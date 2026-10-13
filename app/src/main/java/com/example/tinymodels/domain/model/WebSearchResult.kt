package com.example.tinymodels.domain.model

/** A single web-search result returned to the model as tool output. */
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    /**
     * What was searched to find this, as sent. Every result from one search
     * holds the same string. Shown under "Sources", and the one place that
     * records follow-up context borrowing.
     */
    val query: String = ""
) {
    /** Compact one-line form fed back into the model's context. */
    fun toToolText(index: Int): String =
        "$index. $title — $snippet ($url)"

    /** Host without `www.`, for showing a source without URL noise. */
    val displayHost: String
        get() {
            val host = try {
                java.net.URI(url).host ?: return url
            } catch (_: Exception) {
                return url
            }
            if (host.isEmpty()) return url
            return if (host.startsWith("www.")) host.substring(4) else host
        }

    fun withQuery(q: String): WebSearchResult = copy(query = q)

    companion object {
        /** Chars of snippet kept in persisted history. */
        const val STORED_SNIPPET_LENGTH = 200

        fun fromMap(value: Map<*, *>?): WebSearchResult? {
            val url = value?.get("url") as? String ?: return null
            if (url.isEmpty()) return null
            val title = value["title"] as? String ?: url
            val snippet = value["snippet"] as? String ?: ""
            val query = value["query"] as? String ?: ""
            return WebSearchResult(title = title, url = url, snippet = snippet, query = query)
        }
    }

    /** Persisted with the conversation so saved replies trace back to sources. */
    fun toMap(): Map<String, Any?> = buildMap {
        put("title", title)
        put("url", url)
        if (snippet.isNotEmpty()) {
            put(
                "snippet",
                if (snippet.length > STORED_SNIPPET_LENGTH) {
                    snippet.substring(0, STORED_SNIPPET_LENGTH).trimEnd() + "…"
                } else snippet
            )
        }
        if (query.isNotEmpty()) put("query", query)
    }
}