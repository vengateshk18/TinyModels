package com.example.tinymodels.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchQueryTest {

    // ---- needsWebSearch ----

    @Test fun `needsWebSearch true for news price weather score`() {
        assertTrue(WebSearchQuery.needsWebSearch("latest AI news"))
        assertTrue(WebSearchQuery.needsWebSearch("gold price chennai today"))
        assertTrue(WebSearchQuery.needsWebSearch("weather today in chennai"))
        assertTrue(WebSearchQuery.needsWebSearch("who won yesterday IPL match"))
    }

    @Test fun `needsWebSearch false for local tasks and greetings`() {
        assertFalse(WebSearchQuery.needsWebSearch("write a poem about rain"))
        assertFalse(WebSearchQuery.needsWebSearch("translate this to tamil"))
        assertFalse(WebSearchQuery.needsWebSearch("fix my function"))
        assertFalse(WebSearchQuery.needsWebSearch("hi"))
        assertFalse(WebSearchQuery.needsWebSearch(""))
    }

    @Test fun `needsWebSearch respects explicit ask and refusal`() {
        assertTrue(WebSearchQuery.needsWebSearch("search the web whats the news"))
        assertTrue(WebSearchQuery.needsWebSearch("google it for me"))
        assertFalse(WebSearchQuery.needsWebSearch("answer without searching the web"))
        assertFalse(WebSearchQuery.needsWebSearch("do not search the internet, just answer"))
    }

    @Test fun `needsWebSearch true for year mentions`() {
        assertTrue(WebSearchQuery.needsWebSearch("election results 2024 in india"))
    }

    // ---- searchQueryFrom ----

    @Test fun `searchQueryFrom strips search-the-web prefix`() {
        assertEquals(
            "whats the news",
            WebSearchQuery.searchQueryFrom("search the web whats the news")
        )
        assertEquals(
            "whats the news",
            WebSearchQuery.searchQueryFrom("please search the web for whats the news")
        )
    }

    @Test fun `searchQueryFrom strips suffix and give-me-results`() {
        assertEquals(
            "whats the news in tamil",
            WebSearchQuery.searchQueryFrom("whats the news in tamil search the web give me results")
        )
    }

    @Test fun `searchQueryFrom resolves follow-up with carried terms`() {
        val q = WebSearchQuery.searchQueryFrom(
            "what is its price",
            previousUserTurns = listOf("latest iphone")
        )
        assertTrue(q.contains("iphone", ignoreCase = true))
        assertTrue(q.contains("price", ignoreCase = true))
    }

    @Test fun `searchQueryFrom does not pollute standalone query`() {
        // "gold price in chennai today" is complete — must not gain old topic.
        val q = WebSearchQuery.searchQueryFrom(
            "gold price in chennai today",
            previousUserTurns = listOf("latest iphone")
        )
        assertEquals("gold price in chennai today", q)
    }

    @Test fun `searchQueryFrom caps at 240 chars`() {
        val long = "word ".repeat(100).trim()
        assertTrue(WebSearchQuery.searchQueryFrom(long).length <= 240)
    }

    // ---- queryTerms (incl. Tamil) ----

    @Test fun `queryTerms keeps acronyms and drops stopwords`() {
        assertEquals(listOf("ceo", "nvidia"), WebSearchQuery.queryTerms("who is the CEO of Nvidia"))
        assertEquals(listOf("ipl", "match"), WebSearchQuery.queryTerms("who won yesterday IPL match"))
    }

    @Test fun `queryTerms handles Tamil without shattering`() {
        val terms = WebSearchQuery.queryTerms("இன்றைய சென்னை வானிலை")
        // இன்றைய/இன்று are stop words; சென்னை + வானிலை must survive whole.
        assertTrue(terms.any { it.contains("சென்னை") })
        assertTrue(terms.any { it.contains("வானிலை") })
    }

    // ---- keywordQuery / retry ----

    @Test fun `keywordQuery drops question words, keeps order, moves time to end`() {
        assertEquals("CEO Nvidia", WebSearchQuery.keywordQuery("who is the CEO of Nvidia"))
        assertEquals("IPL match yesterday", WebSearchQuery.keywordQuery("who won yesterday's IPL match"))
        assertEquals("next ISRO launch", WebSearchQuery.keywordQuery("when is the next ISRO launch"))
    }

    @Test fun `keywordQuery returns original when only filler`() {
        assertEquals("who is", WebSearchQuery.keywordQuery("who is"))
    }

    @Test fun `retryKeywordQuery swaps first two words`() {
        assertEquals("Nvidia CEO", WebSearchQuery.retryKeywordQuery("CEO Nvidia"))
    }

    @Test fun `retryKeywordQuery moves recency word to end`() {
        assertEquals(
            "gemma model release google latest",
            WebSearchQuery.retryKeywordQuery("latest gemma model release google")
        )
    }

    @Test fun `retryKeywordQuery null when no different phrasing`() {
        assertNull(WebSearchQuery.retryKeywordQuery("gold"))
    }
}
