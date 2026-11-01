package com.ai.tinymodels.core.inference.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class VlmPromptMarkerTest {

    @Test
    fun `prepends the projector marker to the image turn`() {
        assertEquals(
            "<__media__>\nWhat is in this photo?",
            VlmPromptMarker.apply("What is in this photo?", "<__media__>", 1)
        )
    }

    @Test
    fun `blank text yields the marker alone`() {
        assertEquals("<__media__>", VlmPromptMarker.apply("", "<__media__>", 1))
    }

    @Test
    fun `one marker per image keeps mtmd pairing aligned`() {
        assertEquals(
            "<__media__>\n<__media__>\nCompare these",
            VlmPromptMarker.apply("Compare these", "<__media__>", 2)
        )
    }

    @Test
    fun `text-only turns are untouched`() {
        assertEquals("Hello", VlmPromptMarker.apply("Hello", "<__media__>", 0))
        assertEquals("Hello", VlmPromptMarker.apply("Hello", null, 1))
        assertEquals("Hello", VlmPromptMarker.apply("Hello", "  ", 1))
    }
}
