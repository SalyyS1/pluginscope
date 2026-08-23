package com.salyvn.pluginscope.compat

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/**
 * Fetches a `paper-api` jar so `compat` can be pointed at a Minecraft version instead of a file.
 *
 * Paper publishes two version shapes and both are still in the repository: the historical
 * `1.21.4-R0.1-SNAPSHOT` and the current `26.2.build.116-stable`. Both are handled, including the
 * extra snapshot-metadata hop the first one needs.
 *
 * Downloads are cached and checked against the repository's own `.sha1`.
 */
object PaperApiResolver {

    private const val BASE = "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api"

    data class Resolved(val requested: String, val version: String, val jar: Path, val fromCache: Boolean)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    fun defaultCacheDir(): Path = Path.of(System.getProperty("user.home"), ".pluginscope", "api-cache")

    /**
     * @param request `latest`, a full artifact version, or a Minecraft version such as `1.21.4`
     *   or `26.2`, in which case the newest matching artifact is used.
     */
    fun resolve(request: String, cacheDir: Path = defaultCacheDir(), log: (String) -> Unit = {}): Resolved {
        val versions = listVersions()
        val version = select(request, versions)
            ?: throw IOException(
                "No paper-api artifact matches '$request'. Known examples: ${versions.takeLast(3).joinToString(", ")}"
            )

        val (fileVersion, url) = downloadTarget(version)
        val cached = cacheDir.resolve("paper-api-$fileVersion.jar")
        if (Files.isRegularFile(cached) && Files.size(cached) > 0) {
            return Resolved(request, version, cached, fromCache = true)
        }

        Files.createDirectories(cacheDir)
        log("Downloading paper-api $version")
        val bytes = getBytes(url)
        val expected = runCatching { getString("$url.sha1").trim().substringBefore(' ') }.getOrNull()
        if (expected != null) {
            val actual = sha1(bytes)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw IOException("Checksum mismatch for $url (expected $expected, got $actual)")
            }
        }
        val temp = Files.createTempFile(cacheDir, "paper-api-", ".part")
        Files.write(temp, bytes)
        Files.move(temp, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return Resolved(request, version, cached, fromCache = false)
    }

    internal fun select(request: String, versions: List<String>): String? {
        if (versions.isEmpty()) return null
        if (request.equals("latest", ignoreCase = true)) return versions.last()
        if (request in versions) return request

        val candidates = versions.filter { version ->
            version.startsWith("$request-R") || version.startsWith("$request.build.") || version == request
        }
        if (candidates.isEmpty()) return null
        // `.build.N` sorts wrong lexically (build.9 after build.116), so compare the number.
        return candidates.maxByOrNull { version ->
            version.substringAfter(".build.", "").takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    private fun listVersions(): List<String> {
        val metadata = getString("$BASE/maven-metadata.xml")
        return VERSION_TAG.findAll(metadata).map { it.groupValues[1] }.toList()
    }

    /** Returns the version string used in the file name plus the URL to download. */
    private fun downloadTarget(version: String): Pair<String, String> {
        if (!version.endsWith("-SNAPSHOT")) {
            return version to "$BASE/$version/paper-api-$version.jar"
        }
        val metadata = getString("$BASE/$version/maven-metadata.xml")
        val fileVersion = plainJarSnapshotValue(metadata)
            ?: throw IOException("Snapshot metadata for $version lists no plain jar")
        return fileVersion to "$BASE/$version/paper-api-$fileVersion.jar"
    }

    /**
     * Picks the `<snapshotVersion>` block that has `<extension>jar</extension>` and no
     * `<classifier>`, i.e. the actual API jar rather than sources or javadoc.
     */
    internal fun plainJarSnapshotValue(metadata: String): String? = SNAPSHOT_BLOCK.findAll(metadata)
        .map { it.groupValues[1] }
        .filterNot { it.contains("<classifier>") }
        .filter { it.contains("<extension>jar</extension>") }
        .mapNotNull { VALUE_TAG.find(it)?.groupValues?.get(1) }
        .firstOrNull()

    private fun getString(url: String): String {
        val response = http.send(request(url), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IOException("GET $url returned HTTP ${response.statusCode()}")
        return response.body()
    }

    private fun getBytes(url: String): ByteArray {
        val response = http.send(request(url), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200) throw IOException("GET $url returned HTTP ${response.statusCode()}")
        return response.body()
    }

    private fun request(url: String) = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMinutes(2))
        .header("User-Agent", "pluginscope")
        .GET()
        .build()

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private val VERSION_TAG = Regex("<version>([^<]+)</version>")
    private val SNAPSHOT_BLOCK = Regex("<snapshotVersion>(.*?)</snapshotVersion>", RegexOption.DOT_MATCHES_ALL)
    private val VALUE_TAG = Regex("<value>([^<]+)</value>")
}
