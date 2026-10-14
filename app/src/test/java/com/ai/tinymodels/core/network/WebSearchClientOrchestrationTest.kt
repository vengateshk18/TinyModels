package com.ai.tinymodels.core.network

import com.ai.tinymodels.domain.model.WebSearchResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orchestration tests with canned fetchers (no network): fallback order,
 * relevance filtering across engines, challenge/blocked, keyword retry,
 * and query attach. Mirrors Thinai's live-audit cases.
 */
class WebSearchClientOrchestrationTest {

    private fun ep(
        url: String,
        keywords: Boolean = false,
        results: List<WebSearchResult>
    ) = SearchEndpoint(url = url, keywords = keywords, parse = { results })

    private fun r(title: String, snippet: String, url: String = "https://example.com/${title.hashCode()}") =
        WebSearchResult(title = title, url = url, snippet = snippet)

    @Test fun `falls back to second engine when first is empty`() = runTest {
        val client = WebSearchClient()
        val good = listOf(r("Chennai weather today", "chennai weather forecast rain today"))
        client.endpoints = listOf(ep("https://a.test/", results = emptyList()), ep("https://b.test/", results = good))
        client.fetcher = { _, _ -> 200 to "<html></html>" }
        val out = client.search("weather today in chennai", readTopResult = false)
        assertTrue(out.isNotEmpty)
        assertEquals("weather today in chennai", out.query)
        assertEquals("weather today in chennai", out.results.first().query)
    }

    @Test fun `drops irrelevant first engine and uses next`() = runTest {
        val client = WebSearchClient()
        val won = listOf(r("South Korean won - Wikipedia", "currency of South Korea"))
        val score = listOf(r("India vs Australia scorecard", "india australia match score yesterday"))
        client.endpoints = listOf(ep("https://a.test/", results = won), ep("https://b.test/", results = score))
        client.fetcher = { _, _ -> 200 to "x" }
        val out = client.search(
            "who won the last india vs australia match",
            readTopResult = false
        )
        assertEquals(1, out.results.size)
        assertTrue(out.results[0].title.contains("scorecard", ignoreCase = true))
    }

    @Test fun `challenge on all engines yields blocked empty`() = runTest {
        val client = WebSearchClient()
        client.endpoints = listOf(ep("https://a.test/", results = emptyList()))
        client.fetcher = { _, _ -> 202 to "anomaly challenge" }
        val out = client.search("latest news", readTopResult = false)
        assertTrue(out.isEmpty)
        assertTrue(out.blocked)
    }

    @Test fun `keyword retry reorders and wins`() = runTest {
        val client = WebSearchClient()
        val generic = listOf(r("What does a CEO do", "chief executive duties"))
        val specific = listOf(r("Jensen Huang Nvidia CEO", "nvidia chief executive jensen huang"))
        // keywords=true so sent="CEO Nvidia", retry="Nvidia CEO".
        client.endpoints = listOf(ep("https://b.test/", keywords = true, results = generic))
        val calls = mutableListOf<String>()
        val realGeneric = generic
        client.fetcher = { _, sent ->
            calls.add(sent)
            if (calls.size == 1) 200 to "first" else 200 to "second"
        }
        // Swap parser per call: first generic, then specific.
        var n = 0
        client.endpoints = listOf(
            SearchEndpoint(url = "https://b.test/", keywords = true, parse = {
                n++
                if (n == 1) realGeneric else specific
            })
        )
        val out = client.search("who is the CEO of Nvidia", readTopResult = false)
        assertEquals(2, calls.size)
        assertEquals("CEO Nvidia", calls[0])
        assertEquals("Nvidia CEO", calls[1])
        assertTrue(out.results[0].title.contains("Nvidia", ignoreCase = true))
    }

    @Test fun `empty everywhere is not blocked`() = runTest {
        val client = WebSearchClient()
        client.endpoints = listOf(ep("https://a.test/", results = emptyList()))
        client.fetcher = { _, _ -> 200 to "<html>no markers here</html>" }
        val out = client.search("some very obscure query xyz", readTopResult = false)
        assertTrue(out.isEmpty)
        assertFalse(out.blocked)
    }
}
