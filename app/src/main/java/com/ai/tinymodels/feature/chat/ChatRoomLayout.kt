package com.ai.tinymodels.feature.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ai.tinymodels.core.ui.components.WindowSize

/**
 * Pure sizing rules for the tablet-native chat room (unit-testable, covered
 * by ChatRoomLayoutTest). The composables in [ChatRoomScreen] and
 * [MessageBubble] consume these instead of encoding the numbers inline, so
 * list gutter, composer width and bubble caps can never drift apart silently.
 */
object ChatRoomLayout {

    /**
     * Width of the conversation column tablets: the message list renders
     * centered inside this width and the composer below is capped to it, so
     * both read as one column. On compact windows the gutter clamps to 16dp
     * and [bubbleMaxWidth] keeps the original 300dp phone cap, reproducing
     * the pre-tablet phone layout exactly.
     */
    val CONVERSATION_COLUMN_WIDTH: Dp = 640.dp

    val MIN_GUTTER: Dp = 16.dp

    /**
     * Horizontal gutter that centers a [CONVERSATION_COLUMN_WIDTH] column
     * inside [availableWidth]. Falls back to [MIN_GUTTER] whenever the column
     * does not fit (phone windows) so the phone layout keeps its 16dp.
     */
    fun conversationGutter(availableWidth: Dp): Dp =
        ((availableWidth - CONVERSATION_COLUMN_WIDTH) / 2f).coerceAtLeast(MIN_GUTTER)

    /**
     * Bubble width cap per window-size class. Compact keeps the phone cap;
     * wider windows let bubbles grow so messages stop wrapping into tall
     * narrow strips on the far edges of a tablet screen.Assistant replies
     * carry markdown and code blocks that benefit from the wider read width.
     */
    fun bubbleMaxWidth(windowSize: WindowSize): Dp = when (windowSize) {
        WindowSize.Compact -> 300.dp
        WindowSize.Medium -> 360.dp
        WindowSize.Expanded -> 440.dp
    }
}
