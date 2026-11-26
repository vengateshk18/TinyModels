package com.ai.tinymodels.domain.model

/**
 * Where web results come from. [KEYLESS] is the zero-config default
 * (DuckDuckGo + Bing scraping); the rest use an API the user configures.
 */
enum class SearchProvider {
    KEYLESS,
    BRAVE,
    TAVILY,
    SEARXNG
}
