package com.ai.tinymodels.domain.repository

import com.ai.tinymodels.domain.model.PageContent
import com.ai.tinymodels.domain.model.WebSearchResults

/**
 * Web search backend used by the model's `web_search` tool and by app-side
 * auto-search. Implementations must be safe to call from any dispatcher
 * (they switch to IO internally) and must degrade gracefully (return empty /
 * throw [WebSearchException] with a friendly message) instead of crashing
 * the generation turn.
 */
interface WebSearchRepository {

    /**
     * Searches the web for [query] and returns up to [maxResults] results.
     * @throws WebSearchException when no engine could be reached —
     * the tool converts this into "Search unavailable" text for the model.
     */
    suspend fun search(
        query: String,
        maxResults: Int = 5,
        pageBudgetChars: Int = 1_500,
        pagesToRead: Int = 2
    ): WebSearchResults

    /**
     * Reads one public web page. Returns null when the URL is unsafe,
     * unreadable, or the network fails.
     */
    suspend fun openPage(url: String, query: String? = null, maxChars: Int = 2_500): PageContent?
}

/** Search backend failure with a user/model-friendly message. */
class WebSearchException(message: String, cause: Throwable? = null) : Exception(message, cause)