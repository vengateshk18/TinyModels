package com.ai.tinymodels.feature.chat

import androidx.compose.ui.unit.dp
import com.ai.tinymodels.core.ui.components.WindowSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the tablet chat-room layout spec ([ChatRoomLayout]): the shared
 * conversation-column constant, the gutter math that collapses back to the
 * phone layout on narrow windows, and the window-aware bubble caps. Pure
 * logic only — the composables are exercised by UI tests (same pattern as
 * RamUsageChartTest).
 */
class ChatRoomLayoutTest {

    @Test fun `list gutter and composer cap share one column constant`() {
        // The message gutter and the composer widthIn are both derived from
        // CONVERSATION_COLUMN_WIDTH; a refactor must never let them drift.
        assertEquals(640.dp, ChatRoomLayout.CONVERSATION_COLUMN_WIDTH)
    }

    @Test fun `gutter centers the column on tablet windows`() {
        val w = ChatRoomLayout.CONVERSATION_COLUMN_WIDTH
        assertEquals(320.dp, ChatRoomLayout.conversationGutter(w * 2f))
        assertEquals(320.dp, ChatRoomLayout.conversationGutter(1280.dp))
        assertEquals(80.dp, ChatRoomLayout.conversationGutter(800.dp))
        assertEquals(40.dp, ChatRoomLayout.conversationGutter(720.dp))
    }

    @Test fun `gutter is symmetric while the column fits`() {
        // Column + 2 gutters must exactly fill the window on tablet widths.
        val available = 920.dp
        val gutter = ChatRoomLayout.conversationGutter(available)
        assertEquals(
            available,
            ChatRoomLayout.CONVERSATION_COLUMN_WIDTH + (gutter * 2f)
        )
    }

    @Test fun `gutter clamps to 16dp when the column does not fit`() {
        // Exactly at the boundary the gutter equals the phone minimum.
        val boundary =
            ChatRoomLayout.CONVERSATION_COLUMN_WIDTH + (ChatRoomLayout.MIN_GUTTER * 2f)
        assertEquals(ChatRoomLayout.MIN_GUTTER, ChatRoomLayout.conversationGutter(boundary))
        // Below it (medium-large phones) and on tiny windows it stays clamped.
        assertEquals(ChatRoomLayout.MIN_GUTTER, ChatRoomLayout.conversationGutter(660.dp))
        assertEquals(ChatRoomLayout.MIN_GUTTER, ChatRoomLayout.conversationGutter(600.dp))
        assertEquals(ChatRoomLayout.MIN_GUTTER, ChatRoomLayout.conversationGutter(0.dp))
    }

    @Test fun `compact windows keep the original 300dp phone bubble cap`() {
        assertEquals(300.dp, ChatRoomLayout.bubbleMaxWidth(WindowSize.Compact))
    }

    @Test fun `bubble caps widen with the window`() {
        val medium = ChatRoomLayout.bubbleMaxWidth(WindowSize.Medium)
        val expanded = ChatRoomLayout.bubbleMaxWidth(WindowSize.Expanded)
        assertEquals(360.dp, medium)
        assertEquals(440.dp, expanded)
        // Tablets must not fall back to the cramped phone strip.
        assertTrue(expanded > medium)
        assertTrue(medium > ChatRoomLayout.bubbleMaxWidth(WindowSize.Compact))
    }
}
