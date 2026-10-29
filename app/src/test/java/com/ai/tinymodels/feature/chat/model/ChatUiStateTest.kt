package com.ai.tinymodels.feature.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the composer's send/edit gating: the text field stays editable
 * ([isModelReady]) while streaming, but sending ([canSend]) still requires
 * an idle generation.
 */
class ChatUiStateTest {

    private val ready = ModelChipState.Ready("m", "CPU")

    @Test
    fun `idle with model can send and edit`() {
        val state = ChatUiState(model = ready, generation = GenerationState.IDLE)
        assertTrue(state.canSend)
        assertTrue(state.isModelReady)
    }

    @Test
    fun `generating blocks send but keeps field editable`() {
        val state = ChatUiState(model = ready, generation = GenerationState.GENERATING)
        assertFalse(state.canSend)
        assertTrue(state.isModelReady)
    }

    @Test
    fun `no model blocks both`() {
        val state = ChatUiState(model = ModelChipState.NotSelected)
        assertFalse(state.canSend)
        assertFalse(state.isModelReady)
    }

    @Test
    fun `maxContextTokens prefers detected window over the default`() {
        // A GGUF model reports its real window (e.g. 8192) — the sheet and the
        // ring must show that, not ContextUsage's 2048 UI default.
        val state = ChatUiState(
            model = ready,
            detectedContextTokens = 8192,
            contextUsage = ContextUsage(maxTokens = 2048)
        )
        assertEquals(8192, state.maxContextTokens)
    }

    @Test
    fun `maxContextTokens falls back to context usage when undetected`() {
        val state = ChatUiState(
            model = ready,
            detectedContextTokens = null,
            contextUsage = ContextUsage(maxTokens = 4096)
        )
        assertEquals(4096, state.maxContextTokens)
    }
}
