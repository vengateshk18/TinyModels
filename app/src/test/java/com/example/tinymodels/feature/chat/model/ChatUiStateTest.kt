package com.example.tinymodels.feature.chat.model

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
}
