package com.salyvn.pluginscope.pack

import kotlinx.serialization.json.JsonArray
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant

/** Resource-pack and data-pack formats of one Minecraft version. */
internal data class VersionFormats(
    val id: String,
    val resourcePack: PackFormat,
    val dataPack: PackFormat,
)

/**
 * Maps a Minecraft version to the pack formats its client and server accept.
 *
 * The table is fetched at runtime and cached, never vendored: the upstream summary is generated from
 * the game's own version manifest by misode/mcmeta, a repository that carries no licence file, so
 * copying its content into this repository would not be ours to redistribute.
 *
 * The summary was verified to carry `resource_pack_version`, `resource_pack_version_minor`,
 * `data_pack_version` and `data_pack_version_minor` per version, and for 26.2 it reads resource pack
 * 88.0 and data pack 107.1.
 */
internal object PackFormatCatalog {

    const val SOURCE_URL =
        "https://raw.githubusercontent.com/misode/mcmeta/refs/heads/summary/versions/data.min.json"

    private const val CACHE_FILE = "mcmeta-versions.json"

    /**
     * The formats 1.21.9 shipped with. They are the boundary the `pack.mcmeta` schema itself refers
     * to when it says `supported_formats` must not appear next to a `min_format` for 1.21.9 or
     * later, so the two numbers are needed for that check to work with no network at all.
     */
    const val RESOURCE_PACK_FORMAT_1_21_9 = 69
    const val DATA_PACK_FORMAT_1_21_9 = 88

    /** The release that removed `overrides` from the model format. */
    const val RESOURCE_PACK_FORMAT_1_21_4 = 46

    private const val CACHE_MAX_AGE_DAYS = 7L

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    fun defaultCacheDir(): Path = Path.of(System.getProperty("user.home"), ".pluginscope", "cache")

    /**
     * @param allowNetwork when false only an existing cache file is consulted, so `--no-fetch` runs
     *   are guaranteed not to touch the network.
     * @throws IOException when the table cannot be obtained or the version is not in it.
     */
    fun lookup(
        version: String,
        cacheDir: Path = defaultCacheDir(),
        allowNetwork: Boolean = true,
        log: (String) -> Unit = {},
    ): VersionFormats {
        val table = load(cacheDir, allowNetwork, log)
        return table[version.lowercase()]
            ?: throw IOException("'$version' is not a version in the pack format table")
    }

    private fun load(cacheDir: Path, allowNetwork: Boolean, log: (String) -> Unit): Map<String, VersionFormats> {
        val cached = cacheDir.resolve(CACHE_FILE)
        val fresh = Files.isRegularFile(cached) &&
            Files.size(cached) > 0 &&
            Files.getLastModifiedTime(cached).toInstant()
                .isAfter(Instant.now().minus(Duration.ofDays(CACHE_MAX_AGE_DAYS)))

        if (fresh) return parse(Files.readAllBytes(cached))

        if (!allowNetwork) {
            if (Files.isRegularFile(cached) && Files.size(cached) > 0) {
                log("Using the cached pack format table; it is older than $CACHE_MAX_AGE_DAYS days.")
                return parse(Files.readAllBytes(cached))
            }
            throw IOException("No cached pack format table at $cached and fetching is disabled")
        }

        return try {
            log("Fetching the pack format table")
            val bytes = fetch()
            val table = parse(bytes)
            store(cached, bytes)
            table
        } catch (e: IOException) {
            // A stale table still answers correctly for every version it already knows about, which
            // is every released version older than the cache. Better that than no check at all.
            if (Files.isRegularFile(cached) && Files.size(cached) > 0) {
                log("Fetch failed (${e.message}); falling back to the cached table.")
                parse(Files.readAllBytes(cached))
            } else {
                throw e
            }
        }
    }

    private fun fetch(): ByteArray {
        val request = HttpRequest.newBuilder(URI.create(SOURCE_URL))
            .timeout(Duration.ofMinutes(1))
            .header("User-Agent", "pluginscope")
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while fetching $SOURCE_URL", e)
        }
        if (response.statusCode() != 200) {
            throw IOException("GET $SOURCE_URL returned HTTP ${response.statusCode()}")
        }
        return response.body()
    }

    private fun store(target: Path, bytes: ByteArray) {
        target.parent?.let { Files.createDirectories(it) }
        val temp = Files.createTempFile(target.parent, "mcmeta-versions-", ".part")
        Files.write(temp, bytes)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun parse(bytes: ByteArray): Map<String, VersionFormats> {
        val root = parseJson(bytes).getOrElse { throw IOException("Pack format table is not valid JSON", it) }
        val array = root as? JsonArray ?: throw IOException("Pack format table is not a JSON list")
        val table = HashMap<String, VersionFormats>(array.size * 2)
        for (element in array) {
            val entry = element.asObjectOrNull() ?: continue
            val id = entry.string("id") ?: continue
            val resource = entry["resource_pack_version"]?.asIntOrNull() ?: continue
            val data = entry["data_pack_version"]?.asIntOrNull() ?: continue
            table[id.lowercase()] = VersionFormats(
                id = id,
                resourcePack = PackFormat(resource, entry["resource_pack_version_minor"]?.asIntOrNull() ?: 0),
                dataPack = PackFormat(data, entry["data_pack_version_minor"]?.asIntOrNull() ?: 0),
            )
        }
        if (table.isEmpty()) throw IOException("Pack format table carried no usable version entries")
        return table
    }
}
