package com.salyvn.pluginscope.stack

import com.salyvn.pluginscope.core.BuildInfo
import com.salyvn.pluginscope.core.DescriptorKind
import com.salyvn.pluginscope.core.FindingSink
import com.salyvn.pluginscope.core.JarInspection
import com.salyvn.pluginscope.core.JarReader
import com.salyvn.pluginscope.core.ScanReport
import com.salyvn.pluginscope.core.Severity
import com.salyvn.pluginscope.data.JavaRuntimes
import com.salyvn.pluginscope.data.ServerRuntime
import java.nio.file.Path

/**
 * Audits a whole `plugins/` directory as one system.
 *
 * Everything here is a property of the *set*, not of any single jar: which dependency is missing,
 * which two plugins want the same command, which library exists twice in two different builds.
 * These are the failures that appear after an install and look like "the server broke on its own".
 */
class StackAuditor(private val foliaTarget: Boolean = false) {

    private data class Installed(
        val path: Path,
        val jar: JarInspection,
        val name: String,
        val version: String?,
        val provides: List<String>,
    )

    fun audit(directory: Path, jars: List<Path>): ScanReport {
        val sink = FindingSink()
        val pairs = jars.map { it to JarReader.read(it) }
        val inspections = pairs.map { it.second }
        val plugins = pairs.mapNotNull { (path, jar) ->
            val descriptor = jar.descriptor ?: return@mapNotNull null
            val name = descriptor.name ?: return@mapNotNull null
            Installed(path, jar, name, descriptor.version, descriptor.provides)
        }
        val libraries = inspections.filter { it.descriptor == null }

        val knownNames = plugins.flatMap { listOf(it.name) + it.provides }.map { it.lowercase() }.toSet()

        checkIdentity(plugins, sink)
        checkDependencies(plugins, knownNames, sink)
        checkClassloaderIsolation(plugins, sink)
        checkCommands(plugins, sink)
        checkSharedLibraries(plugins, sink)
        checkRuntime(inspections, plugins, sink)

        return ScanReport(
            tool = "${BuildInfo.NAME} stack",
            toolVersion = BuildInfo.version,
            subject = directory.toAbsolutePath().normalize().toString(),
            subjectKind = "plugins directory",
            findings = sink.findings(),
            facts = buildMap {
                put("jars", jars.size.toString())
                put("plugins", plugins.size.toString())
                if (libraries.isNotEmpty()) put("non-plugin jars", libraries.size.toString())
                put(
                    "total size",
                    String.format(
                        java.util.Locale.ROOT,
                        "%.1f MiB",
                        inspections.sumOf { it.sizeBytes } / 1024.0 / 1024.0,
                    ),
                )
                put("target", if (foliaTarget) "Folia" else "Paper")
                for (plugin in plugins.sortedBy { it.name.lowercase() }) {
                    val jar = plugin.jar
                    val descriptor = jar.descriptor
                    val java = jar.maxMajorVersion?.let { "java ${JavaRuntimes.releaseFor(it)}" } ?: "java ?"
                    val api = descriptor?.apiVersion?.let { "api $it" } ?: "api none"
                    val folia = if (descriptor?.foliaSupported == true) "folia" else ""
                    put(
                        "  ${plugin.name}",
                        listOf(plugin.version ?: "?", api, java, folia).filter { it.isNotEmpty() }.joinToString("  "),
                    )
                }
            },
        )
    }

    private fun checkIdentity(plugins: List<Installed>, sink: FindingSink) {
        plugins.groupBy { it.name.lowercase() }
            .filterValues { it.size > 1 }
            .forEach { (_, clash) ->
                sink.add(
                    StackRules.DUPLICATE_PLUGIN_NAME,
                    location = clash.first().name,
                    detail = "${clash.size} jars declare this plugin name.",
                    evidence = clash.map { "${it.jar.fileName} (version ${it.version ?: "?"})" },
                )
            }

        plugins.filter { it.jar.descriptor?.main != null }
            .groupBy { it.jar.descriptor!!.main!! }
            .filterValues { it.size > 1 }
            .forEach { (main, clash) ->
                sink.add(
                    StackRules.DUPLICATE_MAIN_CLASS,
                    location = main,
                    detail = "Declared as the main class by ${clash.size} jars.",
                    evidence = clash.map { "${it.jar.fileName} -> ${it.name}" },
                )
            }

        val realNames = plugins.map { it.name.lowercase() }.toSet()
        for (plugin in plugins) {
            for (claim in plugin.provides) {
                if (claim.lowercase() in realNames && !claim.equals(plugin.name, ignoreCase = true)) {
                    sink.add(
                        StackRules.NAME_SHADOWED_BY_PROVIDES,
                        location = "${plugin.name} provides $claim",
                        detail = "$claim is also installed as a plugin in its own right.",
                    )
                }
            }
        }
    }

