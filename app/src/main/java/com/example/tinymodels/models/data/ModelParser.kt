package com.example.tinymodels.models.data

import org.json.JSONArray
import org.json.JSONObject

object ModelParser {
    fun parseModelArray(arr: JSONArray): List<Model> {
        return buildList {
            for (i in 0 until arr.length()) {
                add(parseModel(arr.getJSONObject(i)))
            }
        }
    }

    fun parseModel(json: JSONObject): Model {
        return Model(
            id = json.optString("id"),
            modelId = json.optString("modelId"),
            author = json.optString("author"),
            likes = json.optIntOrNull("likes"),
            downloads = json.optLongOrNull("downloads"),
            tags = json.optStringList("tags"),
            libraryName = json.optString("library_name").takeIf { it.isNotBlank() },
            pipeLineTag = json.optString("pipeline_tag").takeIf { it.isNotBlank() },
            _id = json.optString("_id").takeIf { it.isNotBlank() },
            gated = json.optBooleanOrNull("gated"),
            lastModified = json.optString("lastModified").takeIf { it.isNotBlank() },
            private = json.optBooleanOrNull("private"),
            sha = json.optString("sha").takeIf { it.isNotBlank() },
            createdAt = json.optString("createdAt").takeIf { it.isNotBlank() },
            siblings = json.optStringListFromObjects("siblings", "rfilename")
        )
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null

    private fun JSONObject.optStringList(key: String): List<String>? {
        if (!has(key) || isNull(key)) return null
        val array = getJSONArray(key)
        return buildList {
            for (i in 0 until array.length()) {
                add(array.optString(i))
            }
        }
    }

    private fun JSONObject.optStringListFromObjects(
        key: String,
        nestedKey: String
    ): List<String>? {
        if (!has(key) || isNull(key)) return null
        val array = getJSONArray(key)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                val value = item?.optString(nestedKey).orEmpty()
                if (value.isNotBlank()) add(value)
            }
        }
    }
}