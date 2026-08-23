package com.salyvn.pluginscope.jar

import com.salyvn.pluginscope.core.DescriptorKind
import com.salyvn.pluginscope.core.FindingSink
import com.salyvn.pluginscope.core.JarInspection
import com.salyvn.pluginscope.data.McVersions

/** Checks that only read the plugin descriptor. Split out because there are a lot of them. */
internal object DescriptorChecks {

    /**
     * `VALID_NAME` as compiled into paper-api: `^[A-Za-z0-9 _.-]+$`. Read out of
     * `org/bukkit/plugin/PluginDescriptionFile.class` in paper-api 26.2.build.116-stable, which is
     * why the space is in there — it is genuinely accepted, however odd it looks.
     */
    private val LEGAL_NAME = Regex("^[A-Za-z0-9 _.-]+$")

    fun run(jar: JarInspection, sink: FindingSink) {
        val descriptor = jar.descriptor
        if (descriptor == null) {
            sink.add(
                JarRules.NOT_A_PLUGIN,
                location = jar.fileName,
                detail = "No plugin.yml and no paper-plugin.yml at the jar root.",
                evidence = listOf("${jar.classes.size} classes, ${jar.resources.size} resources"),
            )
            return
        }

        if (jar.descriptors.size > 1) {
            sink.add(
                JarRules.BOTH_DESCRIPTORS,
                location = jar.fileName,
                detail = "paper-plugin.yml takes effect; plugin.yml is ignored on Paper.",
                evidence = jar.descriptors.keys.map { it.entryName },
            )
        }

        for (error in descriptor.parseErrors) {
            sink.add(JarRules.DESCRIPTOR_UNPARSEABLE, location = descriptor.kind.entryName, detail = error)
        }

        val missing = buildList {
            if (descriptor.name.isNullOrBlank()) add("name")
            if (descriptor.version.isNullOrBlank()) add("version")
            if (descriptor.main.isNullOrBlank()) add("main")
        }
        if (missing.isNotEmpty()) {
            sink.add(
                JarRules.DESCRIPTOR_INCOMPLETE,
                location = descriptor.kind.entryName,
                detail = "Missing required field(s): ${missing.joinToString(", ")}.",
            )
        }

        descriptor.name?.let { name ->
            if (!LEGAL_NAME.matches(name)) {
                sink.add(
                    JarRules.NAME_ILLEGAL,
                    location = descriptor.kind.entryName,
                    detail = "name is '$name'. Accepted characters are letters, digits, space, " +
                        "underscore, hyphen and dot.",
                )
            }
        }

        descriptor.main?.let { main ->
            if (main.startsWith("org.bukkit.")) {
                sink.add(
                    JarRules.MAIN_IN_SERVER_NAMESPACE,
                    location = descriptor.kind.entryName,
                    detail = "main is '$main'. The loader refuses a main class inside org.bukkit.",
                )
            }
        }

        descriptor.mainInternalName?.let { main ->
            if (!jar.contains(main)) {
                val near = jar.classes.map { it.internalName }
                    .filter { it.substringAfterLast('/') == main.substringAfterLast('/') }
                    .take(3)
                sink.add(
                    JarRules.MAIN_CLASS_MISSING,
                    location = descriptor.main!!,
                    detail = "The declared main class is not among this jar's ${jar.classes.size} classes.",
                    evidence = if (near.isEmpty()) emptyList() else near.map { "same simple name: ${it.replace('/', '.')}" },
                )
            }
        }

        if (descriptor.unknownKeys.isNotEmpty()) {            sink.add(
                JarRules.DESCRIPTOR_UNKNOWN_KEY,
                location = descriptor.kind.entryName,
                detail = "Ignored by the server: ${descriptor.unknownKeys.joinToString(", ")}.",
                evidence = descriptor.unknownKeys.mapNotNull { key ->
                    suggestion(key)?.let { "'$key' looks like '$it'" }
                },
            )
        }

        for ((key, scalar) in descriptor.retypedScalars) {
            sink.add(
                JarRules.UNQUOTED_VERSION_SCALAR,
                location = "${descriptor.kind.entryName}: $key",
                detail = "Written as ${scalar.written}, but the server reads ${scalar.loaded}: " +
                    "YAML resolved it as a number.",
                evidence = listOf("quote the value to keep it exactly as written: $key: '${scalar.written}'"),
            )
        }

        checkApiVersion(descriptor.apiVersion, descriptor.kind, sink)

        checkDependencyHygiene(jar, sink)

        if (descriptor.foliaSupported == true) {
            sink.add(
                JarRules.FOLIA_DECLARATION,
                location = descriptor.kind.entryName,
                detail = "folia-supported: true. The key controls loading only, not thread safety.",
            )
        }
    }

