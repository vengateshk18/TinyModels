package com.example.titymodels.models.data

import org.json.JSONObject

object ModelDetailsParser {
    fun parse(json: JSONObject): ModelDetails {
        val cardData = json.optJSONObject("cardData")
        return ModelDetails(
            id = json.optString("modelId", json.optString("id")),
            author = json.optStringOrNull("author"),
            pipelineTag = json.optStringOrNull("pipeline_tag"),
            libraryName = json.optStringOrNull("library_name"),
            tags = json.optStringList("tags"),
            downloads = json.optLongOrNull("downloads"),
            likes = json.optIntOrNull("likes"),
            sha = json.optStringOrNull("sha"),
            lastModified = json.optStringOrNull("lastModified"),
            gated = json.opt("gated")?.toString()?.takeIf { it.isNotBlank() },
            disabled = json.optBoolean("disabled", false),
            widgetPrompts = json.optObjectStringList("widgetData", "text"),
            baseModel = cardData?.optStringOrNull("base_model"),
            createdAt = json.optStringOrNull("createdAt"),
            siblings = json.optObjectStringList("siblings", "rfilename"),
            spaces = json.optStringList("spaces"),
            usedStorage = json.optLongOrNull("usedStorage")
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optStringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONObject.optObjectStringList(key: String, nestedKey: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)
                    ?.optString(nestedKey)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
    }
}
