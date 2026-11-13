package com.ai.tinymodels.feature.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextChunkerTest {

    @Test
    fun `returns empty when no complete sentence is available`() {
        val chunker = SpeechTextChunker()
        assertEquals(emptyList<String>(), chunker.consume("Hello world"))
    }

    @Test
    fun `extracts completed sentences and keeps trailing fragment`() {
        val chunker = SpeechTextChunker()
        val spoken = chunker.consume("Hello world. How are")
        assertEquals(listOf("Hello world."), spoken)
        assertEquals(listOf("How are you?"), chunker.consume("Hello world. How are you?"))
    }

    @Test
    fun `handles exclamation and question marks`() {
        val chunker = SpeechTextChunker()
        assertEquals(
            listOf("Wow!", "Really?"),
            chunker.consume("Wow! Really? Yes")
        )
    }

    @Test
    fun `flush returns remaining text`() {
        val chunker = SpeechTextChunker()
        chunker.consume("Hello world. Partial")
        assertEquals("Partial", chunker.flush())
        assertEquals(null, chunker.flush())
    }
}