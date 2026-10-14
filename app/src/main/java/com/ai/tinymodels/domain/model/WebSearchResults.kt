package com.ai.tinymodels.domain.model

/**
 * What one search returned. Distinguishes "nothing found" from "blocked"
 * (every engine answered with a challenge) — the two want different words.
 */
data class WebSearchResults(
    val query: String,
    val results: List<WebSearchResult>,
    val fetchedAt: Long = System.currentTimeMillis(),
    /** Readable text pulled from the top result's page, when fetchable. */
    val leadText: String = "",
    /** Every engine that answered did so with a challenge page. */
    val blocked: Boolean = false
) {
    val isEmpty: Boolean get() = results.isEmpty()
    val isNotEmpty: Boolean get() = results.isNotEmpty()
}
