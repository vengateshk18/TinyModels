package com.ai.tinymodels.core.network

import com.ai.tinymodels.domain.model.WebSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSearchFilterRankTest {

    private fun r(title: String, url: String, snippet: String = "") =
        WebSearchResult(title = title, url = url, snippet = snippet)

    @Test fun `dedupes by host keeping first occurrence`() {
        val ranked = WebSearchFilter.rank(
            listOf(
                r("A1", "https://en.wikipedia.org/wiki/A"),
                r("A2", "https://en.wikipedia.org/wiki/B"),
                r("C", "https://example.com/c")
            )
        )
        assertEquals(listOf("https://en.wikipedia.org/wiki/A", "https://example.com/c"), ranked.map { it.url })
    }

    @Test fun `demotes listicle titles after plain results`() {
        val ranked = WebSearchFilter.rank(
            listOf(
                r("Top 10 Best Laptops 2026", "https://list.example/a"),
                r("MacBook Pro M4 review", "https://review.example/b")
            )
        )
        assertEquals("https://review.example/b", ranked.first().url)
    }

    @Test fun `keeps stable order among non-listicles`() {
        val ranked = WebSearchFilter.rank(
            listOf(r("A", "https://a.example/1"), r("B", "https://b.example/2"), r("C", "https://c.example/3"))
        )
        assertEquals(listOf("a.example", "b.example", "c.example"), ranked.map { it.displayHost })
    }

    @Test fun `empty returns empty`() {
        assertEquals(emptyList<WebSearchResult>(), WebSearchFilter.rank(emptyList()))
    }
}
