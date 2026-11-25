package com.ai.tinymodels.core.network.dto

import com.ai.tinymodels.domain.model.ModelCollection
import org.json.JSONObject

/** Parses Hugging Face collection JSON into [ModelCollection]. */
object ModelCollectionDtoParser {

    fun parseCollections(json: String): List<ModelCollection> {
        val array = org.json.JSONArray(json)
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                add(parseCollection(obj))
            }
        }
    }

    fun parseCollection(json: String): ModelCollection = parseCollection(JSONObject(json))

    private fun parseCollection(obj: JSONObject): ModelCollection {
        val slug = obj.optString("slug")
        val ownerObj = obj.optJSONObject("owner")
        val owner = ownerObj?.optString("name").orEmpty().ifBlank { slug.substringBefore('/', "") }
        val items = obj.optJSONArray("items")
        val modelIds = buildList {
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    if (item.optString("type") != "model") continue
                    val id = item.optString("id").ifBlank { item.optString("modelId") }
                    if (id.isNotBlank()) add(id)
                }
            }
        }
        return ModelCollection(
            slug = slug,
            title = obj.optString("title").ifBlank { slug.substringAfterLast('/') },
            description = obj.optString("description"),
            owner = owner,
            avatarUrl = ownerObj?.optString("avatarUrl")?.takeIf { it.isNotBlank() },
            itemCount = modelIds.size,
            modelIds = modelIds
        )
    }
}
