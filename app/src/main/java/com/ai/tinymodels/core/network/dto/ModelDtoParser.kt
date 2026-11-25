package com.ai.tinymodels.core.network.dto

import com.ai.tinymodels.domain.model.ModelDetails
import com.ai.tinymodels.domain.model.ModelSummary
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

    private fun parseSummary(json: JSONObject): ModelSummary {
        val tags = json.optStringList("tags")
        val cardData = json.optJSONObject("cardData")
        return ModelSummary(
            id = json.optString("id"),
            modelId = json.optString("modelId", json.optString("id")),
            author = json.optString("author"),
            likes = json.optIntOrNull("likes"),
            downloads = json.optLongOrNull("downloads"),
            tags = tags,
            libraryName = json.optStringOrNull("library_name"),
            pipelineTag = json.optStringOrNull("pipeline_tag"),
            lastModified = json.optStringOrNull("lastModified"),
            siblings = json.optObjectStringList("siblings", "rfilename"),
            createdAt = json.optStringOrNull("createdAt"),
            trendingScore = json.optDoubleOrNull("trendingScore"),
            license = extractLicense(cardData, tags),
            contextLength = extractContextLength(json)
        )
    }

    fun parseDetails(json: String): ModelDetails {
        val obj = JSONObject(json)
        val cardData = obj.optJSONObject("cardData")
        val tags = obj.optStringList("tags")
        return ModelDetails(
            id = obj.optString("modelId", obj.optString("id")),
            author = obj.optStringOrNull("author"),
            pipelineTag = obj.optStringOrNull("pipeline_tag"),
            libraryName = obj.optStringOrNull("library_name"),
            tags = tags,
            downloads = obj.optLongOrNull("downloads"),
            likes = obj.optIntOrNull("likes"),
            lastModified = obj.optStringOrNull("lastModified"),
            createdAt = obj.optStringOrNull("createdAt"),
            siblings = obj.optObjectStringList("siblings", "rfilename"),
            usedStorage = obj.optLongOrNull("usedStorage"),
            sha = obj.optStringOrNull("sha"),
            gated = obj.opt("gated")?.toString()?.takeIf { it.isNotBlank() && it != "null" },
            disabled = obj.optBoolean("disabled", false),
            widgetPrompts = obj.optObjectStringList("widgetData", "text"),
            baseModel = cardData?.optStringOrNull("base_model"),
            license = extractLicense(cardData, tags),
            trendingScore = obj.optDoubleOrNull("trendingScore"),
            parameterCount = extractParameterCount(obj),
            contextLength = extractContextLength(obj),
            ggufTotalBytes = obj.optJSONObject("gguf")?.optLongOrNull("total"),
            ggufArchitecture = obj.optJSONObject("gguf")?.optStringOrNull("architecture")
        )
    }

    private fun extractLicense(cardData: JSONObject?, tags: List<String>): String? {
        cardData?.optStringOrNull("license")?.let { return it }
        cardData?.opt("license")?.let { value ->
            when (value) {
                is String -> if (value.isNotBlank()) return value
                is JSONArray -> value.optString(0).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return tags.firstOrNull { it.startsWith("license:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractParameterCount(obj: JSONObject): Long? {
        val safetensors = obj.optJSONObject("safetensors")
        safetensors?.optLongOrNull("total")?.let { if (it > 0) return it }
        val params = safetensors?.optJSONObject("parameters")
        if (params != null) {
            var sum = 0L
            val keys = params.keys()
            while (keys.hasNext()) {
                sum += params.optLong(keys.next(), 0L)
            }
            if (sum > 0) return sum
        }
        return null
    }

    private fun extractContextLength(obj: JSONObject): Int? {
        obj.optJSONObject("gguf")?.optIntOrNull("context_length")?.let { if (it > 0) return it }
        val config = obj.optJSONObject("config") ?: return null
        listOf("max_position_embeddings", "max_sequence_length", "n_ctx", "seq_length", "context_length").forEach { key ->
            config.optIntOrNull(key)?.let { if (it > 0) return it }
        }
        return null
    }

    // ---- JSON helpers ----

    private fun JSONObject.optStringOrNull(key: String): String? =
        optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() || it.isInfinite() } else null

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
