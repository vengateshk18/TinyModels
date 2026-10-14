package com.ai.tinymodels.core.network

import com.ai.tinymodels.domain.model.WebSearchResult

/**
 * One place the app knows how to ask. Ported from Thinai's `SearchEndpoint`.
 */
data class SearchEndpoint(
    val url: String,
    val post: Boolean = false,
    /** Whether this engine is sent [WebSearchQuery.keywordQuery] not the question. */
    val keywords: Boolean = false,
    /** Browser to claim to be; null uses the app-wide default. */
    val userAgent: String? = null,
    val parse: (String) -> List<WebSearchResult>
)

object WebSearchEndpoints {

    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /**
     * Lite first (same index, fraction of markup), then full HTML, then a
     * different index (Bing) for when DDG answers with a challenge page.
     */
    val DEFAULT: List<SearchEndpoint> = listOf(
        SearchEndpoint(
            url = "https://lite.duckduckgo.com/lite/",
            post = true,
            parse = { WebSearchParser.parseDuckDuckGoHtml(it) }
        ),
        SearchEndpoint(
            url = "https://html.duckduckgo.com/html/",
            post = true,
            parse = { WebSearchParser.parseDuckDuckGoHtml(it) }
        ),
        SearchEndpoint(
            url = "https://www.bing.com/search",
            keywords = true,
            userAgent = DESKTOP_UA,
            parse = { WebSearchParser.parseHeadingResults(it) }
        )
    )

    fun headersFor(endpoint: SearchEndpoint): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to (endpoint.userAgent ?: MOBILE_UA),
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        val host = try {
            java.net.URI(endpoint.url).host ?: ""
        } catch (_: Exception) {
            ""
        }
        if (host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")) {
            headers["Referer"] = "https://duckduckgo.com/"
        }
        return headers
    }
}
