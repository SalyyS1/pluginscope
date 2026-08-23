package com.salyvn.pluginscope.core

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * The two descriptor flavours a server plugin can ship. Both may be present in one jar, in which
 * case Paper uses the paper-plugin.yml one and ignores the legacy file.
 */
enum class DescriptorKind(val entryName: String) {
    LEGACY("plugin.yml"),
    PAPER("paper-plugin.yml"),
}

/** A declared dependency on another plugin, normalised across both descriptor formats. */
data class DependencyRef(
    val name: String,
    val required: Boolean,
    /** `BEFORE` / `AFTER` for paper-plugin.yml; null when the format does not express it. */
    val load: String? = null,
    val joinClasspath: Boolean? = null,
)

/**
 * A value the author typed and the value the YAML parser handed on, when the two differ.
 */
data class RetypedScalar(val written: String, val loaded: String)

/**
 * What a plugin says about itself. Parsed leniently: a malformed descriptor is still worth
 * reporting on, so unreadable fields become null instead of throwing.
 */
data class PluginDescriptor(
    val kind: DescriptorKind,
    val name: String?,
    val version: String?,
    val main: String?,
    val apiVersion: String?,
    val authors: List<String> = emptyList(),
    val dependencies: List<DependencyRef> = emptyList(),
    val loadBefore: List<String> = emptyList(),
    val provides: List<String> = emptyList(),
    val libraries: List<String> = emptyList(),
    /** Command name -> declared aliases. Legacy descriptors only. */
    val commands: Map<String, List<String>> = emptyMap(),
    val permissions: List<String> = emptyList(),
    val foliaSupported: Boolean? = null,
    val bootstrapper: String? = null,
    val loader: String? = null,
    val hasOpenClassloader: Boolean? = null,
    val load: String? = null,
    /** Keys present in the file that the format does not define. */
    val unknownKeys: List<String> = emptyList(),
    /**
     * Keys whose YAML value came back as a number and no longer reads as it was written.
     * `api-version: 1.20` unquoted is the classic case: YAML reads it as the float 1.2, so the
     * value the server ends up with is "1.2".
     */
    val retypedScalars: Map<String, RetypedScalar> = emptyMap(),
    /** Parse problems that did not stop the rest of the file being read. */
    val parseErrors: List<String> = emptyList(),
) {
    val hardDependencies: List<String> get() = dependencies.filter { it.required }.map { it.name }
    val softDependencies: List<String> get() = dependencies.filterNot { it.required }.map { it.name }

    /** Internal binary name of the main class, e.g. `com/example/Plugin`. */
    val mainInternalName: String? get() = main?.replace('.', '/')
}

/**
 * Reads both descriptor formats. Loaded through [SafeConstructor] with an aliasing cap because the
 * YAML comes out of a jar the user did not necessarily build: arbitrary type instantiation and
 * billion-laughs expansion are both refused.
 */
object DescriptorParser {

    /**
     * Keys `PluginDescriptionFile` actually reads, taken from the constant pool of
     * `org/bukkit/plugin/PluginDescriptionFile.class` in paper-api 26.2.build.116-stable, plus
     * `folia-supported`, which the server reads outside that class.
     */
    private val LEGACY_KEYS = setOf(
        "name", "version", "main", "description", "author", "authors", "contributors", "website",
        "prefix", "api-version", "load", "depend", "softdepend", "loadbefore", "provides",
        "libraries", "commands", "permissions", "default-permission", "awareness", "class-loader-of",
        "paper-plugin-loader", "paper-skip-libraries", "folia-supported",
    )

    private val PAPER_KEYS = setOf(
        "name", "version", "main", "description", "author", "authors", "contributors", "website",
        "prefix", "api-version", "load", "bootstrapper", "loader", "has-open-classloader",
        "dependencies", "provides", "permissions", "default-permission", "folia-supported",
    )

    /**
     * Keys the formats define as strings, where an unquoted value can silently change meaning:
     * YAML resolves `1.20` to a float and prints it back as `1.2`, so the string the server reads
     * is not the string that was written.
     */
    private val RETYPED_CANDIDATES = listOf("api-version", "version")

    fun parse(kind: DescriptorKind, yamlBytes: ByteArray): PluginDescriptor {
        val errors = mutableListOf<String>()
        val root = try {
            val options = LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 64
                codePointLimit = 4 * 1024 * 1024
            }
            Yaml(SafeConstructor(options), org.yaml.snakeyaml.representer.Representer(org.yaml.snakeyaml.DumperOptions()))
                .load<Any?>(yamlBytes.inputStream())
        } catch (e: Exception) {
            errors += "${kind.entryName} is not valid YAML: ${e.message?.lineSequence()?.first() ?: e::class.simpleName}"
            null
        }

        @Suppress("UNCHECKED_CAST")
        val map = (root as? Map<Any?, Any?>)?.mapKeys { it.key.toString() } ?: run {
            if (root != null) errors += "${kind.entryName} does not contain a YAML mapping at the top level"
            emptyMap()
        }

        val known = if (kind == DescriptorKind.LEGACY) LEGACY_KEYS else PAPER_KEYS
        val dependencies = when (kind) {
            DescriptorKind.LEGACY -> legacyDependencies(map)
            DescriptorKind.PAPER -> paperDependencies(map, errors)
        }

