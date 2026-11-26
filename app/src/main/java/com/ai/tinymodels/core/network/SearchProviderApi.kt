package com.ai.tinymodels.core.network

import com.ai.tinymodels.domain.model.SearchProvider
import com.ai.tinymodels.domain.model.WebSearchResult
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class ProviderRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

/** Pure request builders/parsers for configured search providers. */
object SearchProviderApi {

    fun request(
        provider: SearchProvider,
        config: WebSearchConfig,
        query: String,
        maxResults: Int,
        freshness: SearchFreshness
    ): ProviderRequest {
        return when (provider) {
            SearchProvider.BRAVE -> braveRequest(config, query, maxResults, freshness)
            SearchProvider.TAVILY -> tavilyRequest(config, query, maxResults, freshness)
            SearchProvider.SEARXNG -> searxngRequest(config, query, maxResults)
            SearchProvider.KEYLESS -> error("Keyless has no provider request")
        }
    }

    fun parse(provider: SearchProvider, body: String): List<WebSearchResult> = try {
        when (provider) {
            SearchProvider.BRAVE -> parseBrave(body)
            SearchProvider.TAVILY -> parseTavily(body)
            SearchProvider.SEARXNG -> parseSearxng(body)
            SearchProvider.KEYLESS -> emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun braveRequest(
        config: WebSearchConfig, query: String, maxResults: Int, freshness: SearchFreshness
    ): ProviderRequest {
        val q = enc(query)
        val freshnessParam = freshness.braveFreshness()?.let { "&freshness=$it" } ?: ""
        return ProviderRequest(
            url = "https://api.search.brave.com/res/v1/web/search?q=$q&count=$maxResults$freshnessParam",
            headers = mapOf(
                "Accept" to "application/json",
                "X-Subscription-Token" to config.apiKey.trim()
            )
        )
    }

    private fun tavilyRequest(
        config: WebSearchConfig, query: String, maxResults: Int, freshness: SearchFreshness
    ): ProviderRequest {
        val payload = JSONObject().apply {
            put("api_key", config.apiKey.trim())
            put("query", query)
            put("max_results", maxResults)
            put("search_depth", "basic")
            put("include_answer", false)
            put("include_raw_content", false)
            freshness.tavilyDays()?.let { put("days", it) }
        }
        return ProviderRequest(
            url = "https://api.tavily.com/search",
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json"
            ),
            body = payload.toString()
        )
    }

    private fun searxngRequest(
        config: WebSearchConfig, query: String, maxResults: Int
    ): ProviderRequest {
        val base = config.searxngUrl.trim().trimEnd('/')
        val q = enc(query)
        return ProviderRequest(
            url = "$base/search?q=$q&format=json&pageno=1",
            headers = mapOf("Accept" to "application/json")
        )
    }

    private fun parseBrave(body: String): List<WebSearchResult> {
        val root = JSONObject(body)
        val arr = root.optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
        return parseResultArray(arr, titleKey = "title", urlKey = "url", snippetKey = "description")
    }

    private fun parseTavily(body: String): List<WebSearchResult> {
        val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return parseResultArray(arr, titleKey = "title", urlKey = "url", snippetKey = "content")
    }

    private fun parseSearxng(body: String): List<WebSearchResult> {
        val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<WebSearchResult>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val url = item.optString("url").trim()
            if (url.isBlank() || !seen.add(url)) continue
            val title = cleanText(item.optString("title").ifBlank { url })
            val snippet = cleanText(item.optString("content"))
            out.add(WebSearchResult(title = title, url = url, snippet = snippet))
        }
        return out
    }

    private fun parseResultArray(
        arr: JSONArray, titleKey: String, urlKey: String, snippetKey: String
    ): List<WebSearchResult> {
        val out = mutableListOf<WebSearchResult>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val url = item.optString(urlKey).trim()
            if (url.isBlank()) continue
            val title = cleanText(item.optString(titleKey).ifBlank { url })
            val snippet = cleanText(item.optString(snippetKey))
            out.add(WebSearchResult(title = title, url = url, snippet = snippet))
        }
        return out
    }

    private fun cleanText(text: String): String =
        text.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
