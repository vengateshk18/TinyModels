package com.example.tinymodels.domain.model

/** A single web-search result returned to the model as tool output. */
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String
) {
    /** Compact one-line form fed back into the model's context. */
    fun toToolText(index: Int): String =
        "$index. $title — $snippet ($url)"
}