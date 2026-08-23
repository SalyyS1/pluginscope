package com.salyvn.pluginscope.pack

import com.salyvn.pluginscope.core.FindingSink
import kotlinx.serialization.json.JsonObject

/**
 * Reads the pack's JSON assets and keeps a note of every file it could not parse.
 *
 * A file that is dropped silently takes its references with it, and the run would then report a clean
 * pack while having checked less of it than the author thinks. Collecting the failures here turns
 * that gap into a finding. A path the pack simply does not contain is not a failure and produces no
 * note, because whether that absence matters is for the caller to decide.
 */
internal class JsonAssets(private val source: PackSource) {

    private val unreadable = mutableListOf<String>()

    fun read(path: String): JsonObject? {
        if (!source.contains(path)) return null
        val bytes = source.read(path)
        if (bytes == null) {
            unreadable += "$path: larger than ${PackSource.MAX_TEXT_BYTES / (1024 * 1024)} MiB"
            return null
        }
        val parsed = parseJson(bytes)
        val obj = parsed.getOrNull()?.asObjectOrNull()
        if (obj == null) {
            val reason = parsed.exceptionOrNull()?.message?.lineSequence()?.firstOrNull()?.trim()
                ?: "top level is not a JSON object"
            unreadable += "$path: $reason"
            return null
        }
        return obj
    }

    fun reportUnreadable(sink: FindingSink) {
        if (unreadable.isEmpty()) return
        sink.add(
            PackRules.ASSET_JSON_UNREADABLE,
            location = source.label,
            detail = "${unreadable.size} JSON asset(s) could not be parsed, so anything they " +
                "reference went unchecked.",
            evidence = unreadable.sorted().take(8),
        )
    }
}
