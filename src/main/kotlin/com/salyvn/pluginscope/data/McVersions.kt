package com.salyvn.pluginscope.data

import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Facts about Minecraft/Paper version strings.
 *
 * Two numbering schemes are in play and both are live in the wild:
 *
 * - the historical `1.x.y` Minecraft versions, whose api-version values look like `1.20` or `1.21.4`
 * - the current `<year>.<release>` scheme, e.g. `26.2`
 *
 * The second one is not a guess: a paper-api jar carries `apiVersioning.json` at its root, and in
 * `paper-api 26.2.build.116-stable` that file reads
 * `{"version":"26.2.build.116-stable","currentApiVersion":"26.2"}`.
 *
 * Rather than ship a list of accepted api-version strings that goes stale with every release, the
 * offline check here is deliberately narrow: it only reports values that cannot be a Minecraft
 * version at all. To check against one specific server, point the tool at that server's paper-api
 * jar and the current value is read out of it.
 */
object McVersions {

    enum class ApiVersionShape {
        /** `1.13` … `1.21.4`: the pre-26 scheme. */
        LEGACY_NUMBERING,

        /** `26.2` and later: `<year>.<release>`. */
        CURRENT_NUMBERING,

        /** The literal string `none`, which opts into legacy handling explicitly. */
        EXPLICIT_NONE,

        /** Not a Minecraft version string in either scheme. */
        MALFORMED,
    }

    private val LEGACY = Regex("^1\\.(\\d{1,2})(\\.\\d{1,2})?$")
    private val CURRENT = Regex("^(2[5-9]|[3-9]\\d)\\.\\d{1,2}$")

    /**
     * Paper parses api-version as `major.minor[.patch]`, or the literal `none`, and throws
     * IllegalArgumentException on anything else (`CraftMagicNumbers`/`ApiVersion` in paper-server).
     * `none` is therefore a valid declaration and must not be reported as a typo.
     */
    fun shapeOf(apiVersion: String): ApiVersionShape = when {
        apiVersion.equals("none", ignoreCase = true) -> ApiVersionShape.EXPLICIT_NONE
        LEGACY.matches(apiVersion) -> ApiVersionShape.LEGACY_NUMBERING
        CURRENT.matches(apiVersion) -> ApiVersionShape.CURRENT_NUMBERING
        else -> ApiVersionShape.MALFORMED
    }

    /**
     * The api-version the given paper-api jar corresponds to, read from `apiVersioning.json`.
     * Returns null for older jars that do not carry the file.
     */
    fun currentApiVersionOf(paperApiJar: Path): String? = runCatching {
        ZipFile(paperApiJar.toFile()).use { zip ->
            val entry = zip.getEntry("apiVersioning.json") ?: return null
            val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
            Regex("\"currentApiVersion\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        }
    }.getOrNull()

    /**
     * The Java release a server of the given Minecraft version runs on, which is the ceiling for
     * the class file versions a plugin may ship.
     *
     * Values come from `javaVersion.majorVersion` in Mojang's per-version manifest, reachable from
     * `https://launchermeta.mojang.com/mc/game/version_manifest_v2.json`. Verified points:
     * 1.16.5 -> 8, 1.17.1 -> 16, 1.20.4 -> 17, 1.20.5 -> 21, 1.21.4 -> 21, 26.1 -> 25, 26.2 -> 25.
     * Only the steps are encoded, because that is all a class-file check needs.
     */
    fun minimumJavaFor(mcVersion: String): Int? {
        val shape = shapeOf(mcVersion)
        if (shape == ApiVersionShape.CURRENT_NUMBERING) return 25
        if (shape != ApiVersionShape.LEGACY_NUMBERING) return null
        val parts = mcVersion.removePrefix("1.").split('.')
        val minor = parts[0].toIntOrNull() ?: return null
        val patch = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return when {
            minor >= 21 -> 21
            minor == 20 && patch >= 5 -> 21
            minor >= 18 -> 17
            minor == 17 -> 16
            else -> 8
        }
    }
}
