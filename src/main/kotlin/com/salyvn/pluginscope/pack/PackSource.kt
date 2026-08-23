package com.salyvn.pluginscope.pack

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/** One file inside a pack, addressed by its pack-relative path with forward slashes. */
internal data class PackEntry(val path: String, val sizeBytes: Long)

/**
 * A pack ships either as an unpacked directory or as a zip, and the same mistakes happen in both.
 * Every check works off pack-relative paths so it cannot tell the two apart, which is also what
 * makes "zip and directory produce the same findings" a testable property rather than a hope.
 */
internal sealed interface PackSource : AutoCloseable {

    /** Absolute path of the pack, for the report subject. */
    val label: String

    /** `directory` or `zip`, printed in the report. */
    val kind: String

    val entries: List<PackEntry>

    /**
     * Every directory prefix that exists, whether or not the archive carries an explicit entry for
     * it. Zips written by different tools disagree about that, and overlay checks need the answer.
     */
    val directories: Set<String>

    /** Null when the path is absent or larger than [MAX_TEXT_BYTES]. */
    fun read(path: String): ByteArray?

    /** First [limit] bytes, or fewer at end of file. Null when the path is absent. */
    fun readHead(path: String, limit: Int): ByteArray?

    val paths: Set<String>

    fun contains(path: String): Boolean = paths.contains(path)

    companion object {
        /** Text files are read whole, so a bomb in an untrusted pack must not become an OOM. */
        const val MAX_TEXT_BYTES = 8 * 1024 * 1024

        fun open(path: Path): PackSource = when {
            path.isDirectory() -> DirectoryPackSource(path)
            path.isRegularFile() -> ZipPackSource(path)
            else -> throw IOException("$path is neither a directory nor a file")
        }

        /** Zip entry names are not normalised by the format: separators and prefixes both vary. */
        internal fun normalise(raw: String): String = raw.replace('\\', '/')
            .removePrefix("./")
            .removePrefix("/")
            .trimEnd('/')

        internal fun parentsOf(path: String): List<String> {
            val parts = path.split('/')
            if (parts.size < 2) return emptyList()
            return (1 until parts.size).map { parts.take(it).joinToString("/") }
        }
    }
}

private class DirectoryPackSource(private val root: Path) : PackSource {

    override val label: String = root.toAbsolutePath().normalize().toString()
    override val kind: String = "directory"

    override val entries: List<PackEntry> = Files.walk(root).use { stream ->
        stream.filter { it.isRegularFile() }
            .map { file ->
                val relative = PackSource.normalise(root.relativize(file).toString())
                PackEntry(relative, file.toFile().length())
            }
            .sorted(compareBy { it.path })
            .toList()
    }

    override val paths: Set<String> = entries.mapTo(HashSet()) { it.path }

    override val directories: Set<String> = entries.flatMapTo(HashSet()) { PackSource.parentsOf(it.path) }

    override fun read(path: String): ByteArray? {
        val file = resolve(path) ?: return null
        if (Files.size(file) > PackSource.MAX_TEXT_BYTES) return null
        return Files.readAllBytes(file)
    }

    override fun readHead(path: String, limit: Int): ByteArray? {
        val file = resolve(path) ?: return null
        return Files.newInputStream(file).use { it.readNBytes(limit) }
    }

    /**
     * A path is only accepted when it was produced by the walk above, so a caller cannot reach
     * outside the pack root by assembling `../` into a reference.
     */
    private fun resolve(path: String): Path? {
        if (path !in paths) return null
        return root.resolve(path)
    }

    override fun close() = Unit
}

private class ZipPackSource(path: Path) : PackSource {

    private val zip = ZipFile(path.toFile())

    override val label: String = path.toAbsolutePath().normalize().toString()
    override val kind: String = "zip"

    private val declaredDirectories = mutableSetOf<String>()

    override val entries: List<PackEntry> = zip.entries().asSequence()
        .mapNotNull { entry ->
            val name = PackSource.normalise(entry.name)
            if (name.isEmpty()) return@mapNotNull null
            if (entry.isDirectory) {
                declaredDirectories += name
                null
            } else {
                PackEntry(name, entry.size)
            }
        }
        .sortedBy { it.path }
        .toList()

    override val paths: Set<String> = entries.mapTo(HashSet()) { it.path }

    override val directories: Set<String> =
        entries.flatMapTo(HashSet(declaredDirectories)) { PackSource.parentsOf(it.path) }

    override fun read(path: String): ByteArray? {
        val entry = entryFor(path) ?: return null
        if (entry.size > PackSource.MAX_TEXT_BYTES) return null
        return zip.getInputStream(entry).use { it.readNBytes(PackSource.MAX_TEXT_BYTES) }
    }

    override fun readHead(path: String, limit: Int): ByteArray? {
        val entry = entryFor(path) ?: return null
        return zip.getInputStream(entry).use { it.readNBytes(limit) }
    }

    /**
     * Looked up by the normalised name first, because that is what the checks carry, then by the
     * raw name for archives that use backslashes or a `./` prefix.
     */
    private fun entryFor(path: String) = zip.getEntry(path)
        ?: zip.entries().asSequence().firstOrNull { !it.isDirectory && PackSource.normalise(it.name) == path }

    override fun close() = zip.close()
}