    private fun checkApiVersion(apiVersion: String?, kind: DescriptorKind, sink: FindingSink) {
        if (apiVersion == null) {
            if (kind == DescriptorKind.LEGACY) {
                sink.add(
                    JarRules.API_VERSION_ABSENT,
                    location = kind.entryName,
                    detail = "No api-version, so the server treats this plugin as pre-1.13 legacy.",
                )
            } else {
                sink.add(
                    JarRules.DESCRIPTOR_INCOMPLETE,
                    location = kind.entryName,
                    detail = "paper-plugin.yml has no api-version. Paper plugins are required to declare one.",
                )
            }
            return
        }
        when (McVersions.shapeOf(apiVersion)) {
            McVersions.ApiVersionShape.MALFORMED -> sink.add(
                JarRules.API_VERSION_UNKNOWN,
                location = kind.entryName,
                detail = "api-version '$apiVersion' is not a Minecraft version string.",
                evidence = listOf(
                    "accepted forms: major.minor[.patch] (1.21, 1.21.4), the current <year>.<release> form (26.2), or 'none'",
                ),
            )

            McVersions.ApiVersionShape.EXPLICIT_NONE -> sink.add(
                JarRules.API_VERSION_ABSENT,
                location = kind.entryName,
                detail = "api-version is 'none', which opts into the same legacy handling as leaving it out.",
                severityOverride = com.salyvn.pluginscope.core.Severity.INFO,
            )

            else -> Unit
        }
    }

    private fun checkDependencyHygiene(jar: JarInspection, sink: FindingSink) {
        val descriptor = jar.descriptor ?: return
        val own = descriptor.name?.lowercase()
        val hard = descriptor.hardDependencies.map { it.lowercase() }
        val soft = descriptor.softDependencies.map { it.lowercase() }

        val selfReferences = (hard + soft + descriptor.loadBefore.map { it.lowercase() })
            .filter { own != null && it == own }
        if (selfReferences.isNotEmpty()) {
            sink.add(
                JarRules.DEPENDENCY_SUSPECT,
                location = descriptor.kind.entryName,
                detail = "The plugin lists itself as a dependency or load-order target.",
            )
        }

        val both = hard.toSet() intersect soft.toSet()
        if (both.isNotEmpty()) {
            sink.add(
                JarRules.DEPENDENCY_SUSPECT,
                location = descriptor.kind.entryName,
                detail = "Listed as both a hard and a soft dependency: ${both.joinToString(", ")}.",
            )
        }

        val duplicates = (hard + soft).groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            sink.add(
                JarRules.DEPENDENCY_SUSPECT,
                location = descriptor.kind.entryName,
                detail = "Duplicated dependency entries: ${duplicates.joinToString(", ")}.",
            )
        }
    }

    /** Catches the near-misses that silently do nothing, `depends` being the classic. */
    private fun suggestion(key: String): String? {
        val candidates = listOf(
            "depend", "softdepend", "loadbefore", "api-version", "folia-supported", "libraries",
            "provides", "commands", "permissions", "authors", "website", "prefix", "main", "version",
        )
        return candidates.firstOrNull { candidate ->
            candidate != key && (candidate.startsWith(key) || key.startsWith(candidate) ||
                editDistanceAtMostOne(candidate, key))
        }
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++; j++
                continue
            }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> {
                    i++; j++
                }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
