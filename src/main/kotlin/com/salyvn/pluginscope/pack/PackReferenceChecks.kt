package com.salyvn.pluginscope.pack

import com.salyvn.pluginscope.core.FindingSink
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Resolves a reference and decides whose problem a miss is.
 *
 * A miss in the `minecraft` namespace is never reported. Nearly every custom model inherits from
 * `minecraft:item/generated` or `item/handheld`, so treating vanilla ids as the pack's responsibility
 * would flag correct packs by the dozen, and a resource pack legitimately ships only the files it
 * replaces. A miss in a namespace the pack itself owns is a real defect: the pack claimed that
 * namespace and then did not ship the file. Anything else belongs to a pack that is not this one, so
 * it is counted and reported as a fact rather than as a finding.
 */
internal class ReferenceResolver(private val index: PackIndex) {

    var checked: Int = 0
        private set

    var expectedFromVanilla: Int = 0
        private set

    val externalNamespaces: MutableSet<String> = LinkedHashSet()

    /** The text of the defect when the pack can fix it, else null. */
    fun miss(raw: String, folder: String, suffix: String): String? {
        checked++
        val location = ResourceLocation.parse(raw) ?: return "\"$raw\" is not a resource location"
        if (index.hasAsset(location, folder, suffix)) return null
        if (location.isVanillaNamespace) {
            expectedFromVanilla++
            return null
        }
        if (location.namespace !in index.assetNamespaces) {
            externalNamespaces += location.namespace
            return null
        }
        return "$location is not at ${index.assetPath(location, folder, suffix)}"
    }

    /**
     * Same policy, except that an id written without a namespace is looked for in [defaultNamespace]
     * first. sounds.json and font files are usually written with unqualified ids, and mcdoc notes
     * that ids in a sounds.json implicitly take the namespace of the file itself
     * (java/assets/sounds.mcdoc:2).
     *
     * An unqualified id that is found in neither place is not reported. It could just as well name a
     * vanilla file the pack deliberately reuses, and the pack carries no list of what vanilla
     * contains, so there is nothing here that distinguishes a typo from a valid reuse.
     */
    fun missIn(defaultNamespace: String, raw: String, folder: String, suffix: String): String? {
        if (':' in raw) return miss(raw, folder, suffix)
        checked++
        if (index.hasAsset(ResourceLocation(defaultNamespace, raw), folder, suffix)) return null
        expectedFromVanilla++
        return null
    }
}

internal data class ReferenceFacts(
    val models: Int,
    val itemDefinitions: Int,
)

/**
 * Follows every reference the model tree and the item definitions make, and reports the ones that
 * land on nothing.
 *
 * Reference fields verified against SpyglassMC/vanilla-mcdoc java/assets/model.mcdoc:7 (`parent`),
 * :10-15 (`textures`), :24-30 (`overrides`, removed in 1.21.4) and :47 (the texture material with
 * `sprite`), plus java/assets/item_definition.mcdoc:61,109 for the item model refs.
 */
internal object PackReferenceChecks {

    private const val MODELS = "models"
    private const val TEXTURES = "textures"
    private const val JSON = ".json"
    private const val PNG = ".png"

    fun check(
        index: PackIndex,
        resolver: ReferenceResolver,
        assets: JsonAssets,
        effectiveFormat: Int?,
        sink: FindingSink,
    ): ReferenceFacts {
        val legacy = mutableListOf<String>()
        val parents = LinkedHashMap<ResourceLocation, ResourceLocation>()

        val models = index.assetEntries(MODELS, JSON)
        for ((id, path) in models) {
            val root = assets.read(path) ?: continue
            checkModel(id, path, root, resolver, parents, legacy, sink)
        }
        reportCycles(parents.filterKeys { it in models.keys }.filterValues { it in models.keys }, models, sink)

        val items = index.assetEntries(ItemDefinitions.FOLDER, JSON)
        for ((_, path) in items) {
            val root = assets.read(path) ?: continue
            val analysis = ItemDefinitions.analyse(root)
            val misses = analysis.modelRefs.mapNotNull { raw -> resolver.miss(raw, MODELS, JSON)?.let { "model $it" } }
            reportDangling(path, misses, sink)
            ItemDefinitions.checkCases(path, analysis, sink)
        }

        reportLegacyOverrides(index, effectiveFormat, legacy, sink)

        return ReferenceFacts(models = models.size, itemDefinitions = items.size)
    }

