package com.example.tinymodels.core.network.dto

import com.example.tinymodels.domain.model.ModelDetails
import com.example.tinymodels.domain.model.ModelSummary
import org.json.JSONArray
import org.json.JSONObject

/** Parses HuggingFace JSON into domain models. Reuses the proven field mapping. */
object ModelDtoParser {

    fun parseModelList(json: String): List<ModelSummary> {
        val array = JSONArray(json)
        return buildList {
            for (i in 0 until array.length()) {
                add(parseSummary(array.getJSONObject(i)))
            }
        }
    }

    private fun parseSummary(json: JSONObject) = ModelSummary(
        id = json.optString("id"),
        modelId = json.optString("modelId", json.optString("id")),
        author = json.optString("author"),
        likes = json.optIntOrNull("likes"),
        downloads = json.optLongOrNull("downloads"),
        tags = json.optStringList("tags"),
        libraryName = json.optStringOrNull("library_name"),
        pipelineTag = json.optStringOrNull("pipeline_tag"),
        lastModified = json.optStringOrNull("lastModified")
    )

    fun parseDetails(json: String): ModelDetails {
        val obj = JSONObject(json)
        return ModelDetails(
            id = obj.optString("modelId", obj.optString("id")),
            author = obj.optStringOrNull("author"),
            pipelineTag = obj.optStringOrNull("pipeline_tag"),
            libraryName = obj.optStringOrNull("library_name"),
            tags = obj.optStringList("tags"),
            downloads = obj.optLongOrNull("downloads"),
            likes = obj.optIntOrNull("likes"),
            lastModified = obj.optStringOrNull("lastModified"),
            createdAt = obj.optStringOrNull("createdAt"),
            siblings = obj.optObjectStringList("siblings", "rfilename")
        )
    }

    // ---- JSON helpers ----

    private fun JSONObject.optStringOrNull(key: String): String? =
        optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optStringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONObject.optObjectStringList(key: String, nestedKey: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.optString(nestedKey)?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}
