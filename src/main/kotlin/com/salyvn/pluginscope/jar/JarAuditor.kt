package com.salyvn.pluginscope.jar

import com.salyvn.pluginscope.core.BuildInfo
import com.salyvn.pluginscope.core.FindingSink
import com.salyvn.pluginscope.core.JarInspection
import com.salyvn.pluginscope.core.JarReader
import com.salyvn.pluginscope.core.ScanReport
import com.salyvn.pluginscope.data.JavaRuntimes
import com.salyvn.pluginscope.data.ServerRuntime
import org.objectweb.asm.Type
import java.nio.file.Path

data class JarAuditOptions(
    /** Java release the plugin is expected to run on. Enables the class-version check. */
    val targetJava: Int? = null,
    val reachability: Boolean = true,
    val disabledRules: Set<String> = emptySet(),
)

/**
 * Audits one plugin jar on its own: descriptor, what is bundled inside it, packaging hygiene, and
 * how much of the bundled code is actually referenced.
 */
class JarAuditor(private val options: JarAuditOptions = JarAuditOptions()) {

    fun audit(path: Path): ScanReport {
        val sink = FindingSink()
        // First pass captures class metadata and server internal references for bytecode verification.
        val jar = JarReader.read(path) { owner ->
            owner.startsWith("net/minecraft/") || owner.startsWith("org/bukkit/craftbukkit/")
        }

        DescriptorChecks.run(jar, sink)
        BundlingChecks.run(jar, options.targetJava, sink)
        HygieneChecks.run(jar, sink)
        LifecycleChecks.run(path, jar, sink)
        BytecodeCodecAndAsmChecks.run(path, jar, options.targetJava, sink)
        suggestRuntimeLibraries(jar, sink)
        if (options.reachability) reportReachability(path, jar, sink)

        val findings = sink.findings().filterNot { it.ruleId in options.disabledRules }
        return ScanReport(
            tool = "${BuildInfo.NAME} jar",
            toolVersion = BuildInfo.version,
            subject = jar.path,
            subjectKind = "jar",
            findings = findings,
            facts = facts(jar),
        )
    }

    private fun facts(jar: JarInspection): Map<String, String> = buildMap {
        val descriptor = jar.descriptor
        put("plugin", descriptor?.name ?: "(not a plugin)")
        descriptor?.version?.let { put("version", it) }
        descriptor?.let { put("descriptor", it.kind.entryName) }
        put("api-version", descriptor?.apiVersion ?: "(none)")
        put("size", formatMib(jar.sizeBytes))
        put("classes", "${jar.classes.size} (${jar.classes.count { jar.isOwnCode(it.internalName) }} own)")
        jar.maxMajorVersion?.let { put("needs java", JavaRuntimes.releaseFor(it).toString()) }
        jar.ownPackageRoot?.let { put("own package", it.replace('/', '.')) }
        put("kotlin", if (jar.classes.any { it.isKotlin }) "yes" else "no")
        descriptor?.foliaSupported?.let { put("folia-supported", it.toString()) }
    }

    /**
     * Suggests moving bulk out of the jar when the descriptor does not already use `libraries:`.
     * Only fires when the bundled weight is worth the change.
     */
    private fun suggestRuntimeLibraries(jar: JarInspection, sink: FindingSink) {
        val descriptor = jar.descriptor ?: return
        if (descriptor.libraries.isNotEmpty()) return
        val candidates = jar.bundledPackages()
            .filterNot { ServerRuntime.isServerNamespace(it.root) }
            .filterNot { ServerRuntime.isKotlinRuntime(it.root) }
            .filter { it.sizeBytes > LIBRARY_HINT_BYTES }
        if (candidates.isEmpty()) return
        sink.add(
            JarRules.RUNTIME_LIBRARIES_HINT,
            location = descriptor.kind.entryName,
            detail = "${candidates.size} bundled library(ies) over ${LIBRARY_HINT_BYTES / 1024} KiB and no " +
                "`libraries:` block in the descriptor.",
            evidence = candidates.sortedByDescending { it.sizeBytes }.take(4)
                .map { "${it.dotted}: ${it.sizeBytes / 1024} KiB" },
        )
    }

