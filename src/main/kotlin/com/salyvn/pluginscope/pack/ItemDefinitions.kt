package com.salyvn.pluginscope.pack

import com.salyvn.pluginscope.core.FindingSink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One `select` node inside an item definition, with the values each of its cases matches. */
internal data class SelectNode(
    val property: String,
    val index: JsonElement?,
    /** One entry per case, in file order, holding every value that case matches. */
    val cases: List<List<String>>,
) {
    val isCustomModelData: Boolean
        get() = property == "custom_model_data" || property == "minecraft:custom_model_data"
}

internal data class ItemDefinitionAnalysis(
    /** Every `model` / `base` string in the tree, which are references into models/. */
    val modelRefs: List<String>,
    val selects: List<SelectNode>,
)

/**
 * Reads `assets/<namespace>/items/<id>.json`, the item definition format that replaced the
 * `overrides` mechanism in 1.21.4.
 *
 * The walk is a plain recursive descent rather than a typed decode of every item model type. The
 * model tree nests through eight different node types and grows every release; descending on
 * structure catches a nested reference whichever new node introduced it, and cannot silently stop at
 * a type this build has not heard of.
 *
 * Source: SpyglassMC/vanilla-mcdoc java/assets/item_definition.mcdoc:11-100 for the tree and
 * :350-353 for the custom_model_data select property.
 */
internal object ItemDefinitions {

    const val FOLDER = "items"

    fun analyse(root: JsonObject): ItemDefinitionAnalysis {
        val modelRefs = mutableListOf<String>()
        val selects = mutableListOf<SelectNode>()
        walk(root, modelRefs, selects)
        return ItemDefinitionAnalysis(modelRefs, selects)
    }

    private fun walk(element: JsonElement, modelRefs: MutableList<String>, selects: MutableList<SelectNode>) {
        when (element) {
            is JsonObject -> {
                readSelect(element)?.let { selects += it }
                for ((key, value) in element) {
                    // `model` is a string when the node renders a model and an object when it nests
                    // another item model, so the JSON type decides, not the node's declared type.
                    if ((key == "model" || key == "base") && value is JsonPrimitive && value.isString) {
                        modelRefs += value.content
                    } else {
                        walk(value, modelRefs, selects)
                    }
                }
            }

            is JsonArray -> element.forEach { walk(it, modelRefs, selects) }
            is JsonPrimitive -> Unit
        }
    }

    private fun readSelect(node: JsonObject): SelectNode? {
        val property = node.string("property") ?: return null
        val cases = node.array("cases") ?: return null
        return SelectNode(
            property = property,
            index = node["index"],
            cases = cases.map { case ->
                when (val whenValue = case.asObjectOrNull()?.get("when")) {
                    null -> emptyList()
                    is JsonArray -> whenValue.map { render(it) }
                    else -> listOf(render(whenValue))
                }
            },
        )
    }

    /** `when` is typed per property, so values are compared as the text the client matches on. */
    private fun render(element: JsonElement): String = when (element) {
        is JsonPrimitive -> element.content
        else -> element.toString()
    }

    /**
     * A select node picks the first case whose `when` matches, so a value that appears twice makes
     * the later case unreachable. An `index` outside `int @ 0..` cannot be read at all.
     */
    fun checkCases(location: String, analysis: ItemDefinitionAnalysis, sink: FindingSink) {
        for (select in analysis.selects) {
            val positions = mutableMapOf<String, MutableList<Int>>()
            select.cases.forEachIndexed { position, values ->
                for (value in values) positions.getOrPut(value) { mutableListOf() } += position
            }
            val duplicated = positions.filterValues { it.size > 1 }
            if (duplicated.isNotEmpty()) {
                sink.add(
                    PackRules.SELECT_CASE_COLLISION,
                    location = location,
                    detail = "select on `${select.property}` repeats ${duplicated.size} value(s) " +
                        "across its ${select.cases.size} cases; only the first case for each is ever used.",
                    evidence = duplicated.entries.sortedBy { it.key }.take(6)
                        .map { (value, at) -> "\"$value\" in cases ${at.joinToString(", ")}" },
                )
            }

            val index = select.index
            if (select.isCustomModelData && index != null && (index.asIntOrNull() ?: -1) < 0) {
                sink.add(
                    PackRules.SELECT_INDEX_INVALID,
                    location = location,
                    detail = "select on `${select.property}` has index $index.",
                    evidence = listOf("index must be a whole number, zero or greater"),
                )
            }
        }
    }
}
