package com.ai.tinymodels.core.network

import com.ai.tinymodels.domain.model.SearchProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchConfigTest {

    @Test
    fun `keyless is always ready`() {
        assertTrue(WebSearchConfig(provider = SearchProvider.KEYLESS).providerReady())
    }

    @Test
    fun `brave without a key is not ready`() {
        assertFalse(WebSearchConfig(provider = SearchProvider.BRAVE, apiKey = "").providerReady())
    }

    @Test
    fun `brave with a key is ready`() {
        assertTrue(WebSearchConfig(provider = SearchProvider.BRAVE, apiKey = "bsa-key").providerReady())
    }

    @Test
    fun `tavily with a whitespace key is not ready`() {
        assertFalse(WebSearchConfig(provider = SearchProvider.TAVILY, apiKey = "   ").providerReady())
    }

    @Test
    fun `tavily with a key is ready`() {
        assertTrue(WebSearchConfig(provider = SearchProvider.TAVILY, apiKey = "tvly-key").providerReady())
    }

    @Test
    fun `searxng requires an http or https url`() {
        assertFalse(WebSearchConfig(provider = SearchProvider.SEARXNG, searxngUrl = "").providerReady())
        assertFalse(WebSearchConfig(provider = SearchProvider.SEARXNG, searxngUrl = "search.example.com").providerReady())
        assertFalse(WebSearchConfig(provider = SearchProvider.SEARXNG, searxngUrl = "ftp://search.example.com").providerReady())
    }

    @Test
    fun `searxng http and https urls are ready`() {
        assertTrue(
            WebSearchConfig(provider = SearchProvider.SEARXNG, searxngUrl = "http://localhost:8080").providerReady()
        )
        assertTrue(
            WebSearchConfig(provider = SearchProvider.SEARXNG, searxngUrl = "https://search.example.com").providerReady()
        )
    }
}
