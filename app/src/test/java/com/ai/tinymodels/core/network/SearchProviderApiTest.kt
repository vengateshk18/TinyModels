package com.ai.tinymodels.core.network

import com.ai.tinymodels.domain.model.SearchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchProviderApiTest {

    private val cfg = WebSearchConfig(apiKey = "test-key")

    @Test fun `brave request uses subscription header and freshness`() {
        val req = SearchProviderApi.request(
            SearchProvider.BRAVE,
            cfg,
            query = "nvidia news",
            maxResults = 5,
            freshness = SearchFreshness.DAY
        )
        assertTrue(req.url.contains("api.search.brave.com/res/v1/web/search"))
        assertTrue(req.url.contains("q=nvidia+news") || req.url.contains("q=nvidia%20news"))
        assertTrue(req.url.contains("count=5"))
        assertTrue(req.url.contains("freshness=pd"))
        assertEquals("test-key", req.headers["X-Subscription-Token"])
    }

    @Test fun `tavily request posts json body with api key and days`() {
        val req = SearchProviderApi.request(
            SearchProvider.TAVILY,
            cfg,
            query = "gold price",
            maxResults = 4,
            freshness = SearchFreshness.WEEK
        )
        assertEquals("POST", req.method)
        assertEquals("https://api.tavily.com/search", req.url)
        assertTrue(req.body!!.contains("\"api_key\":\"test-key\""))
        assertTrue(req.body!!.contains("\"query\":\"gold price\""))
        assertTrue(req.body!!.contains("\"max_results\":4"))
        assertTrue(req.body!!.contains("\"days\":7"))
    }

    @Test fun `searxng request uses base url and json format`() {
        val req = SearchProviderApi.request(
            SearchProvider.SEARXNG,
            cfg.copy(searxngUrl = "https://search.example.com/"),
            query = "rust book",
            maxResults = 3,
            freshness = SearchFreshness.NONE
        )
        assertEquals("GET", req.method)
        assertTrue(req.url.startsWith("https://search.example.com/search?"))
        assertTrue(req.url.contains("format=json"))
        assertTrue(req.url.contains("q=rust+book") || req.url.contains("q=rust%20book"))
    }

    @Test fun `brave parse reads web results`() {
        val body = """
            {"web":{"results":[
              {"title":"Nvidia","url":"https://nvidia.com","description":"GPU maker"},
              {"title":"News","url":"https://example.com/n","description":"Latest <b>news</b>"}
            ]}}
        """.trimIndent()
        val results = SearchProviderApi.parse(SearchProvider.BRAVE, body)
        assertEquals(2, results.size)
        assertEquals("Nvidia", results[0].title)
        assertEquals("Latest news", results[1].snippet)
    }

    @Test fun `tavily parse reads results array`() {
        val body = """{"results":[{"title":"Gold","url":"https://gold.test","content":"Price up"}]}"""
        val results = SearchProviderApi.parse(SearchProvider.TAVILY, body)
        assertEquals(1, results.size)
        assertEquals("Gold", results[0].title)
        assertEquals("Price up", results[0].snippet)
    }

    @Test fun `searxng parse dedupes and skips missing urls`() {
        val body = """{"results":[
          {"title":"A","url":"https://a.test","content":"aa"},
          {"title":"A2","url":"https://a.test","content":"dup"},
          {"title":"B","content":"no url"}
        ]}"""
        val results = SearchProviderApi.parse(SearchProvider.SEARXNG, body)
        assertEquals(1, results.size)
        assertEquals("A", results[0].title)
    }

    @Test fun `malformed json returns empty`() {
        assertTrue(SearchProviderApi.parse(SearchProvider.BRAVE, "nope").isEmpty())
        assertTrue(SearchProviderApi.parse(SearchProvider.TAVILY, "{").isEmpty())
        assertTrue(SearchProviderApi.parse(SearchProvider.SEARXNG, "").isEmpty())
    }
}
