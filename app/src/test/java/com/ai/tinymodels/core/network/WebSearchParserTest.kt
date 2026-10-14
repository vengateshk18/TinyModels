package com.ai.tinymodels.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchParserTest {

    @Test fun `decodeHtmlEntities resolves named numeric and leaves unknown`() {
        assertEquals("& < > ₹ %", WebSearchParser.decodeHtmlEntities("&amp; &lt; &gt; &rupee; &percnt;"))
        assertEquals("A", WebSearchParser.decodeHtmlEntities("&#65;"))
        assertEquals("A", WebSearchParser.decodeHtmlEntities("&#x41;"))
        assertEquals("&foo;", WebSearchParser.decodeHtmlEntities("&foo;"))
    }

    @Test fun `parseDuckDuckGoHtml reads links and snippets by position`() {
        val html = """
            <a class="result__a" href="https://example.com/1">First <b>Title</b></a>
            <a class="result__snippet">first snippet text here</a>
            <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2F2">Second Title</a>
            <td class="result-snippet">second snippet</td>
        """.trimIndent()
        val results = WebSearchParser.parseDuckDuckGoHtml(html)
        assertEquals(2, results.size)
        assertEquals("First Title", results[0].title)
        assertEquals("https://example.com/1", results[0].url)
        assertEquals("first snippet text here", results[0].snippet)
        assertEquals("https://example.com/2", results[1].url)
    }

    @Test fun `parseDuckDuckGoHtml drops ad trackers and dedupes`() {
        val html = """
            <a class="result__a" href="https://duckduckgo.com/l/?q=ad">Ad</a>
            <a class="result__a" href="https://example.com/x">X</a>
            <a class="result__a" href="https://example.com/x">X dup</a>
        """.trimIndent()
        val results = WebSearchParser.parseDuckDuckGoHtml(html)
        assertEquals(1, results.size)
        assertEquals("https://example.com/x", results[0].url)
    }

    @Test fun `parseHeadingResults reads bing shape and skips engine links`() {
        val html = """
            <h2><a href="https://example.com/a">Alpha page</a></h2>
            <p>Alpha snippet about chennai weather details here ok</p>
            <a href="https://www.bing.com/maps">Maps</a>
            <a href="https://example.com/b"><h2>Beta page</h2></a>
            <p>Beta snippet about chennai weather more words here</p>
        """.trimIndent()
        val results = WebSearchParser.parseHeadingResults(html)
        assertEquals(2, results.size)
        assertEquals("Alpha page", results[0].title)
        assertTrue(results[0].snippet.contains("chennai", ignoreCase = true))
    }

    @Test fun `resolveUrl rejects non-web and suffix tricks`() {
        assertNull(WebSearchParser.resolveUrl(""))
        assertNull(WebSearchParser.resolveUrl("javascript:void(0)"))
        // Bare suffix must not unwrap as DDG's own redirect — must not return the inner target.
        val suffix = WebSearchParser.resolveUrl("https://notduckduckgo.com/l/?uddg=https://evil.com")
        assertTrue(suffix != null && suffix != "https://evil.com")
    }

    @Test fun `isChallengePage 202 always blocked, captcha only when empty`() {
        assertTrue(WebSearchParser.isChallengePage(202, "<html>results</html>", hadResults = true))
        assertTrue(WebSearchParser.isChallengePage(200, "verify you are human captcha", hadResults = false))
        assertFalse(WebSearchParser.isChallengePage(200, "verify you are human captcha", hadResults = true))
        assertFalse(WebSearchParser.isChallengePage(200, "<html>no markers</html>", hadResults = false))
    }
}
