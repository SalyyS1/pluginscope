package com.salyvn.pluginscope.pack

import com.salyvn.pluginscope.core.FindingSink
import com.salyvn.pluginscope.core.Rule

/**
 * Checks that need only the entry list and a few bytes of each file: whether paths are addressable at
 * all, whether the archive carries anything that is not pack content, and whether the PNGs are PNGs
 * of a shape the client can use.
 */
internal object PackHygieneChecks {

    private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    /** Signature, length, chunk type and both dimensions all live in the first 24 bytes. */
    private const val PNG_HEADER_BYTES = 24

    private val JUNK_SUFFIXES = listOf(
        ".ds_store", "thumbs.db", "desktop.ini", ".psd", ".xcf", ".kra", ".aseprite", ".ase",
        ".blend", ".bbmodel", ".bak", ".orig", "~",
    )

    private val JUNK_PREFIXES = listOf("__macosx/", ".git/", ".idea/", ".vscode/")

    private val JUNK_NAMES = listOf(".gitignore", ".gitattributes")

    fun check(index: PackIndex, assets: JsonAssets, sink: FindingSink) {
        checkPaths(index, sink)
        checkJunk(index, sink)
        checkTextures(index, assets, sink)
    }

    /**
     * A path outside the allowed character set resolves on a case-insensitive filesystem and fails on
     * the Linux host the pack is usually served from, which is why this is worth reporting even though
     * the author's own client loads the pack.
     */
    private fun checkPaths(index: PackIndex, sink: FindingSink) {
        val offenders = index.source.entries.mapNotNull { entry ->
            val (_, namespace, rest) = PackIndex.namespacedPart(entry.path) ?: return@mapNotNull null
            val illegal = ResourceLocation.illegalNamespaceChars(namespace) + ResourceLocation.illegalPathChars(rest)
            if (illegal.isEmpty()) null else "${entry.path}: ${illegal.sorted().joinToString(" ") { describe(it) }}"
        }
        if (offenders.isEmpty()) return
        sink.add(
            PackRules.ILLEGAL_RESOURCE_PATH,
            location = PACK_ROOT_LOCATION,
            detail = "${offenders.size} path(s) contain characters outside [a-z0-9._-] and /.",
            evidence = offenders.sorted().take(8),
        )
    }

    private fun describe(char: Char): String = when {
        char == ' ' -> "space"
        char == '\\' -> "backslash"
        char.isWhitespace() -> "whitespace"
        else -> "\"$char\""
    }

    private fun checkJunk(index: PackIndex, sink: FindingSink) {
        val junk = index.source.entries.filter { entry ->
            val lower = entry.path.lowercase()
            val name = lower.substringAfterLast('/')
            JUNK_PREFIXES.any { lower.startsWith(it) || "/$it" in lower } ||
                JUNK_NAMES.contains(name) ||
                JUNK_SUFFIXES.any { name.endsWith(it) }
        }
        if (junk.isEmpty()) return
        val bytes = junk.sumOf { it.sizeBytes }
        sink.add(
            PackRules.JUNK_ENTRY,
            location = PACK_ROOT_LOCATION,
            detail = "${junk.size} entr" + (if (junk.size == 1) "y is" else "ies are") +
                " not pack content, ${bytes / 1024} KiB in total, which every player downloads.",
            evidence = junk.map { it.path }.sorted().take(8),
        )
    }

    private fun checkTextures(index: PackIndex, assets: JsonAssets, sink: FindingSink) {
        val notPng = mutableListOf<String>()
        val notPowerOfTwo = mutableListOf<String>()
        val frameMismatch = mutableListOf<String>()

        for (entry in index.source.entries) {
            if (!entry.path.endsWith(".png")) continue
            val head = index.source.readHead(entry.path, PNG_HEADER_BYTES) ?: continue
            if (!head.take(PNG_SIGNATURE.size).toByteArray().contentEquals(PNG_SIGNATURE)) {
                notPng += "${entry.path}: starts with ${signatureOf(head)}"
                continue
            }
            val size = readSize(head) ?: continue

            // `animation` makes the image a filmstrip, so its height is frames times the frame height
            // and is not expected to be a power of two on its own.
            val animation = assets.read("${entry.path}.mcmeta")?.child("animation")
            val animated = animation != null
            if (isAtlasTexture(entry.path)) {
                val bad = buildList {
                    if (!isPowerOfTwo(size.first)) add("width ${size.first}")
                    if (!animated && !isPowerOfTwo(size.second)) add("height ${size.second}")
                }
                if (bad.isNotEmpty()) notPowerOfTwo += "${entry.path}: ${bad.joinToString(", ")}"
            }

            // With an explicit width or height the frame is no longer square and the tool has no way
            // to know the intended frame count, so only the default case is checked.
            if (animation != null && animation["width"] == null && animation["height"] == null) {
                val (width, height) = size
                if (width > 0 && height % width != 0) {
                    frameMismatch += "${entry.path}: ${width}x$height is ${height % width} row(s) past a whole frame"
                }
            }
        }

        report(PackRules.NOT_A_PNG, index, notPng, "file(s) named .png are not PNG files.", sink)
        report(
            PackRules.TEXTURE_NOT_POWER_OF_TWO, index, notPowerOfTwo,
            "atlas texture(s) have a side that is not a power of two.", sink,
        )
        report(
            PackRules.ANIMATION_FRAME_MISMATCH, index, frameMismatch,
            "animated texture(s) are not a whole number of frames tall.", sink,
        )
    }

    private fun report(
        rule: Rule,
        index: PackIndex,
        offenders: List<String>,
        summary: String,
        sink: FindingSink,
    ) {
        if (offenders.isEmpty()) return
        sink.add(
            rule,
            location = PACK_ROOT_LOCATION,
            detail = "${offenders.size} $summary",
            evidence = offenders.sorted().take(8),
        )
    }

    /**
     * Only textures stitched into an atlas are mipmapped, so only those are affected. The blocks atlas
     * is built from the block and item texture directories, which is why the check is scoped to them
     * rather than to every PNG in the pack.
     */
    private fun isAtlasTexture(path: String): Boolean {
        val (_, _, rest) = PackIndex.namespacedPart(path) ?: return false
        return rest.startsWith("textures/block/") || rest.startsWith("textures/item/")
    }

    private fun isPowerOfTwo(value: Int): Boolean = value > 0 && (value and (value - 1)) == 0

    /** Width and height from the IHDR chunk, or null when the header is truncated or not IHDR. */
    private fun readSize(head: ByteArray): Pair<Int, Int>? {
        if (head.size < PNG_HEADER_BYTES) return null
        val type = String(head, 12, 4, Charsets.US_ASCII)
        if (type != "IHDR") return null
        return intAt(head, 16) to intAt(head, 20)
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int = (0 until 4).fold(0) { value, index ->
        (value shl 8) or (bytes[offset + index].toInt() and 0xFF)
    }

    private fun signatureOf(head: ByteArray): String =
        head.take(4).joinToString(" ") { "%02x".format(it) }.ifEmpty { "no bytes" }

    /**
     * Findings about the pack as a whole name the root rather than the file system path. The path is
     * already on the subject line, and repeating it would make a report depend on where the pack
     * happened to sit when it was checked, which SARIF fingerprints then inherit.
     */
    private const val PACK_ROOT_LOCATION = "(pack root)"
}
