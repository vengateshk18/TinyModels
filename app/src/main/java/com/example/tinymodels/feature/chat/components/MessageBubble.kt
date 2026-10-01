package com.example.tinymodels.feature.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.tinymodels.feature.chat.model.UiChatMessage
import com.mikepenz.markdown.m3.Markdown
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * A single chat bubble. User messages are right-aligned with primary-container
 * colors; assistant messages are left-aligned and rendered as Markdown (code
 * blocks, lists, bold). Long-press copies the raw text.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: UiChatMessage,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val isUser = message.isUser

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .combinedClickable(
                        onClick = { },
                        onLongClick = {
                            if (message.text.isNotBlank()) {
                                clipboard.setText(AnnotatedString(message.text))
                            }
                        }
                    ),
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 18.dp
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (isUser) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else if (message.text.isBlank() && message.isStreaming) {
                        Text(
                            text = "\u258D",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        SafeMarkdown(content = message.text)
                        if (message.isStreaming) {
                            Text(
                                text = "\u258D",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
        // Timestamp row below the bubble.
        MessageTimestamps(message)
    }
}

/**
 * Compact, muted timestamp row below a bubble.
 * - User message: "HH:mm"
 * - Assistant (complete): "HH:mm • {duration}"
 * - Assistant (streaming): hidden until done.
 */
@Composable
private fun MessageTimestamps(message: UiChatMessage) {
    if (message.timestamp <= 0L) return
    val label = remember(message.id, message.completedAt, message.isStreaming) {
        buildTimestampLabel(message)
    }
    if (label.isBlank()) return
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = if (message.isUser) 0.dp else 4.dp,
            end = if (message.isUser) 4.dp else 0.dp,
            top = 2.dp
        )
    )
}

/** Pure helper — formats sent/completed timestamps into a compact line. */
private fun buildTimestampLabel(message: UiChatMessage): String {
    val sent = formatTime(message.timestamp)
    if (message.isUser) return sent
    if (message.isStreaming) return ""
    val completed = message.completedAt
    return if (completed != null && completed > message.timestamp) {
        "$sent \u2022 ${formatDuration(completed - message.timestamp)}"
    } else {
        sent
    }
}

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))

/** Human-friendly duration: "1.2s", "12s", "1m 05s". */
private fun formatDuration(millis: Long): String {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis)
    return if (seconds < 60) {
        String.format(Locale.US, "%.1fs", millis / 1000.0)
    } else {
        val m = seconds / 60
        val s = seconds % 60
        String.format(Locale.US, "%dm %02ds", m, s)
    }
}

/**
 * Renders markdown safely. During streaming the text may contain unclosed code
 * fences or half-finished lists that can confuse the parser; fall back to plain
 * [Text] in that case so the partial reply is always visible.
 */
@Composable
private fun SafeMarkdown(content: String, modifier: Modifier = Modifier) {
    val canRender = remember(content) { canRenderMarkdown(content) }
    if (canRender) {
        Markdown(content = content, modifier = modifier)
    } else {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier
        )
    }
}

/** Quick heuristic: only fully-render once the text looks structurally complete. */
private fun canRenderMarkdown(text: String): Boolean {
    if (text.isBlank()) return true
    // Unbalanced code fences -> render as plain text to avoid broken blocks.
    val fenceCount = text.split("```").size - 1
    return fenceCount % 2 == 0
}
