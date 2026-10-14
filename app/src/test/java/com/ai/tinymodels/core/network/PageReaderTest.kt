package com.ai.tinymodels.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageReaderTest {

    @Test fun `isReadablePage blocks app shells`() {
        assertFalse(PageReader.isReadablePage("https://www.youtube.com/watch?v=x"))
        assertFalse(PageReader.isReadablePage("https://x.com/user/status/1"))
        assertFalse(PageReader.isReadablePage("https://reddit.com/r/x"))
        assertTrue(PageReader.isReadablePage("https://example.com/article"))
    }

    @Test fun `extractReadableText prefers query paragraphs over nav`() {
        val html = """
            <nav>Skip to main content Home Menu</nav>
            <p>You're all set to receive the latest tips, news, offers, and more from Android phones everywhere now</p>
            <h2>Android 15 released</h2>
            <p>Android 15 is the current version released in October with new privacy features for android devices</p>
        """.trimIndent()
        val text = PageReader.extractReadableText(html, query = "current android version")
        assertTrue(text.contains("Android 15", ignoreCase = true))
    }

    @Test fun `extractReadableText strips scripts and caps length`() {
        val html = "<script>var x = {a:1}; ytcfg.set({});</script><p>" + "word ".repeat(500) + "</p>"
        val text = PageReader.extractReadableText(html, limit = 200)
        assertFalse(text.contains("ytcfg"))
        assertTrue(text.length <= 201)
    }

    @Test fun `extractReadableText empty on machinery only`() {
        val html = "<script>var a=[{x:1}];</script>"
        assertEquals("", PageReader.extractReadableText(html))
    }

    @Test fun `wantsData true for weather price score`() {
        assertTrue(PageReader.wantsData("weather in Chennai today"))
        assertTrue(PageReader.wantsData("gold price chennai"))
        assertTrue(PageReader.wantsData("india vs australia score"))
        assertFalse(PageReader.wantsData("who is the CEO of Nvidia"))
    }

    @Test fun `extractReadableText harvests table cells and stat blocks for data queries`() {
        val html = """
            <div class="header">Chennai Weather Nav Home Menu 24</div>
            <p>Stay informed and make the most of your day with our live weather updates from Chennai today for your trip.</p>
            <div class="stat">Feels like 35 ° · Low 28 °</div>
            <div class="stat">25 km/h Wind</div>
            <div class="stat">68% Humidity</div>
            <table><tr><td>28.5 °</td><td>28.4 °</td><td>28.3 °</td><td>29.2 °</td></tr></table>
            <p>The temperature today in Chennai is expected to reach 31 ° C in the afternoon hours today.</p>
        """.trimIndent()
        val text = PageReader.extractReadableText(html, query = "weather in Chennai today")
        assertTrue(text.contains("35", ignoreCase = true))
        assertTrue(text.contains("68%", ignoreCase = true))
        assertTrue(text.contains("25 km/h", ignoreCase = true))
        assertTrue(text.contains("28.5", ignoreCase = true))
        // Numbers lead, prose follows.
        assertTrue(text.indexOf("35") < text.indexOf("Stay informed"))
    }

    @Test fun `extractReadableText ignores tables for non-data queries`() {
        val html = "<p>" + "word ".repeat(30) + "</p><table><tr><td>28.5 °</td><td>28.4 °</td><td>28.3 °</td></tr></table>"
        val text = PageReader.extractReadableText(html, query = "who is the CEO of Nvidia")
        assertFalse(text.contains("28.5"))
    }

    @Test fun `extractReadableText joins split stat-value and stat-label divs`() {
        val html = """
            <div class="stat-card"><div class="stat-icon">x</div>
            <div class="stat-value">68%</div> <div class="stat-label">Humidity</div></div>
            <div class="stat-card"><div class="stat-value"><span>25</span> <span>km/h</span></div> <div class="stat-label">Wind</div></div>
        """.trimIndent()
        val text = PageReader.extractReadableText(html, query = "weather in Chennai today")
        assertTrue(text.contains("68% Humidity"))
        assertTrue(text.contains("25 km/h Wind"))
    }
}