    /**
     * Reachability over the jar's own reference graph, seeded from the descriptor entry points, the
     * plugin's own classes and anything named in `META-INF/services`.
     *
     * Deliberately reported as a hint: reflection, Class.forName and resource-driven wiring are all
     * invisible here, so a low ratio means "look", not "delete".
     */
    private fun reportReachability(path: Path, jar: JarInspection, sink: FindingSink) {
        val inJar = jar.classes.map { it.internalName }.toHashSet()
        if (inJar.isEmpty()) return
        val withEdges = JarReader.read(path) { owner -> owner in inJar }

        val edges = HashMap<String, MutableSet<String>>()
        for (entry in withEdges.classes) {
            val out = edges.getOrPut(entry.internalName) { mutableSetOf() }
            entry.superName?.takeIf { it in inJar }?.let { out += it }
            out += entry.interfaces.filter { it in inJar }
        }
        for (use in withEdges.memberUses) {
            val out = edges.getOrPut(use.fromClass) { mutableSetOf() }
            out += use.owner
            // Types that only appear in a descriptor still create a link at verification time.
            if (use.descriptor.startsWith("(")) {
                for (type in Type.getArgumentTypes(use.descriptor) + Type.getReturnType(use.descriptor)) {
                    internalNameOf(type)?.takeIf { it in inJar }?.let { out += it }
                }
            } else if (use.descriptor.isNotEmpty()) {
                internalNameOf(Type.getType(use.descriptor))?.takeIf { it in inJar }?.let { out += it }
            }
        }

        val roots = buildSet {
            jar.descriptor?.mainInternalName?.let { if (it in inJar) add(it) }
            jar.descriptor?.bootstrapper?.replace('.', '/')?.let { if (it in inJar) add(it) }
            jar.descriptor?.loader?.replace('.', '/')?.let { if (it in inJar) add(it) }
            addAll(jar.classes.map { it.internalName }.filter { jar.isOwnCode(it) })
            addAll(serviceImplementations(withEdges).filter { it in inJar })
        }
        if (roots.isEmpty()) return

        val reached = HashSet<String>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!reached.add(current)) continue
            edges[current]?.forEach { if (it !in reached) queue.addLast(it) }
        }

        for (group in jar.bundledPackages()) {
            if (group.classCount < MIN_PACKAGE_FOR_REACHABILITY) continue
            if (ServerRuntime.isServerNamespace(group.root)) continue
            val members = jar.classes.filter { it.internalName.startsWith("${group.root}/") }
            val used = members.count { it.internalName in reached }
            val ratio = used.toDouble() / members.size
            if (ratio < UNUSED_RATIO) {
                sink.add(
                    JarRules.UNREACHABLE_BUNDLE,
                    location = group.dotted,
                    detail = "$used of ${members.size} classes are referenced (" +
                        "${(ratio * 100).toInt()}%), about ${(group.sizeBytes * (1 - ratio)).toLong() / 1024} KiB unused.",
                    evidence = listOf("roots: ${roots.size} classes, reached ${reached.size} of ${inJar.size}"),
                )
            }
        }
    }

    /** Class names listed under `META-INF/services`, which are instantiated reflectively. */
    private fun serviceImplementations(jar: JarInspection): List<String> = buildList {
        addAll(jar.resources
            .filter { it.entryName.startsWith("META-INF/services/") && !it.isDirectory }
            .map { it.entryName.removePrefix("META-INF/services/").replace('.', '/') })
        addAll(jar.serviceProviders.values.flatten().map { it.replace('.', '/') })
    }

    private fun internalNameOf(type: Type): String? = when (type.sort) {
        Type.OBJECT -> type.internalName
        Type.ARRAY -> internalNameOf(type.elementType)
        else -> null
    }

    private companion object {
        const val LIBRARY_HINT_BYTES = 512L * 1024
        const val MIN_PACKAGE_FOR_REACHABILITY = 25
        const val UNUSED_RATIO = 0.35

        /** Locale.ROOT so the decimal separator does not change with the machine's locale. */
        fun formatMib(bytes: Long): String =
            String.format(java.util.Locale.ROOT, "%.2f MiB", bytes / 1024.0 / 1024.0)
    }
}
