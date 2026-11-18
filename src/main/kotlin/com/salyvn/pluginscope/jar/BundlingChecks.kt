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

        val bstatsGroups = bundled.filter { ServerRuntime.isBstats(it.root) }
        if (bstatsGroups.isNotEmpty()) {
            val classes = bstatsGroups.sumOf { it.classCount }
            val bytes = bstatsGroups.sumOf { it.sizeBytes }
            sink.add(
                JarRules.BSTATS_UNRELOCATED,
                location = "org.bstats",
                detail = "$classes bStats class(es) (${bytes / 1024} KiB) shipped under org.bstats " +
                    "instead of ${jar.ownPackageRoot?.replace('/', '.') ?: "the plugin package"}.",
                evidence = sampleClasses(jar, "org/bstats"),
            )
        }

        for (group in bundled) {
            if (ServerRuntime.isKotlinRuntime(group.root) || ServerRuntime.isBstats(group.root)) continue
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
        checkMojangAndVersionedBytecode(jar, targetJava, sink)
        checkPluginRemapperHazards(jar, targetJava, sink)
        checkNmsReflectionLiterals(jar, targetJava, sink)
    }

    private fun checkMojangAndVersionedBytecode(jar: JarInspection, targetJava: Int?, sink: FindingSink) {
        val descriptor = jar.descriptor
        val isModernPaper = descriptor?.apiVersion?.let {
            com.salyvn.pluginscope.data.McVersions.shapeOf(it) == com.salyvn.pluginscope.data.McVersions.ApiVersionShape.CURRENT_NUMBERING
        } == true || (targetJava != null && targetJava >= 25)

        // 1. Check for legacy versioned NMS / CraftBukkit references (net/minecraft/server/v1_*, org/bukkit/craftbukkit/v1_*)
        val versionedUses = jar.memberUses.filter {
            it.owner.startsWith("net/minecraft/server/v1_") || it.owner.startsWith("org/bukkit/craftbukkit/v1_")
        }
        val versionedClasses = jar.classes.filter {
            it.internalName.startsWith("net/minecraft/server/v1_") || it.internalName.startsWith("org/bukkit/craftbukkit/v1_") ||
                it.superName?.let { s -> s.startsWith("net/minecraft/server/v1_") || s.startsWith("org/bukkit/craftbukkit/v1_") } == true ||
                it.interfaces.any { iface -> iface.startsWith("net/minecraft/server/v1_") || iface.startsWith("org/bukkit/craftbukkit/v1_") }
        }

        if (isModernPaper && (versionedUses.isNotEmpty() || versionedClasses.isNotEmpty())) {
            val sampleSites = (versionedUses.map { it.render() } +
                versionedClasses.map { it.internalName.replace('/', '.') }).distinct().take(4)
            sink.add(
                JarRules.LEGACY_VERSIONED_NMS_ON_MODERN_PAPER,
                location = jar.fileName,
                detail = "Plugin targets modern Paper (${descriptor?.apiVersion ?: "Java $targetJava"}) but references " +
                    "legacy versioned NMS or CraftBukkit package(s). Paper 26.x+ has removed versioned package relocation shims.",
                evidence = sampleSites,
            )
        }

        // 2. Check for unversioned Mojang-mapped bytecode in legacy plugin.yml (without paper-plugin.yml)
        val hasPaperDescriptor = com.salyvn.pluginscope.core.DescriptorKind.PAPER in jar.descriptors
        if (descriptor?.kind == com.salyvn.pluginscope.core.DescriptorKind.LEGACY && !hasPaperDescriptor) {
            val mojangUses = jar.memberUses.filter {
                it.owner.startsWith("net/minecraft/") && !it.owner.contains("/v1_")
            }
            val mojangClasses = jar.classes.filter {
                (it.internalName.startsWith("net/minecraft/") && !it.internalName.contains("/v1_")) ||
                    (it.superName?.let { s -> s.startsWith("net/minecraft/") && !s.contains("/v1_") } == true) ||
                    it.interfaces.any { iface -> iface.startsWith("net/minecraft/") && !iface.contains("/v1_") }
            }
            if (mojangUses.isNotEmpty() || mojangClasses.isNotEmpty()) {
                val sampleSites = (mojangUses.map { it.render() } +
                    mojangClasses.map { it.internalName.replace('/', '.') }).distinct().take(4)
                sink.add(
                    JarRules.MOJANG_MAPPED_BYTECODE_IN_LEGACY_DESCRIPTOR,
                    location = jar.fileName,
                    detail = "Legacy plugin.yml plugin references unversioned Mojang-mapped classes. Spigot and pre-1.20.5 " +
                        "servers do not map or expose net.minecraft to legacy plugin classloaders.",
                    evidence = sampleSites,
                )
            }
        }
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

    private fun checkPluginRemapperHazards(jar: JarInspection, targetJava: Int?, sink: FindingSink) {
        val descriptor = jar.descriptor
        val hasPaperDescriptor = com.salyvn.pluginscope.core.DescriptorKind.PAPER in jar.descriptors
        val isModernPaper = descriptor?.apiVersion?.let {
            com.salyvn.pluginscope.data.McVersions.shapeOf(it) == com.salyvn.pluginscope.data.McVersions.ApiVersionShape.CURRENT_NUMBERING
        } == true || (targetJava != null && targetJava >= 25)

        // Case 1: Plugin declares paper-plugin.yml, but references CraftBukkit or legacy NMS in bytecode.
        // Modern Paper (1.20.5+..26.3+) skips PluginRemapper for paper-plugin.yml plugins.
        if (hasPaperDescriptor) {
            val obcUses = jar.memberUses.filter {
                it.owner.startsWith("org/bukkit/craftbukkit/") || it.owner.startsWith("net/minecraft/server/")
            }
            val obcClasses = jar.classes.filter {
                it.internalName.startsWith("org/bukkit/craftbukkit/") || it.internalName.startsWith("net/minecraft/server/") ||
                    it.superName?.let { s -> s.startsWith("org/bukkit/craftbukkit/") || s.startsWith("net/minecraft/server/") } == true ||
                    it.interfaces.any { iface -> iface.startsWith("org/bukkit/craftbukkit/") || iface.startsWith("net/minecraft/server/") }
            }
            if (obcUses.isNotEmpty() || obcClasses.isNotEmpty()) {
                val sampleSites = (obcUses.map { it.render() } +
                    obcClasses.map { it.internalName.replace('/', '.') }).distinct().take(4)
                sink.add(
                    JarRules.PAPER_PLUGIN_REMAPPER_HAZARD,
                    location = jar.fileName,
                    detail = "Plugin declares paper-plugin.yml, causing modern Paper (1.20.5+..26.3+) to bypass " +
                        "PluginRemapper. References to org.bukkit.craftbukkit or net.minecraft.server will fail with " +
                        "ClassNotFoundException / LinkageError because runtime bytecode remapping is disabled for Paper plugins.",
                    evidence = sampleSites,
                )
            }
        }

        // Case 2: Legacy plugin.yml plugin targeting modern Paper (1.21.4+..26.3+) that defines classes
        // dynamically via custom ClassLoader or MethodHandles, bypassing Paper's PluginRemapper.
        if (descriptor?.kind == com.salyvn.pluginscope.core.DescriptorKind.LEGACY && !hasPaperDescriptor && isModernPaper) {
            val dynamicDefUses = jar.memberUses.filter {
                (it.owner == "java/lang/ClassLoader" && it.name.startsWith("defineClass")) ||
                    (it.owner == "java/lang/invoke/MethodHandles\$Lookup" && it.name == "defineClass") ||
                    (it.owner == "java/net/URLClassLoader" && it.name == "<init>")
            }
            if (dynamicDefUses.isNotEmpty()) {
                sink.add(
                    JarRules.PAPER_PLUGIN_REMAPPER_HAZARD,
                    location = jar.fileName,
                    detail = "Legacy plugin.yml targets Paper 1.21.4+..26.3+ and defines classes dynamically via " +
                        "custom ClassLoader or MethodHandles. Dynamically defined classes bypass Paper's runtime " +
                        "PluginRemapper and will fail to resolve remapped server symbols.",
                    evidence = dynamicDefUses.map { it.render() }.distinct().take(4),
                )
            }
        }
    }

    private fun checkNmsReflectionLiterals(jar: JarInspection, targetJava: Int?, sink: FindingSink) {
        val nmsLiterals = jar.stringLiteralUses.filter { use ->
            use.value.contains("net.minecraft") || use.value.contains("net/minecraft") ||
                use.value.contains("org.bukkit.craftbukkit") || use.value.contains("org/bukkit/craftbukkit")
        }
        if (nmsLiterals.isNotEmpty()) {
            val sampleSites = nmsLiterals.map { it.render() }.distinct().take(4)
            sink.add(
                JarRules.UNRELOCATED_NMS_REFLECTION_LITERAL,
                location = jar.fileName,
                detail = "Found ${nmsLiterals.size} unrelocated NMS or CraftBukkit reflection string literal(s) " +
                    "in bytecode. Paper's PluginRemapper and build relocation tools do not rewrite string literals " +
                    "in constant pools, causing ClassNotFoundException or NoSuchFieldException on modern Paper.",
                evidence = sampleSites,
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
