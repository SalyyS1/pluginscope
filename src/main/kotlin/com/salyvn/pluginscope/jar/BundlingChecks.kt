package com.salyvn.pluginscope.jar

import com.salyvn.pluginscope.core.FindingSink
import com.salyvn.pluginscope.core.JarInspection
import com.salyvn.pluginscope.data.JavaRuntimes
import com.salyvn.pluginscope.data.ServerRuntime

/**
 * Checks about what the jar carries: whose code is inside it, whether it was relocated, and which
 * Java release the class files need.
 */
internal object BundlingChecks {

    fun run(jar: JarInspection, targetJava: Int?, sink: FindingSink) {
        val bundled = jar.bundledPackages()
        // The Kotlin runtime spreads over dozens of subpackages. Reporting each one separately
        // would bury every other finding under forty near-identical lines, so it is summed up.
        val kotlinRuntime = bundled.filter { ServerRuntime.isKotlinRuntime(it.root) }
        if (kotlinRuntime.isNotEmpty()) {
            val classes = kotlinRuntime.sumOf { it.classCount }
            val bytes = kotlinRuntime.sumOf { it.sizeBytes }
            sink.add(
                JarRules.KOTLIN_STDLIB_BUNDLED,
                location = kotlinRuntime.minByOrNull { it.root.length }?.dotted ?: "kotlin",
                detail = "$classes classes across ${kotlinRuntime.size} package(s), ${bytes / 1024} KiB, " +
                    "shipped under their original names.",
                evidence = kotlinRuntime.sortedByDescending { it.sizeBytes }.take(3)
                    .map { "${it.dotted}: ${it.classCount} classes, ${it.sizeBytes / 1024} KiB" },
            )
        }

        for (group in bundled) {
            if (ServerRuntime.isKotlinRuntime(group.root)) continue
            val reason = ServerRuntime.serverNamespaceReason(group.root)
            if (reason != null) {
                sink.add(
                    JarRules.SERVER_NAMESPACE_BUNDLED,
                    location = group.dotted,
                    detail = "${group.classCount} classes in a namespace that is $reason.",
                    evidence = sampleClasses(jar, group.root),
                )
                continue
            }

            val provided = ServerRuntime.providedFor(group.root)
            if (provided != null) {
                val version = provided.versionAtReference.takeUnless { it == "-" }?.let { " $it" } ?: ""
                sink.add(
                    JarRules.PROVIDED_LIBRARY_BUNDLED,
                    location = group.dotted,
                    detail = "${group.classCount} classes, ${group.sizeBytes / 1024} KiB. The server " +
                        "provides ${provided.artifact}$version.",
                    evidence = listOf("server side: ${provided.provenance}"),
                )
                continue
            }

            if (group.classCount >= MIN_LIBRARY_CLASSES) {
                sink.add(
                    JarRules.UNRELOCATED_LIBRARY,
                    location = group.dotted,
                    detail = "${group.classCount} classes, ${group.sizeBytes / 1024} KiB, outside the " +
                        "plugin's own package (${jar.ownPackageRoot?.replace('/', '.') ?: "unknown"}).",
                    evidence = sampleClasses(jar, group.root),
                )
            }
        }

        checkClassVersions(jar, targetJava, sink)
    }

    private fun checkClassVersions(jar: JarInspection, targetJava: Int?, sink: FindingSink) {
        val own = jar.classes.filter { jar.isOwnCode(it.internalName) }
        val ownMax = own.maxOfOrNull { it.majorVersion }
        val allMax = jar.maxMajorVersion ?: return

        if (targetJava != null) {
            val allowed = JavaRuntimes.majorVersionFor(targetJava)
            val offenders = jar.classes.filter { it.majorVersion > allowed }
            if (offenders.isNotEmpty()) {
                val worst = offenders.maxOf { it.majorVersion }
                sink.add(
                    JarRules.CLASS_VERSION_TOO_NEW,
                    location = jar.fileName,
                    detail = "${offenders.size} class(es) are ${JavaRuntimes.describe(worst)} but the " +
                        "target runs Java $targetJava.",
                    evidence = offenders.sortedByDescending { it.majorVersion }.take(5)
                        .map { "${it.internalName.replace('/', '.')} (${JavaRuntimes.describe(it.majorVersion)})" },
                )
            }
        }

        if (ownMax != null && allMax > ownMax) {
            val offenders = jar.classes.filter { it.majorVersion > ownMax }
            sink.add(
                JarRules.CLASS_VERSION_MIXED,
                location = jar.fileName,
                detail = "Own code is ${JavaRuntimes.describe(ownMax)}; ${offenders.size} bundled " +
                    "class(es) need ${JavaRuntimes.describe(allMax)}.",
                evidence = offenders.groupBy { it.internalName.split('/').take(3).joinToString(".") }
                    .entries.sortedByDescending { it.value.size }.take(4)
                    .map { "${it.key}: ${it.value.size} classes" },
            )
        }
    }

    private fun sampleClasses(jar: JarInspection, root: String, limit: Int = 3): List<String> = jar.classes
        .asSequence()
        .filter { it.internalName.startsWith("$root/") }
        .map { it.internalName.replace('/', '.') }
        .sorted()
        .take(limit)
        .toList()

    /** Fewer classes than this outside the own package is noise: a stray helper, not a library. */
    private const val MIN_LIBRARY_CLASSES = 3
}
