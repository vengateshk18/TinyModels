package com.ai.tinymodels.domain.model

/** One page the search read, kept so the prompt can cite it as [n]. */
data class WebSearchPage(
    val url: String,
    val title: String,
    val host: String,
    val text: String
)
