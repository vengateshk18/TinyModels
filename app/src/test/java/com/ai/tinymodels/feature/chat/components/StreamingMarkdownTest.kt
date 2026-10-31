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

    @Test
    fun `reveal step is zero when caught up`() {
        assertEquals(0, revealStep(0))
        assertEquals(0, revealStep(-3))
    }

    @Test
    fun `reveal step stays gentle for small token arrivals`() {
        assertEquals(2, revealStep(1))
        assertEquals(2, revealStep(8))
    }

    @Test
    fun `reveal step grows with a large native batch but stays capped`() {
        assertTrue(revealStep(80) > revealStep(20))
        assertEquals(10, revealStep(1_000))
    }

    @Test
    fun `safe reveal end clamps to the text length`() {
        assertEquals(0, safeRevealEnd("hello", -2))
        assertEquals(5, safeRevealEnd("hello", 99))
    }

    @Test
    fun `safe reveal end never splits a surrogate pair`() {
        val emoji = "a\uD83D\uDE00b" // a, 😀, b
        // Offset 2 would land between the two halves of the emoji.
        assertEquals(1, safeRevealEnd(emoji, 2))
        assertEquals(3, safeRevealEnd(emoji, 3))
        assertEquals(4, safeRevealEnd(emoji, 4))
    }
}
