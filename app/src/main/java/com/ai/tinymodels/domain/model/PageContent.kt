package com.ai.tinymodels.domain.model

/** Readable text extracted from one web page. */
data class PageContent(
    val url: String,
    val text: String
)
