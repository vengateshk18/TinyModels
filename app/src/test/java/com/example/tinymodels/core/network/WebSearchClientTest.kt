package com.example.tinymodels.core.network

import com.example.tinymodels.domain.repository.WebSearchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchClientTest {

    private val client = WebSearchClient()

    // ---- Tier 1: DuckDuckGo Instant Answer ----

    @Test
    fun `parseDuckDuckGoResults returns abstract as top result`() {
        val json = """
            {
              "Heading": "Kotlin (programming language)",
              "AbstractText": "Kotlin is a cross-platform, statically typed programming language.",
              "AbstractURL": "https://en.wikipedia.org/wiki/Kotlin",
              "RelatedTopics": []
            }
        """.trimIndent()

        val results = client.parseDuckDuckGoResults(json, "kotlin", 5)

        assertEquals(1, results.size)
        assertEquals("Kotlin (programming language)", results[0].title)
        assertEquals("https://en.wikipedia.org/wiki/Kotlin", results[0].url)
        assertTrue(results[0].snippet.contains("cross-platform"))
    }

    @Test
    fun `parseDuckDuckGoResults includes related topics up to maxResults`() {
        val topics = (1..3).joinToString(",") { i ->
            """{"Text": "Topic $i - Some detail about topic $i", "FirstURL": "https://example.com/$i"}"""
        }
        val json = """{"Heading": "", "AbstractText": "", "RelatedTopics": [$topics]}"""

        val results = client.parseDuckDuckGoResults(json, "query", 2)

        assertEquals(2, results.size)
        assertEquals("Topic 1", results[0].title)
        assertEquals("https://example.com/1", results[0].url)
    }

    @Test
    fun `parseDuckDuckGoResults empty response yields no results`() {
        val json = """{"Heading": "", "AbstractText": "", "RelatedTopics": []}"""
        val results = client.parseDuckDuckGoResults(json, "nothing", 5)
        assertTrue(results.isEmpty())
    }

    @Test(expected = WebSearchException::class)
    fun `parseDuckDuckGoResults malformed json throws WebSearchException`() {
        client.parseDuckDuckGoResults("not json at all {{{", "query", 5)
    }

    @Test
    fun `parseDuckDuckGoResults skips nested topic groups without Text`() {
        val json = """
            {
              "AbstractText": "",
              "RelatedTopics": [
                {"Name": "Group", "Topics": []},
                {"Text": "Real topic - detail", "FirstURL": "https://example.com"}
              ]
            }
        """.trimIndent()
        val results = client.parseDuckDuckGoResults(json, "q", 5)
        assertEquals(1, results.size)
        assertEquals("Real topic", results[0].title)
    }

    // ---- Tier 2: Wikipedia search ----

    @Test
    fun `parseWikipediaResults maps hits to title url snippet`() {
        val json = """
            {
              "query": {
                "search": [
                  {
                    "title": "Chennai",
                    "snippet": "officially renamed as <span class=\"searchmatch\">Chennai</span> in 1996.",
                    "pageid": 45139
                  },
                  {
                    "title": "Climate of Tamil Nadu",
                    "snippet": "<span class=\"searchmatch\">Chennai</span> lies on the thermal equator.",
                    "pageid": 37379056
                  }
                ]
              }
            }
        """.trimIndent()

        val results = client.parseWikipediaResults(json, 5)

        assertEquals(2, results.size)
        assertEquals("Chennai", results[0].title)
        assertEquals("https://en.wikipedia.org/wiki/Chennai", results[0].url)
        // Highlight markup must be stripped from the snippet.
        assertEquals("officially renamed as Chennai in 1996.", results[0].snippet)
        assertEquals("Climate of Tamil Nadu", results[1].title)
    }

    @Test
    fun `parseWikipediaResults respects maxResults`() {
        val hits = (1..3).joinToString(",") { i ->
            """{"title": "Hit $i", "snippet": "Snippet $i", "pageid": $i}"""
        }
        val json = """{"query": {"search": [$hits]}}"""

        val results = client.parseWikipediaResults(json, 2)

        assertEquals(2, results.size)
        assertEquals("Hit 1", results[0].title)
    }

    @Test
    fun `parseWikipediaResults empty search yields no results`() {
        val json = """{"query": {"search": []}}"""
        val results = client.parseWikipediaResults(json, 5)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `parseWikipediaResults skips hits with blank snippet`() {
        val json = """
            {"query": {"search": [
              {"title": "No snippet", "snippet": "", "pageid": 1},
              {"title": "Real", "snippet": "A real snippet", "pageid": 2}
            ]}}
        """.trimIndent()
        val results = client.parseWikipediaResults(json, 5)
        assertEquals(1, results.size)
        assertEquals("Real", results[0].title)
    }

    @Test(expected = WebSearchException::class)
    fun `parseWikipediaResults malformed json throws WebSearchException`() {
        client.parseWikipediaResults("not json {{{", 5)
    }
}
