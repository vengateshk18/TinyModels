package com.ai.tinymodels.feature.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownTest {

    @Test
    fun `appends the caret marker to normal content`() {
        val result = buildStreamingMarkdown("Hello **world**")
        assertEquals("Hello **world**$STREAMING_CARET_MARKER", result)
        assertTrue(result.endsWith(STREAMING_CARET_MARKER))
    }

    @Test
    fun `empty content renders the caret only`() {
        assertEquals(STREAMING_CARET_MARKER, buildStreamingMarkdown(""))
    }

    @Test
    fun `keeps the caret at the end after trailing newlines`() {
        val result = buildStreamingMarkdown("line one\nline two\n")
        assertEquals("line one\nline two\n$STREAMING_CARET_MARKER", result)
    }

    @Test
    fun `does not add a caret while a code fence is open`() {
        val open = "```kotlin\nval x = 1"
        assertEquals(open, buildStreamingMarkdown(open))
        assertFalse(open.let(::buildStreamingMarkdown).endsWith(STREAMING_CARET_MARKER))
    }

    @Test
    fun `adds the caret once the code fence closes`() {
        val closed = "```kotlin\nval x = 1\n```"
        assertEquals(closed + STREAMING_CARET_MARKER, buildStreamingMarkdown(closed))
    }

    @Test
    fun `is idempotent when the marker is already present`() {
        val once = buildStreamingMarkdown("streamed text")
        assertEquals(once, buildStreamingMarkdown(once))
    }
}
