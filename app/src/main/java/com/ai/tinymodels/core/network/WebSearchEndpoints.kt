package com.ai.tinymodels.core.network

import com.ai.tinymodels.core.common.TinyConstants
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

    const val MOBILE_UA = TinyConstants.USER_AGENT_MOBILE

    const val DESKTOP_UA = TinyConstants.USER_AGENT_DESKTOP

    /**
     * Lite first (same index, fraction of markup), then full HTML, then a
     * different index (Bing) for when DDG answers with a challenge page.
     */
    val DEFAULT: List<SearchEndpoint> = listOf(
        SearchEndpoint(
            url = TinyConstants.DUCKDUCKGO_LITE_URL,
            post = true,
            parse = { WebSearchParser.parseDuckDuckGoHtml(it) }
        ),
        SearchEndpoint(
            url = TinyConstants.DUCKDUCKGO_HTML_URL,
            post = true,
            parse = { WebSearchParser.parseDuckDuckGoHtml(it) }
        ),
        SearchEndpoint(
            url = TinyConstants.BING_SEARCH_URL,
            keywords = true,
            userAgent = DESKTOP_UA,
            parse = { WebSearchParser.parseHeadingResults(it) }
        )
    )

    fun headersFor(endpoint: SearchEndpoint): Map<String, String> {
        val headers = mutableMapOf(
            TinyConstants.HTTP_HEADER_USER_AGENT to (endpoint.userAgent ?: MOBILE_UA),
            TinyConstants.HTTP_HEADER_ACCEPT to TinyConstants.HTTP_ACCEPT_HTML,
            TinyConstants.HTTP_HEADER_ACCEPT_LANGUAGE to TinyConstants.HTTP_ACCEPT_LANGUAGE_EN
        )
        val host = try {
            java.net.URI(endpoint.url).host ?: ""
        } catch (_: Exception) {
            ""
        }
        if (host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")) {
            headers[TinyConstants.HTTP_HEADER_REFERER] = "${TinyConstants.DUCKDUCKGO_BASE_URL}/"
        }
        return headers
    }
}
