package com.salyvn.pluginscope.pack

import com.salyvn.pluginscope.core.FindingSink
import kotlinx.serialization.json.JsonElement

/**
 * The three asset kinds that name files outside their own folder: sound events, font providers and
 * atlas sprite sources. Each is a place where a pack can ship a definition whose file never arrives,
 * and none of them produces a server-side symptom.
 */
internal object PackAssetChecks {

    private const val SOUNDS_FILE = "sounds.json"
    private const val SOUNDS = "sounds"
    private const val OGG = ".ogg"
    private const val FONT = "font"
    private const val TEXTURES = "textures"
    private const val ATLASES = "atlases"
    private const val JSON = ".json"
    private const val PNG = ".png"

    fun check(index: PackIndex, resolver: ReferenceResolver, assets: JsonAssets, sink: FindingSink) {
        checkSounds(index, resolver, assets, sink)
        checkFonts(index, resolver, assets, sink)
        checkAtlases(index, resolver, assets, sink)
    }

    /**
     * sounds.json sits at the root of a namespace rather than in a folder, so it is found by shape
     * instead of through the folder index.
     *
     * Source: SpyglassMC/vanilla-mcdoc java/assets/sounds.mcdoc:1,8,19,43.
     */
    private fun checkSounds(index: PackIndex, resolver: ReferenceResolver, assets: JsonAssets, sink: FindingSink) {
        val files = index.source.paths.filter { path ->
            val parts = path.split('/')
            parts.size == 3 && parts[0] == "assets" && parts[2] == SOUNDS_FILE
        }
        for (path in files.sorted()) {
            val namespace = path.split('/')[1]
            val root = assets.read(path) ?: continue
            val misses = mutableListOf<String>()
            for ((event, registration) in root) {
                for (entry in registration.asObjectOrNull()?.array(SOUNDS) ?: emptyList<JsonElement>()) {
                    val name = soundName(entry) ?: continue
                    resolver.missIn(namespace, name, SOUNDS, OGG)?.let { misses += "$event: $it" }
                }
            }
            if (misses.isEmpty()) continue
            sink.add(
                PackRules.SOUND_FILE_MISSING,
                location = path,
                detail = "${misses.size} sound(s) name an .ogg the pack does not ship.",
                evidence = misses.take(8),
            )
        }
    }

    /** Null when the entry names another sound event, which is an id in a registry and not a file. */
    private fun soundName(entry: JsonElement): String? {
        entry.asStringOrNull()?.let { return it }
        val obj = entry.asObjectOrNull() ?: return null
        if (obj.string("type") == "event") return null
        return obj.string("name")
    }

    /**
     * Each provider type names its file in its own field and resolves it against its own folder.
     *
     * Source: SpyglassMC/vanilla-mcdoc java/assets/font.mcdoc:32,45,61,86, with the folders confirmed
     * against the extracted vanilla pack as recorded on [PackRules.FONT_FILE_MISSING]. The extension
     * is part of the id for every field except `reference`, which names another font file.
     */
    private fun checkFonts(index: PackIndex, resolver: ReferenceResolver, assets: JsonAssets, sink: FindingSink) {
        for ((id, path) in index.assetEntries(FONT, JSON)) {
            val root = assets.read(path) ?: continue
            val misses = mutableListOf<String>()
            for (provider in root.array("providers") ?: emptyList<JsonElement>()) {
                val obj = provider.asObjectOrNull() ?: continue
                val field = when (obj.string("type")?.removePrefix("minecraft:")) {
                    "bitmap" -> Triple(obj.string("file"), TEXTURES, "")
                    "ttf" -> Triple(obj.string("file"), FONT, "")
                    "unihex" -> Triple(obj.string("hex_file"), FONT, "")
                    "reference" -> Triple(obj.string("id"), FONT, JSON)
                    else -> continue
                }
                val raw = field.first ?: continue
                resolver.missIn(id.namespace, raw, field.second, field.third)?.let { misses += it }
            }
            if (misses.isEmpty()) continue
            sink.add(
                PackRules.FONT_FILE_MISSING,
                location = path,
                detail = "${misses.size} provider(s) name a file the pack does not ship.",
                evidence = misses.take(8),
            )
        }
    }

    /**
     * Only the sprite source fields that reference an existing texture are followed. `sprite` on a
     * single source and on an unstitch region is `definition=true`, meaning it names the sprite the
     * source creates, so checking it for a file would report every correct unstitch in existence.
     *
     * Source: SpyglassMC/vanilla-mcdoc java/assets/atlas.mcdoc:28,31,53,54,56,71,80.
     */
    private fun checkAtlases(index: PackIndex, resolver: ReferenceResolver, assets: JsonAssets, sink: FindingSink) {
        for ((id, path) in index.assetEntries(ATLASES, JSON)) {
            val root = assets.read(path) ?: continue
            val misses = mutableListOf<String>()
            val note = { raw: String -> resolver.missIn(id.namespace, raw, TEXTURES, PNG)?.let { misses += it } }
            for (entry in root.array("sources") ?: emptyList<JsonElement>()) {
                val obj = entry.asObjectOrNull() ?: continue
                when (obj.string("type")?.removePrefix("minecraft:")) {
                    "single", "unstitch" -> obj.string("resource")?.let(note)

                    "paletted_permutations" -> {
                        for (texture in obj.array(TEXTURES) ?: emptyList<JsonElement>()) {
                            texture.asStringOrNull()?.let(note)
                        }
                        obj.string("palette_key")?.let(note)
                        obj.child("permutations")?.values?.forEach { it.asStringOrNull()?.let(note) }
                    }
                }
            }
            if (misses.isEmpty()) continue
            sink.add(
                PackRules.ATLAS_SOURCE_MISSING,
                location = path,
                detail = "${misses.size} sprite source(s) name a texture the pack does not ship.",
                evidence = misses.take(8),
            )
        }
    }
}
