package com.example.tinymodels.core.network

import com.example.tinymodels.domain.model.WebSearchResult
import com.example.tinymodels.domain.repository.WebSearchException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keyless web-search client for the model's `web_search` tool.
 *
 * v1 uses DuckDuckGo's **Instant Answer API** (`api.duckduckgo.com/?q=…&format=json`)
 * — official, keyless, returns compact JSON (Abstract + RelatedTopics) that
 * parses without an HTML parser. It is intentionally abstracted behind
 * [com.example.tinymodels.domain.repository.WebSearchRepository] so a richer
 * backend (Brave/Tavily with a key) can be swapped in later.
 */
@Singleton
class WebSearchClient @Inject constructor() {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Searches DuckDuckGo for [query]. All network work runs on IO.
     * Returns at most [maxResults] results; empty when nothing found.
     * @throws WebSearchException on network/parse failure.
     */
    suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
            val request = Request.Builder()
                .url("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
                .header("User-Agent", "TinyModels/1.0 (Android)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw WebSearchException("Search backend returned HTTP ${response.code}")
                }
                val body = response.body?.string()
                    ?: throw WebSearchException("Search backend returned an empty response")
                parseResults(body, query, maxResults)
            }
        }

    /**
     * Parses the Instant Answer JSON into results:
     *  1. The abstract (topic summary) as the top result, when present.
     *  2. Related topics (each `Text` + `FirstURL`) as further results.
     */
    internal fun parseResults(body: String, query: String, maxResults: Int): List<WebSearchResult> {
        val results = mutableListOf<WebSearchResult>()
        try {
            val root = org.json.JSONObject(body)
            // 1. Abstract topic.
            val abstractText = root.optString("AbstractText").takeIf { it.isNotBlank() }
            val abstractUrl = root.optString("AbstractURL").takeIf { it.isNotBlank() }
            if (abstractText != null) {
                results += WebSearchResult(
                    title = root.optString("Heading").takeIf { it.isNotBlank() } ?: query,
                    url = abstractUrl ?: "https://duckduckgo.com/?q=${URLEncoder.encode(query, Charsets.UTF_8.name())}",
                    snippet = abstractText
                )
            }
            // 2. Related topics.
            val related = root.optJSONArray("RelatedTopics") ?: JSONArray()
            for (i in 0 until related.length()) {
                if (results.size >= maxResults) break
                val topic = related.optJSONObject(i) ?: continue
                // Nested groups ("Name": "…") hold no direct text — skip.
                val text = topic.optString("Text").takeIf { it.isNotBlank() } ?: continue
                val url = topic.optString("FirstURL").takeIf { it.isNotBlank() } ?: continue
                val title = text.substringBefore(" - ").trim().take(80)
                val snippet = text.substringAfter(" - ", "").trim().ifBlank { text }
                results += WebSearchResult(title = title, url = url, snippet = snippet)
            }
        } catch (e: org.json.JSONException) {
            throw WebSearchException("Could not parse search results", e)
        }
        return results.take(maxResults)
    }
}