    private fun checkDependencies(plugins: List<Installed>, knownNames: Set<String>, sink: FindingSink) {
        for (plugin in plugins) {
            val descriptor = plugin.jar.descriptor ?: continue
            for (dependency in descriptor.dependencies) {
                val present = dependency.name.lowercase() in knownNames
                if (present) continue
                if (dependency.required) {
                    sink.add(
                        StackRules.MISSING_HARD_DEPENDENCY,
                        location = "${plugin.name} -> ${dependency.name}",
                        detail = "${plugin.name} requires ${dependency.name}, which is not in this directory.",
                    )
                } else {
                    sink.add(
                        StackRules.MISSING_SOFT_DEPENDENCY,
                        location = "${plugin.name} -> ${dependency.name}",
                        detail = "Optional integration with ${dependency.name} is inactive: not installed.",
                    )
                }
            }
            for (target in descriptor.loadBefore) {
                if (target.lowercase() !in knownNames) {
                    sink.add(
                        StackRules.MISSING_SOFT_DEPENDENCY,
                        location = "${plugin.name} loadbefore ${target}",
                        detail = "Load-order target ${target} is not installed.",
                    )
                }
            }
        }
        findCycles(plugins).forEach { cycle ->
            sink.add(
                StackRules.DEPENDENCY_CYCLE,
                location = cycle.joinToString(" -> "),
                detail = "These plugins require each other, so the loader cannot order them.",
            )
        }
    }

    /**
     * Audits cross-plugin bytecode references against descriptor dependency declarations and
     * Paper's isolated PaperPluginClassLoader / has-open-classloader visibility boundaries.
     */
    private fun checkClassloaderIsolation(plugins: List<Installed>, sink: FindingSink) {
        if (plugins.size < 2) return

        val byCanonicalName = mutableMapOf<String, Installed>()
        for (plugin in plugins) {
            byCanonicalName[plugin.name.lowercase()] = plugin
            for (provided in plugin.provides) {
                byCanonicalName.putIfAbsent(provided.lowercase(), plugin)
            }
        }

        // Map each own-package class that belongs to a single installed plugin.
        val classOwners = mutableMapOf<String, MutableList<Installed>>()
        for (plugin in plugins) {
            for (entry in plugin.jar.classes) {
                if (plugin.jar.isOwnCode(entry.internalName) && !ServerRuntime.isServerNamespace(entry.internalName)) {
                    classOwners.getOrPut(entry.internalName) { mutableListOf() } += plugin
                }
            }
        }
        val uniqueOwnClasses: Map<String, Installed> = classOwners
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }

        val blockedPairsReported = mutableSetOf<Pair<String, String>>()

        // Descriptor-level check: legacy plugin.yml depending on a paper-plugin.yml with has-open-classloader: false.
        for (plugin in plugins) {
            val desc = plugin.jar.descriptor ?: continue
            if (desc.kind != DescriptorKind.LEGACY) continue
            for (dep in desc.dependencies) {
                val target = byCanonicalName[dep.name.lowercase()] ?: continue
                if (target.name.equals(plugin.name, ignoreCase = true)) continue
                val targetDesc = target.jar.descriptor ?: continue
                if (targetDesc.kind == DescriptorKind.PAPER && targetDesc.hasOpenClassloader == false) {
                    val key = plugin.name.lowercase() to target.name.lowercase()
                    if (blockedPairsReported.add(key)) {
                        sink.add(
                            StackRules.ISOLATED_CLASSLOADER_BLOCKED,
                            location = "${plugin.name} -> ${target.name}",
                            detail = "Legacy plugin.yml plugin ${plugin.name} depends on Paper plugin " +
                                "${target.name}, which sets has-open-classloader: false and blocks class visibility.",
                            evidence = listOf("${plugin.jar.fileName} (plugin.yml) -> ${target.jar.fileName} (has-open-classloader: false)"),
                        )
                    }
                }
            }
        }

        if (uniqueOwnClasses.isEmpty()) return

