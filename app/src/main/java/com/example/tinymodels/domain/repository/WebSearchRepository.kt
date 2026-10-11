package com.example.tinymodels.domain.repository

import com.example.tinymodels.domain.model.WebSearchResult

/**
 * Web search backend used by the model's `web_search` tool. Implementations
 * must be safe to call from any dispatcher (they switch to IO internally)
 * and must degrade gracefully (return empty / throw [WebSearchException]
 * with a friendly message) instead of crashing the generation turn.
 */
interface WebSearchRepository {

    /**
     * Searches the web for [query] and returns up to [maxResults] results.
     * @throws WebSearchException when the search backend is unreachable —
     * the tool converts this into "Search unavailable" text for the model.
     */
    suspend fun search(query: String, maxResults: Int = 5): List<WebSearchResult>
}

/** Search backend failure with a user/model-friendly message. */
class WebSearchException(message: String, cause: Throwable? = null) : Exception(message, cause)