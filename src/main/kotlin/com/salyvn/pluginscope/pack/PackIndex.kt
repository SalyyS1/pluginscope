package com.salyvn.pluginscope.pack

/**
 * A namespaced id. Both halves have a restricted character set: numbers, lowercase letters,
 * underscore, hyphen and dot, plus forward slash in the path only. Uppercase was disallowed in
 * 16w32a, which is why a pack authored on a case-insensitive filesystem loads on the author's
 * machine and 404s on a Linux server.
 *
 * Source: minecraft.wiki "Resource location", Java Edition character table.
 */
internal data class ResourceLocation(val namespace: String, val path: String) {

    override fun toString(): String = "$namespace:$path"

    val isVanillaNamespace: Boolean get() = namespace == DEFAULT_NAMESPACE

    companion object {
        const val DEFAULT_NAMESPACE = "minecraft"

        /**
         * Null when the string cannot be a resource location at all: more than one colon, or an
         * empty half. A bare path takes the `minecraft` namespace, as the game does.
         */
        fun parse(raw: String): ResourceLocation? {
            if (raw.isEmpty()) return null
            val colons = raw.count { it == ':' }
            if (colons > 1) return null
            if (colons == 0) return ResourceLocation(DEFAULT_NAMESPACE, raw)
            val namespace = raw.substringBefore(':')
            val path = raw.substringAfter(':')
            if (namespace.isEmpty() || path.isEmpty()) return null
            if ('/' in namespace) return null
            return ResourceLocation(namespace, path)
        }

        private const val NAMESPACE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789._-"

        fun illegalNamespaceChars(namespace: String): Set<Char> =
            namespace.filterNot { it in NAMESPACE_CHARS }.toSet()

        fun illegalPathChars(path: String): Set<Char> =
            path.filterNot { it in NAMESPACE_CHARS || it == '/' }.toSet()
    }
}

/** What the pack is for, decided by which top-level tree it carries. */
internal enum class PackKind(val label: String) {
    RESOURCE("resource pack"),
    DATA("data pack"),
    BOTH("resource and data pack"),
    UNKNOWN("unrecognised"),
}

/**
 * The inventory of one pack: which files exist, which namespaces it owns, and where the pieces of a
 * resource location land on disk.
 *
 * Only the root `assets` tree is indexed for reference resolution. Files inside an overlay directory
 * form a parallel tree that only applies on some client formats, so resolving one against the other
 * would produce answers that are wrong for every client outside that range.
 */
internal class PackIndex(val source: PackSource) {

    val assetNamespaces: Set<String> = namespacesUnder("assets")

    val dataNamespaces: Set<String> = namespacesUnder("data")

    val kind: PackKind = when {
        assetNamespaces.isNotEmpty() && dataNamespaces.isNotEmpty() -> PackKind.BOTH
        assetNamespaces.isNotEmpty() -> PackKind.RESOURCE
        dataNamespaces.isNotEmpty() -> PackKind.DATA
        else -> PackKind.UNKNOWN
    }

    /** Where a resource location lives, given the folder under the namespace and a file suffix. */
    fun assetPath(location: ResourceLocation, folder: String, suffix: String): String =
        "assets/${location.namespace}/$folder/${location.path}$suffix"

    fun hasAsset(location: ResourceLocation, folder: String, suffix: String): Boolean =
        source.contains(assetPath(location, folder, suffix))

    /**
     * Every `assets/<ns>/<folder>/<path><suffix>` file keyed by the resource location it answers to,
     * so a check can iterate the pack's own models or item definitions.
     */
    fun assetEntries(folder: String, suffix: String): Map<ResourceLocation, String> {
        val result = LinkedHashMap<ResourceLocation, String>()
        for (path in source.paths.sorted()) {
            val parts = path.split('/')
            if (parts.size < 4 || parts[0] != "assets" || parts[2] != folder) continue
            if (!path.endsWith(suffix)) continue
            val id = parts.drop(3).joinToString("/").removeSuffix(suffix)
            if (id.isEmpty()) continue
            result[ResourceLocation(parts[1], id)] = path
        }
        return result
    }

    private fun namespacesUnder(tree: String): Set<String> = source.paths.mapNotNullTo(LinkedHashSet()) { path ->
        val parts = path.split('/')
        if (parts.size >= 3 && parts[0] == tree) parts[1] else null
    }

    companion object {
        /**
         * Splits a pack path into the tree it belongs to, its namespace and the rest, for paths in
         * the root as well as inside an overlay directory. Null when the path is not namespaced
         * content, such as `pack.mcmeta` or `pack.png`.
         */
        fun namespacedPart(path: String): Triple<String, String, String>? {
            val parts = path.split('/')
            val tree = parts.indexOfFirst { it == "assets" || it == "data" }
            if (tree < 0 || parts.size < tree + 3) return null
            return Triple(parts[tree], parts[tree + 1], parts.drop(tree + 2).joinToString("/"))
        }
    }
}
