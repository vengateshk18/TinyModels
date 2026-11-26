package com.ai.tinymodels.core.network

import com.ai.tinymodels.domain.model.WebSearchResult

/**
 * Relevance filtering ported from Thinai's `keepRelevant`.
 *
 * A fallback engine given "who won the last india vs australia match" can
 * answer with "South Korean won — Wikipedia". Handing that to a model
 * produces a confident wrong answer — worse than no search. Better to have
 * found nothing and say so.
 */
object WebSearchFilter {

    private data class Scored(val score: Int, val index: Int, val matched: Int, val result: WebSearchResult)

    /**
     * Drops results with nothing to do with [query], ranks survivors by
     * coverage (title hit = 2, snippet hit = 1). Ties keep engine order.
     */
    fun keepRelevant(results: List<WebSearchResult>, query: String): List<WebSearchResult> {
        val terms = WebSearchQuery.queryTerms(query)
        if (terms.isEmpty()) return results
        val scored = mutableListOf<Scored>()
        for (i in results.indices) {
            val r = results[i]
            val title = r.title.lowercase()
            val snippet = r.snippet.lowercase()
            var matched = 0
            var score = 0
            for (term in terms) {
                if (WebSearchQuery.mentions(title, term)) {
                    matched++
                    score += 2
                } else if (WebSearchQuery.mentions(snippet, term)) {
                    matched++
                    score += 1
                }
            }
            if (matched > 0) scored.add(Scored(score, i, matched, r))
        }
        val needed: Int = if (terms.size >= 3) {
            (terms.size + 1) / 2
        } else {
            val anyComplete = scored.any { it.matched == terms.size }
            if (anyComplete) terms.size else 1
        }
        return scored.filter { it.matched >= needed }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.index })
            .map { it.result }
    }

    private val LISTICLE = Regex(
        """^\s*(top\s+\d+|best\s+\d+|\d+\s+(best|top)|top\s+(picks|deals))\b""",
        RegexOption.IGNORE_CASE
    )
    private val YEAR_LISTICLE = Regex("""\b(top|best)\b.{0,24}\b20\d{2}\b""", RegexOption.IGNORE_CASE)

    /**
     * Host-dedupe and demote SEO listicles. Runs after relevance filtering so
     * the first per-host result is the most relevant one.
     */
    fun rank(results: List<WebSearchResult>, maxPerHost: Int = 1): List<WebSearchResult> {
        if (results.isEmpty()) return results
        val seenHosts = mutableMapOf<String, Int>()
        val deduped = ArrayList<WebSearchResult>(results.size)
        for (r in results) {
            val host = r.displayHost.lowercase()
            val count = seenHosts[host] ?: 0
            if (count >= maxPerHost) continue
            seenHosts[host] = count + 1
            deduped.add(r)
        }
        val (plain, listicles) = deduped.partition { !isListicle(it.title) }
        return plain + listicles
    }

    private fun isListicle(title: String): Boolean =
        LISTICLE.containsMatchIn(title) || YEAR_LISTICLE.containsMatchIn(title)

    /**
     * Whether some result names every term of a 1-2 term [query].
     * With 3+ terms [keepRelevant] already demands half, so anything counts.
     */
    fun anyCoversAll(results: List<WebSearchResult>, query: String): Boolean {
        val terms = WebSearchQuery.queryTerms(query)
        if (terms.size >= 3 || terms.isEmpty()) return true
        return results.any { r ->
            val text = "${r.title} ${r.snippet}".lowercase()
            terms.all { WebSearchQuery.mentions(text, it) }
        }
    }
}
