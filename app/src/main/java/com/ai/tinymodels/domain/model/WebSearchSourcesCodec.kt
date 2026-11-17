package com.ai.tinymodels.domain.model

import org.json.JSONArray
import org.json.JSONObject

/** JSON codec for persisting web-search sources alongside chat messages. */
object WebSearchSourcesCodec {

    fun toJson(sources: List<WebSearchResult>): String {
        val array = JSONArray()
        sources.forEach { s ->
            array.put(
                JSONObject()
                    .put("title", s.title)
                    .put("url", s.url)
                    .put("snippet", s.snippet)
                    .put("query", s.query)
            )
        }
        return array.toString()
    }

    fun fromJson(json: String?): List<WebSearchResult> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = obj.optString("url").orEmpty()
                    if (url.isEmpty()) continue
                    add(
                        WebSearchResult(
                            title = obj.optString("title", url),
                            url = url,
                            snippet = obj.optString("snippet", ""),
                            query = obj.optString("query", "")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
