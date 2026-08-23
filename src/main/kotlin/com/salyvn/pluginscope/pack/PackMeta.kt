package com.salyvn.pluginscope.pack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Reads [bytes] as JSON, dropping a leading UTF-8 byte order mark first so that the mark alone is
 * not reported as a syntax error.
 *
 * Strict parsing on purpose: accepting trailing commas or comments here would hide exactly the
 * defect this tool exists to find.
 */
internal fun parseJson(bytes: ByteArray): Result<JsonElement> = runCatching {
    val text = bytes.decodeToString().removePrefix(BYTE_ORDER_MARK)
    Json.parseToJsonElement(text)
}

private val BYTE_ORDER_MARK = Char(0xFEFF).toString()

internal fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement.asIntOrNull(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

internal fun JsonObject.child(key: String): JsonObject? = this[key]?.asObjectOrNull()

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.string(key: String): String? = this[key]?.asStringOrNull()

/** Reads a JSON object out of a pack, or null when the file is absent or is not a JSON object. */
internal fun PackSource.readObject(path: String): JsonObject? =
    read(path)?.let { parseJson(it).getOrNull() }?.asObjectOrNull()

/**
 * A pack format number. Minor formats arrived with 1.21.9; everything before that had a major only,
 * which is why [minor] defaults to zero.
 *
 * Shape verified against SpyglassMC/vanilla-mcdoc java/pack.mcdoc:69,
 * `type PackFormat = (int | [int] @ 1 | [int, int @ 0..])`.
 */
internal data class PackFormat(val major: Int, val minor: Int = 0) : Comparable<PackFormat> {

    override fun compareTo(other: PackFormat): Int = compareValuesBy(this, other, { it.major }, { it.minor })

    override fun toString(): String = "$major.$minor"

    companion object {
        /** Accepts all three legal shapes and returns null for anything else. */
        fun parse(element: JsonElement?): PackFormat? = when (element) {
            null -> null
            is JsonArray -> when (element.size) {
                1 -> element[0].asIntOrNull()?.let { PackFormat(it) }
                2 -> {
                    val major = element[0].asIntOrNull()
                    val minor = element[1].asIntOrNull()
                    if (major != null && minor != null && minor >= 0) PackFormat(major, minor) else null
                }

                else -> null
            }

            else -> element.asIntOrNull()?.let { PackFormat(it) }
        }
    }
}

/** An inclusive range of major format numbers. */
internal data class FormatRange(val min: Int, val max: Int) {

    fun covers(format: Int): Boolean = format in min..max

    fun overlaps(other: FormatRange): Boolean = min <= other.max && other.min <= max

    override fun toString(): String = if (min == max) "$min" else "$min..$max"

    companion object {
        /**
         * `InclusiveRange<T>` is `T | [T] @ 2 | { min_inclusive, max_inclusive }`.
         * Source: SpyglassMC/vanilla-mcdoc java/util/mod.mcdoc:28-35.
         */
        fun parse(element: JsonElement?): FormatRange? = when (element) {
            null -> null
            is JsonArray -> if (element.size == 2) {
                val min = element[0].asIntOrNull()
                val max = element[1].asIntOrNull()
                if (min != null && max != null) FormatRange(min, max) else null
            } else {
                null
            }

            is JsonObject -> {
                val min = element["min_inclusive"]?.asIntOrNull()
                val max = element["max_inclusive"]?.asIntOrNull()
                if (min != null && max != null) FormatRange(min, max) else null
            }

            else -> element.asIntOrNull()?.let { FormatRange(it, it) }
        }
    }
}

/** One entry of `overlays.entries`, kept in declaration order because that is the apply order. */
internal data class OverlayDeclaration(
    val position: Int,
    val directory: String?,
    val formats: FormatRange?,
    val minFormat: PackFormat?,
    val maxFormat: PackFormat?,
) {
    /** The formats this overlay claims, or null when it declares no constraint at all. */
    val range: FormatRange? = formats
        ?: minFormat?.let { FormatRange(it.major, maxFormat?.major ?: Int.MAX_VALUE) }
        ?: maxFormat?.let { FormatRange(Int.MIN_VALUE, it.major) }
}

/**
 * The `pack.mcmeta` fields that decide whether a client accepts the pack at all.
 *
 * Field shapes verified against SpyglassMC/vanilla-mcdoc java/pack.mcdoc:12,16,20,24 for the `pack`
 * object and java/pack.mcdoc:58-67 for the overlay block.
 */
internal data class PackMeta(
    val packFormat: Int?,
    val supportedFormats: FormatRange?,
    val minFormat: PackFormat?,
    val maxFormat: PackFormat?,
    val description: String?,
    val overlays: List<OverlayDeclaration>,
    /** Keys written in the `pack` object, whether or not their value could be understood. */
    val presentKeys: Set<String>,
    /**
     * Keys whose value matched no legal shape, so the client cannot read them either, mapped to the
     * value as written. Reporting the value back is what makes the finding actionable: the author
     * sees the quoted number or the three-element list rather than being told the key is wrong.
     */
    val malformedFields: Map<String, String>,
) {
    /** The single format a client would read the pack as, whichever field carries it. */
    val effectiveFormat: Int? get() = packFormat ?: minFormat?.major

    /** Everything the pack claims to support, which is what an overlay range has to fall inside. */
    val declaredRange: FormatRange?
        get() = supportedFormats
            ?: minFormat?.let { FormatRange(it.major, maxFormat?.major ?: Int.MAX_VALUE) }
            ?: packFormat?.let { FormatRange(it, it) }

    companion object {
        const val PATH = "pack.mcmeta"

        /**
         * Failure means the client would reject the file too: not JSON, not an object, or without a
         * `pack` object inside it.
         */
        fun parse(bytes: ByteArray): Result<PackMeta> = parseJson(bytes).mapCatching { root ->
            val obj = root.asObjectOrNull() ?: error("top level is ${root.typeName()}, not an object")
            val pack = obj["pack"]?.asObjectOrNull()
                ?: error(if (obj.containsKey("pack")) "`pack` is not an object" else "no `pack` object")
            val packFormat = pack["pack_format"]?.asIntOrNull()
            val supportedFormats = FormatRange.parse(pack["supported_formats"])
            val minFormat = PackFormat.parse(pack["min_format"])
            val maxFormat = PackFormat.parse(pack["max_format"])
            PackMeta(
                packFormat = packFormat,
                supportedFormats = supportedFormats,
                minFormat = minFormat,
                maxFormat = maxFormat,
                description = pack["description"]?.let { describe(it) },
                overlays = readOverlays(obj["overlays"]),
                presentKeys = pack.keys,
                malformedFields = buildMap {
                    if (pack.containsKey("pack_format") && packFormat == null) {
                        put("pack_format", pack.getValue("pack_format").toString())
                    }
                    if (pack.containsKey("supported_formats") && supportedFormats == null) {
                        put("supported_formats", pack.getValue("supported_formats").toString())
                    }
                    if (pack.containsKey("min_format") && minFormat == null) {
                        put("min_format", pack.getValue("min_format").toString())
                    }
                    if (pack.containsKey("max_format") && maxFormat == null) {
                        put("max_format", pack.getValue("max_format").toString())
                    }
                },
            )
        }

        private fun readOverlays(element: JsonElement?): List<OverlayDeclaration> {
            val entries = element?.asObjectOrNull()?.array("entries") ?: return emptyList()
            return entries.mapIndexedNotNull { position, entry ->
                val obj = entry.asObjectOrNull() ?: return@mapIndexedNotNull null
                OverlayDeclaration(
                    position = position,
                    directory = obj.string("directory"),
                    formats = FormatRange.parse(obj["formats"]),
                    minFormat = PackFormat.parse(obj["min_format"]),
                    maxFormat = PackFormat.parse(obj["max_format"]),
                )
            }
        }

        /** `description` is a Text component, so it can be a string, an object or a list. */
        private fun describe(element: JsonElement): String = when (element) {
            is JsonPrimitive -> element.content
            is JsonArray -> element.joinToString("") { describe(it) }
            is JsonObject -> element.string("text") ?: element.string("translate") ?: "(text component)"
        }

        private fun JsonElement.typeName(): String = when (this) {
            is JsonArray -> "a list"
            is JsonObject -> "an object"
            is JsonPrimitive -> if (isString) "a string" else "a number or boolean"
        }
    }
}
