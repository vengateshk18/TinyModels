package com.ai.tinymodels.feature.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStateResolverTest {

    @Test
    fun `no downloaded models shows get started state`() {
        val state = WidgetStateResolver.resolve(
            downloadedModelCount = 0,
            lastChatTitle = null
        )

        assertEquals(WidgetDisplayState.GET_STARTED, state.displayState)
    }

    @Test
    fun `downloaded models with no chats shows ready state`() {
        val state = WidgetStateResolver.resolve(
            downloadedModelCount = 2,
            lastChatTitle = null
        )

        assertEquals(WidgetDisplayState.READY, state.displayState)
        assertEquals(2, state.downloadedModelCount)
    }

    @Test
    fun `downloaded models with chat shows continue state`() {
        val state = WidgetStateResolver.resolve(
            downloadedModelCount = 1,
            lastChatTitle = "Trip planning"
        )

        assertEquals(WidgetDisplayState.CONTINUE, state.displayState)
        assertEquals("Trip planning", state.lastChatTitle)
    }
}