    private fun checkModel(
        id: ResourceLocation,
        path: String,
        root: JsonObject,
        resolver: ReferenceResolver,
        parents: MutableMap<ResourceLocation, ResourceLocation>,
        legacy: MutableList<String>,
        sink: FindingSink,
    ) {
        val misses = mutableListOf<String>()

        root.string("parent")?.let { raw ->
            resolver.miss(raw, MODELS, JSON)?.let { misses += "parent $it" }
            ResourceLocation.parse(raw)?.let { parents[id] = it }
        }

        root.child(TEXTURES)?.forEach { (slot, value) ->
            val raw = when {
                value is JsonPrimitive && value.isString -> value.content
                else -> value.asObjectOrNull()?.string("sprite")
            } ?: return@forEach
            // A `#` value names another texture slot, defined here or inherited from a parent that
            // may well be a vanilla model. It is not a path, so it cannot be a missing file.
            if (raw.startsWith('#')) return@forEach
            resolver.miss(raw, TEXTURES, PNG)?.let { misses += "textures.$slot $it" }
        }

        var usesLegacyPredicate = false
        for (entry in root.array("overrides") ?: emptyList<JsonElement>()) {
            val override = entry.asObjectOrNull() ?: continue
            override.string("model")?.let { raw ->
                resolver.miss(raw, MODELS, JSON)?.let { misses += "overrides model $it" }
            }
            if (override.child("predicate")?.containsKey("custom_model_data") == true) usesLegacyPredicate = true
        }
        if (usesLegacyPredicate) legacy += path

        reportDangling(path, misses, sink)
    }

    private fun reportDangling(path: String, misses: List<String>, sink: FindingSink) {
        if (misses.isEmpty()) return
        sink.add(
            PackRules.DANGLING_REFERENCE,
            location = path,
            detail = "${misses.size} reference(s) in this file resolve to a file the pack does not ship.",
            evidence = misses.take(8),
        )
    }

    private fun reportLegacyOverrides(
        index: PackIndex,
        effectiveFormat: Int?,
        legacy: List<String>,
        sink: FindingSink,
    ) {
        if (legacy.isEmpty()) return
        // Before 1.21.4 this was the only mechanism, so on a pack that declares an older format it is
        // correct rather than legacy. With no readable format the pack is reported against the
        // current shape of the game, which is what an author reading this today is building for.
        if (effectiveFormat != null && effectiveFormat < PackFormatCatalog.RESOURCE_PACK_FORMAT_1_21_4) return
        sink.add(
            PackRules.LEGACY_CUSTOM_MODEL_DATA,
            location = legacy.sorted().first(),
            detail = "${legacy.size} model(s) map custom_model_data through `overrides`, which the " +
                "model format dropped in 1.21.4.",
            evidence = legacy.take(8),
        )
    }

    /**
     * Walks each chain once. Nodes already settled by an earlier walk are skipped, and a cycle is
     * keyed by its members so entering it from three different models still reports it once.
     */
    private fun reportCycles(
        edges: Map<ResourceLocation, ResourceLocation>,
        models: Map<ResourceLocation, String>,
        sink: FindingSink,
    ) {
        val settled = HashSet<ResourceLocation>()
        val reported = HashSet<Set<ResourceLocation>>()
        for (start in edges.keys.sortedBy { it.toString() }) {
            if (start in settled) continue
            val walked = mutableListOf<ResourceLocation>()
            val onWalk = HashSet<ResourceLocation>()
            var current: ResourceLocation? = start
            while (current != null && current !in settled) {
                if (!onWalk.add(current)) {
                    val cycle = walked.subList(walked.indexOf(current), walked.size).toList()
                    if (reported.add(cycle.toSet())) report(cycle, models, sink)
                    break
                }
                walked += current
                current = edges[current]
            }
            settled += onWalk
        }
    }

    private fun report(cycle: List<ResourceLocation>, models: Map<ResourceLocation, String>, sink: FindingSink) {
        val first = cycle.indexOf(cycle.minByOrNull { it.toString() })
        val ordered = List(cycle.size) { cycle[(first + it) % cycle.size] }
        sink.add(
            PackRules.PARENT_CYCLE,
            location = models[ordered.first()] ?: ordered.first().toString(),
            detail = "The parent chain returns to its start after ${ordered.size} model(s).",
            evidence = listOf((ordered + ordered.first()).joinToString(" -> ")),
        )
    }
}
