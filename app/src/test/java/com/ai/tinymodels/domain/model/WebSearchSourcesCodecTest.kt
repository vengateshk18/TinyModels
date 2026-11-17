package com.ai.tinymodels.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchSourcesCodecTest {

    @Test
    fun `round-trips sources`() {
        val sources = listOf(
            WebSearchResult(
                title = "Chennai weather",
                url = "https://example.com/weather",
                snippet = "32°C, humid",
                query = "weather in chennai today"
            ),
            WebSearchResult(
                title = "Forecast",
                url = "https://www.weather.test/chennai",
                snippet = "Rain later",
                query = "weather in chennai today"
            )
        )

        val json = WebSearchSourcesCodec.toJson(sources)
        val decoded = WebSearchSourcesCodec.fromJson(json)

        assertEquals(sources, decoded)
    }

    @Test
    fun `empty and null decode to empty list`() {
        assertTrue(WebSearchSourcesCodec.fromJson(null).isEmpty())
        assertTrue(WebSearchSourcesCodec.fromJson("").isEmpty())
        assertTrue(WebSearchSourcesCodec.fromJson("[]").isEmpty())
    }

    @Test
    fun `escapes special characters`() {
        val sources = listOf(
            WebSearchResult(
                title = "A \"quoted\" title\nline",
                url = "https://example.com/a?b=c&d=e",
                snippet = "back\\slash and	tab",
                query = "q \"x\""
            )
        )

        val decoded = WebSearchSourcesCodec.fromJson(WebSearchSourcesCodec.toJson(sources))
        assertEquals(sources, decoded)
    }
}