        for (plugin in plugins) {
            val desc = plugin.jar.descriptor ?: continue
            val withUses = JarReader.read(plugin.path) { owner -> owner in uniqueOwnClasses }
            val crossRefsByTarget = linkedMapOf<Installed, MutableList<String>>()

            for (entry in withUses.classes) {
                if (!plugin.jar.isOwnCode(entry.internalName)) continue
                val fromDotted = entry.internalName.replace('/', '.')
                entry.superName?.let { sup ->
                    val ownerPlugin = uniqueOwnClasses[sup]
                    if (ownerPlugin != null && !ownerPlugin.name.equals(plugin.name, ignoreCase = true)) {
                        crossRefsByTarget.getOrPut(ownerPlugin) { mutableListOf() } +=
                            "$fromDotted extends ${sup.replace('/', '.')}"
                    }
                }
                for (iface in entry.interfaces) {
                    val ownerPlugin = uniqueOwnClasses[iface]
                    if (ownerPlugin != null && !ownerPlugin.name.equals(plugin.name, ignoreCase = true)) {
                        crossRefsByTarget.getOrPut(ownerPlugin) { mutableListOf() } +=
                            "$fromDotted implements ${iface.replace('/', '.')}"
                    }
                }
            }

            for (use in withUses.memberUses) {
                if (!plugin.jar.isOwnCode(use.fromClass)) continue
                val ownerPlugin = uniqueOwnClasses[use.owner] ?: continue
                if (ownerPlugin.name.equals(plugin.name, ignoreCase = true)) continue
                val lineSuffix = use.line?.let { ":$it" } ?: ""
                crossRefsByTarget.getOrPut(ownerPlugin) { mutableListOf() } +=
                    "${use.fromClass.replace('/', '.')}#${use.fromMethod.substringBefore('(')}$lineSuffix -> ${use.render()}"
            }

            for ((target, refs) in crossRefsByTarget) {
                val targetNames = (listOf(target.name) + target.provides).map { it.lowercase() }.toSet()
                val matchingDeps = desc.dependencies.filter { it.name.lowercase() in targetNames }
                val sampleEvidence = refs.distinct().take(4)
                val pairKey = plugin.name.lowercase() to target.name.lowercase()

                val targetDesc = target.jar.descriptor
                if (desc.kind == DescriptorKind.LEGACY &&
                    targetDesc?.kind == DescriptorKind.PAPER &&
                    targetDesc.hasOpenClassloader == false
                ) {
                    if (blockedPairsReported.add(pairKey)) {
                        sink.add(
                            StackRules.ISOLATED_CLASSLOADER_BLOCKED,
                            location = "${plugin.name} -> ${target.name}",
                            detail = "Legacy plugin.yml plugin ${plugin.name} references classes in Paper plugin " +
                                "${target.name}, whose paper-plugin.yml sets has-open-classloader: false.",
                            evidence = sampleEvidence,
                        )
                    }
                    continue
                }

                if (matchingDeps.isEmpty()) {
                    val isPaper = desc.kind == DescriptorKind.PAPER
                    sink.add(
                        StackRules.UNDECLARED_PLUGIN_DEPENDENCY,
                        location = "${plugin.name} -> ${target.name}",
                        detail = if (isPaper) {
                            "${plugin.name} (paper-plugin.yml) references ${target.name} classes without declaring " +
                                "${target.name} in dependencies.server; PaperPluginClassLoader throws NoClassDefFoundError."
                        } else {
                            "${plugin.name} (plugin.yml) references ${target.name} classes without declaring " +
                                "${target.name} in depend or softdepend; load order is unenforced."
                        },
                        evidence = sampleEvidence,
                        severityOverride = if (isPaper) Severity.CRITICAL else Severity.WARNING,
                    )
                    continue
                }

                if (desc.kind == DescriptorKind.PAPER) {
                    val serverDeps = matchingDeps.filter { it.phase == null || it.phase == "server" }
                    if (serverDeps.isEmpty()) {
                        sink.add(
                            StackRules.ISOLATED_CLASSLOADER_BLOCKED,
                            location = "${plugin.name} -> ${target.name}",
                            detail = "${plugin.name} declares ${target.name} only in dependencies.bootstrap, " +
                                "so PaperPluginClassLoader blocks runtime access to ${target.name} classes.",
                            evidence = sampleEvidence,
                        )
                    } else if (serverDeps.all { it.joinClasspath == false }) {
                        sink.add(
                            StackRules.ISOLATED_CLASSLOADER_BLOCKED,
                            location = "${plugin.name} -> ${target.name}",
                            detail = "${plugin.name} sets join-classpath: false on ${target.name} in paper-plugin.yml " +
                                "while its bytecode directly references ${target.name} classes.",
                            evidence = sampleEvidence,
                        )
                    }
                }
            }
        }
    }

    /** Depth-first search over hard dependencies, reporting each cycle once. */
    private fun findCycles(plugins: List<Installed>): List<List<String>> {
        val byName = plugins.associateBy { it.name.lowercase() }
        val edges = plugins.associate { plugin ->
            plugin.name.lowercase() to plugin.jar.descriptor!!.hardDependencies
                .map { it.lowercase() }
                .filter { it in byName }
        }
        val cycles = mutableListOf<List<String>>()
        val state = mutableMapOf<String, Int>() // 0 = unvisited, 1 = on stack, 2 = done
        val stack = ArrayDeque<String>()

        fun visit(node: String) {
            state[node] = 1
            stack.addLast(node)
            for (next in edges[node].orEmpty()) {
                when (state[next] ?: 0) {
                    0 -> visit(next)
                    1 -> {
                        val start = stack.indexOf(next)
                        val cycle = stack.toList().drop(start).map { byName[it]!!.name } + byName[next]!!.name
                        if (cycles.none { it.toSet() == cycle.toSet() }) cycles += cycle
                    }
                }
            }
            stack.removeLast()
            state[node] = 2
        }

        edges.keys.forEach { if ((state[it] ?: 0) == 0) visit(it) }
        return cycles
    }

    private fun checkCommands(plugins: List<Installed>, sink: FindingSink) {
        val owners = mutableMapOf<String, MutableList<String>>()
        val aliasOwners = mutableMapOf<String, MutableList<String>>()
        for (plugin in plugins) {
            val commands = plugin.jar.descriptor?.commands ?: continue
            for ((command, aliases) in commands) {
                owners.getOrPut(command.lowercase()) { mutableListOf() } += plugin.name
                for (alias in aliases) aliasOwners.getOrPut(alias.lowercase()) { mutableListOf() } += plugin.name
            }
        }
        owners.filterValues { it.distinct().size > 1 }.forEach { (command, claimants) ->
            sink.add(
                StackRules.COMMAND_COLLISION,
                location = "/$command",
                detail = "Registered by ${claimants.size} plugins: ${claimants.joinToString(", ")}.",
            )
        }
        for ((name, claims) in aliasOwners) {
            val byAlias = claims.distinct()
            val byCommand = owners[name].orEmpty().distinct()
            // The plain name being contested is the bigger problem and is already reported.
            if (byCommand.size > 1) continue
            val elsewhere = byCommand.filter { it !in byAlias }
            if (byAlias.size < 2 && elsewhere.isEmpty()) continue
            sink.add(
                StackRules.ALIAS_COLLISION,
                location = "/$name",
                detail = if (elsewhere.isEmpty()) {
                    "Used as an alias by ${byAlias.joinToString(", ")}."
                } else {
                    "An alias of ${byAlias.joinToString(", ")}, and the command name of " +
                        "${elsewhere.joinToString(", ")}."
                },
            )
        }
    }

    private fun checkSharedLibraries(plugins: List<Installed>, sink: FindingSink) {
        data class Shipment(val plugin: String, val classes: Int, val fingerprint: Int, val bytes: Long)

        val shipments = mutableMapOf<String, MutableList<Shipment>>()
        for (plugin in plugins) {
            val jar = plugin.jar
            // Group by library rather than by package: slf4j alone spreads over org.slf4j,
            // org.slf4j.helpers and org.slf4j.spi, and reporting those separately turns one
            // duplicated library into three findings that say the same thing.
            val byLibrary = jar.bundledPackages()
                .filterNot { ServerRuntime.isServerNamespace(it.root) }
                .groupBy { libraryRootOf(it.root) }

            for ((root, groups) in byLibrary) {
                val classes = jar.classes.filter { it.internalName == root || it.internalName.startsWith("$root/") }
                val minRequired = minClassesForRoot(root)
                if (classes.size < minRequired) continue
                val fingerprint = classes
                    .sortedBy { it.internalName }
                    .joinToString("|") { cls ->
                        "${cls.internalName}@${cls.majorVersion}:m=${cls.methods.sorted().joinToString(",")}:f=${cls.fields.sorted().joinToString(",")}"
                    }
                    .hashCode()
                shipments.getOrPut(root) { mutableListOf() } += Shipment(
                    plugin = plugin.name,
                    classes = classes.size,
                    fingerprint = fingerprint,
                    bytes = groups.sumOf { it.sizeBytes },
                )
            }
        }

        for ((packageRoot, list) in shipments) {
            if (list.size < 2) continue
            val dotted = packageRoot.replace('/', '.')
            val distinctBuilds = list.map { it.fingerprint }.distinct().size
            val provided = ServerRuntime.providedFor(packageRoot)
            val highRisk = ServerRuntime.highRiskRootFor(packageRoot)
            val evidence = list.sortedBy { it.plugin }
                .map { "${it.plugin}: ${it.classes} classes, ${it.bytes / 1024} KiB" } +
                listOfNotNull(
                    provided?.let { "the server itself provides ${it.artifact}" },
                    highRisk?.second,
                )
            if (highRisk != null) {
                val buildSummary = if (distinctBuilds > 1) {
                    "in $distinctBuilds incompatible ABI builds"
                } else {
                    "without package relocation"
                }
                sink.add(
                    StackRules.TRANSITIVE_SHADING_COLLISION,
                    location = dotted,
                    detail = "High-risk transitive library $dotted shaded by ${list.size} plugins $buildSummary.",
                    evidence = evidence,
                )
            } else if (distinctBuilds > 1) {
                sink.add(
                    StackRules.SHARED_LIBRARY_SKEW,
                    location = dotted,
                    detail = "Shipped unrelocated by ${list.size} plugins in $distinctBuilds different builds.",
                    evidence = evidence,
                )
            } else {
                sink.add(
                    StackRules.SHARED_LIBRARY_DUPLICATED,
                    location = dotted,
                    detail = "Identical copy shipped by ${list.size} plugins, wasting " +
                        "${list.drop(1).sumOf { it.bytes } / 1024} KiB.",
                    evidence = evidence,
                )
            }
        }
    }

    /**
     * The package a library owns, so its subpackages collapse into one entry. High-risk transitive
     * libraries (org.bstats, net.kyori, com.zaxxer.hikari, etc.) and server-provided roots collapse
     * to their canonical prefix; the Kotlin runtime collapses to its top segment.
     */
    private fun libraryRootOf(packageRoot: String): String = when {
        ServerRuntime.highRiskRootFor(packageRoot) != null -> ServerRuntime.highRiskRootFor(packageRoot)!!.first
        ServerRuntime.providedFor(packageRoot) != null -> ServerRuntime.providedFor(packageRoot)!!.packageRoot
        ServerRuntime.isKotlinRuntime(packageRoot) -> packageRoot.substringBefore('/')
        else -> packageRoot
    }

    private fun minClassesForRoot(root: String): Int = when {
        root == "org/bstats" -> 1
        ServerRuntime.highRiskRootFor(root) != null -> 2
        else -> MIN_CLASSES
    }

    private fun checkRuntime(inspections: List<JarInspection>, plugins: List<Installed>, sink: FindingSink) {
        val highest = inspections.mapNotNull { jar -> jar.maxMajorVersion?.let { it to jar } }.maxByOrNull { it.first }
        if (highest != null) {
            val (major, jar) = highest
            sink.add(
                StackRules.JAVA_REQUIREMENT,
                location = jar.fileName,
                detail = "Highest class file version is $major, so this set needs Java " +
                    "${JavaRuntimes.releaseFor(major)} or newer.",
            )
        }

        for (plugin in plugins) {
            val descriptor = plugin.jar.descriptor ?: continue
            if (descriptor.apiVersion == null && descriptor.kind == com.salyvn.pluginscope.core.DescriptorKind.LEGACY) {
                sink.add(
                    StackRules.LEGACY_PLUGIN_PRESENT,
                    location = plugin.name,
                    detail = "No api-version in plugin.yml, so the server applies legacy conversion to it.",
                )
            }
            if (foliaTarget && descriptor.foliaSupported != true) {
                sink.add(
                    StackRules.FOLIA_MIX,
                    location = plugin.name,
                    detail = "folia-supported is not declared, so Folia will refuse to load it.",
                )
            }
        }
    }

    private companion object {
        /** Below this, a shared package root is more likely a stray class than a bundled library. */
        const val MIN_CLASSES = 4
    }
}
