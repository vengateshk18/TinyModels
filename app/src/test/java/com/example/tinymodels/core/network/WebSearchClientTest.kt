package com.example.tinymodels.core.network

import com.example.tinymodels.domain.repository.WebSearchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchClientTest {

    private val client = WebSearchClient()

    @Test
    fun `parseResults returns abstract as top result`() {
        val json = """
            {
              "Heading": "Kotlin (programming language)",
              "AbstractText": "Kotlin is a cross-platform, statically typed programming language.",
              "AbstractURL": "https://en.wikipedia.org/wiki/Kotlin",
              "RelatedTopics": []
            }
        """.trimIndent()

        val results = client.parseResults(json, "kotlin", 5)

        assertEquals(1, results.size)
        assertEquals("Kotlin (programming language)", results[0].title)
        assertEquals("https://en.wikipedia.org/wiki/Kotlin", results[0].url)
        assertTrue(results[0].snippet.contains("cross-platform"))
    }

    @Test
    fun `parseResults includes related topics up to maxResults`() {
        val topics = (1..3).joinToString(",") { i ->
            """{"Text": "Topic $i - Some detail about topic $i", "FirstURL": "https://example.com/$i"}"""
        }
        val json = """{"Heading": "", "AbstractText": "", "RelatedTopics": [$topics]}"""

        val results = client.parseResults(json, "query", 2)

        assertEquals(2, results.size)
        assertEquals("Topic 1", results[0].title)
        assertEquals("https://example.com/1", results[0].url)
    }

    @Test
    fun `parseResults empty response yields no results`() {
        val json = """{"Heading": "", "AbstractText": "", "RelatedTopics": []}"""
        val results = client.parseResults(json, "nothing", 5)
        assertTrue(results.isEmpty())
    }

    @Test(expected = WebSearchException::class)
    fun `parseResults malformed json throws WebSearchException`() {
        client.parseResults("not json at all {{{", "query", 5)
    }

    @Test
    fun `parseResults skips nested topic groups without Text`() {
        val json = """
            {
              "AbstractText": "",
              "RelatedTopics": [
                {"Name": "Group", "Topics": []},
                {"Text": "Real topic - detail", "FirstURL": "https://example.com"}
              ]
            }
        """.trimIndent()
        val results = client.parseResults(json, "q", 5)
        assertEquals(1, results.size)
        assertEquals("Real topic", results[0].title)
    }
}