        return PluginDescriptor(
            kind = kind,
            name = map.str("name"),
            version = map.scalar("version"),
            main = map.str("main"),
            apiVersion = map.scalar("api-version"),
            authors = map.strList("authors") + listOfNotNull(map.str("author")),
            dependencies = dependencies,
            loadBefore = map.strList("loadbefore"),
            provides = map.strList("provides"),
            libraries = map.strList("libraries"),
            commands = legacyCommands(map),
            permissions = map.keysOf("permissions"),
            foliaSupported = map["folia-supported"] as? Boolean,
            bootstrapper = map.str("bootstrapper"),
            loader = map.str("loader"),
            hasOpenClassloader = map["has-open-classloader"] as? Boolean,
            load = map.str("load"),
            unknownKeys = map.keys.filterNot { it in known }.sorted(),
            retypedScalars = RETYPED_CANDIDATES
                .mapNotNull { key ->
                    val value = map[key] ?: return@mapNotNull null
                    if (value is String || value is Map<*, *> || value is List<*>) return@mapNotNull null
                    val loaded = value.toString()
                    // Unquoted 1.21 loads as a float that prints back as "1.21", so nothing was
                    // lost and there is nothing to report. Only a value that no longer reads as it
                    // was written is a defect, and the written form only exists in the file text.
                    val written = writtenValueOf(key, yamlBytes) ?: return@mapNotNull null
                    if (written == loaded) return@mapNotNull null
                    key to RetypedScalar(written = written, loaded = loaded)
                }
                .toMap(),
            parseErrors = errors,
        )
    }

    /**
     * The value as it appears in the file, for a key at the top level of the mapping. The loaded
     * value has already been through YAML's resolver, so the text the author typed is the only way
     * to tell a value that survived from one that was rewritten. Returns null when the value is not
     * a plain one-line scalar, which keeps the comparison from claiming a change it cannot prove.
     */
    private fun writtenValueOf(key: String, yamlBytes: ByteArray): String? {
        val line = yamlBytes.toString(Charsets.UTF_8)
            .lineSequence()
            .firstOrNull { it.startsWith("$key:") }
            ?: return null
        val value = line.substringAfter(':').replace(TRAILING_COMMENT, "").trim()
        return value.ifEmpty { null }
    }

    /** A `#` that starts a comment has to be preceded by whitespace to end a plain scalar. */
    private val TRAILING_COMMENT = Regex("\\s+#.*$")

    private fun legacyDependencies(map: Map<String, Any?>): List<DependencyRef> =
        map.strList("depend").map { DependencyRef(it, required = true) } +
            map.strList("softdepend").map { DependencyRef(it, required = false) }

    /**
     * paper-plugin.yml nests dependencies under `bootstrap:` and `server:`, keyed by plugin name,
     * with per-entry `load` / `required` / `join-classpath`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun paperDependencies(map: Map<String, Any?>, errors: MutableList<String>): List<DependencyRef> {
        val block = map["dependencies"] as? Map<Any?, Any?> ?: return emptyList()
        val out = mutableListOf<DependencyRef>()
        for ((phase, entries) in block) {
            val byName = entries as? Map<Any?, Any?> ?: run {
                errors += "dependencies.$phase should be a mapping of plugin name to options"
                continue
            }
            for ((depName, opts) in byName) {
                val options = opts as? Map<Any?, Any?> ?: emptyMap<Any?, Any?>()
                out += DependencyRef(
                    name = depName.toString(),
                    // Paper defaults `required` to true when the key is omitted.
                    required = options["required"] as? Boolean ?: true,
                    load = options["load"]?.toString(),
                    joinClasspath = options["join-classpath"] as? Boolean,
                )
            }
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun legacyCommands(map: Map<String, Any?>): Map<String, List<String>> {
        val block = map["commands"] as? Map<Any?, Any?> ?: return emptyMap()
        return block.entries.associate { (name, body) ->
            val opts = body as? Map<Any?, Any?>
            val aliases = when (val a = opts?.get("aliases")) {
                is List<*> -> a.mapNotNull { it?.toString() }
                is String -> listOf(a)
                else -> emptyList()
            }
            name.toString() to aliases
        }
    }

    private fun Map<String, Any?>.str(key: String): String? = (this[key] as? String)?.trim()?.ifEmpty { null }

    /** Version-ish fields may parse as numbers (`version: 1.0`), so accept any scalar. */
    private fun Map<String, Any?>.scalar(key: String): String? = when (val v = this[key]) {
        null -> null
        is String -> v.trim().ifEmpty { null }
        is Map<*, *>, is List<*> -> null
        else -> v.toString()
    }

    private fun Map<String, Any?>.strList(key: String): List<String> = when (val v = this[key]) {
        is List<*> -> v.mapNotNull { it?.toString() }
        is String -> listOf(v)
        else -> emptyList()
    }

    private fun Map<String, Any?>.keysOf(key: String): List<String> =
        (this[key] as? Map<*, *>)?.keys?.mapNotNull { it?.toString() } ?: emptyList()
}
