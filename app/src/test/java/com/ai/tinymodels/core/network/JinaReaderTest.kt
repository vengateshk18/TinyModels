package com.ai.tinymodels.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JinaReaderTest {

    @Test fun `builds a jina reader url`() {
        assertEquals(
            "https://r.jina.ai/https://example.com/page",
            JinaReader.urlFor("https://example.com/page")
        )
    }

    @Test fun `cleans metadata headers and image markdown`() {
        val raw = """
            Title: Example
            URL Source: https://example.com
            Markdown Content:
            # Hello

            ![img](https://example.com/a.png)

            Real content here.
        """.trimIndent()
        val cleaned = JinaReader.cleanMarkdown(raw, limit = 1_000)
        assertFalse(cleaned.contains("URL Source"))
        assertFalse(cleaned.contains("![img]"))
        assertTrue(cleaned.contains("Real content here"))
    }

    @Test fun `respects the limit`() {
        val cleaned = JinaReader.cleanMarkdown("word ".repeat(500), limit = 50)
        assertTrue(cleaned.length <= 50)
    }
}
