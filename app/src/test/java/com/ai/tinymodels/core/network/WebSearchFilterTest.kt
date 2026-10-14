package com.ai.tinymodels.core.network

import com.ai.tinymodels.domain.model.WebSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchFilterTest {

    private fun r(title: String, snippet: String) =
        WebSearchResult(title = title, url = "https://example.com/${title.hashCode()}", snippet = snippet)

    @Test fun `keepRelevant drops South-Korean-won style mismatch`() {
        val results = listOf(
            r("South Korean won - Wikipedia", "currency of South Korea exchange rate"),
            r("India vs Australia match scorecard", "india australia match score yesterday")
        )
        val kept = WebSearchFilter.keepRelevant(results, "who won the last india vs australia match")
        assertEquals(1, kept.size)
        assertTrue(kept[0].title.contains("scorecard", ignoreCase = true))
    }

    @Test fun `keepRelevant drops Electoral Officer for Nvidia CEO`() {
        val results = listOf(
            r("Chief Electoral Officer Delhi", "delhi election officer ceo contact"),
            r("Jensen Huang Nvidia CEO", "nvidia chief executive jensen huang")
        )
        val kept = WebSearchFilter.keepRelevant(results, "who is the CEO of Nvidia")
        assertEquals(1, kept.size)
        assertTrue(kept[0].title.contains("Nvidia", ignoreCase = true))
    }

    @Test fun `keepRelevant ranks title hits above snippet hits`() {
        val results = listOf(
            r("Generic page", "mentions erode weather somewhere in text"),
            r("Erode weather forecast", "today report")
        )
        val kept = WebSearchFilter.keepRelevant(results, "weather in erode")
        assertEquals(2, kept.size)
        assertEquals("Erode weather forecast", kept[0].title)
    }

    @Test fun `keepRelevant keeps one-term match for tamil-news style query`() {
        val results = listOf(r("Dinamalar Tamil headlines", "tamil daily news paper"))
        val kept = WebSearchFilter.keepRelevant(results, "tamil news")
        assertEquals(1, kept.size)
    }

    @Test fun `anyCoversAll true only when a result names every term`() {
        val partial = listOf(r("weather report", "weather today"))
        assertFalse(WebSearchFilter.anyCoversAll(partial, "weather erode"))
        val full = listOf(r("Erode weather", "erode weather forecast today"))
        assertTrue(WebSearchFilter.anyCoversAll(full, "weather erode"))
    }
}
