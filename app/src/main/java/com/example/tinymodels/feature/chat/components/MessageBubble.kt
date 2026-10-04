package com.example.tinymodels.feature.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
    modifier: Modifier = Modifier,
    onRegenerate: (() -> Unit)? = null,
    onEdit: ((String) -> Unit)? = null
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
                        onClick = {
                            if (isUser && onEdit != null && !message.isStreaming) {
                                onEdit(message.text)
                            }
                        },
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
                        // Awaiting the first token — blinking caret.
                        StreamingCursor()
                    } else {
                        if (message.isStreaming) {
                            // While streaming, use plain Text to avoid the Markdown
                            // renderer's layout glitches (text overlapping itself)
                            // caused by rapid re-parsing on every token emission.
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = message.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                StreamingCursor()
                            }
                        } else {
                            // Once complete, render full Markdown for formatting.
                            SafeMarkdown(
                                content = message.text,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
        // Timestamp + action row below the bubble.
        MessageFooter(message, onRegenerate)
    }
}

/**
 * Compact footer below a bubble: timestamp text + optional regenerate button
 * (shown only for complete assistant messages).
 * - User message: "HH:mm"
 * - Assistant (complete): "HH:mm • {duration}" + [regenerate icon]
 * - Assistant (streaming): hidden until done.
 */
@Composable
private fun MessageFooter(
    message: UiChatMessage,
    onRegenerate: (() -> Unit)?
) {
    if (message.timestamp <= 0L) return
    val showRegen = !message.isUser && !message.isStreaming && onRegenerate != null
    val label = remember(message.id, message.completedAt, message.isStreaming) {
        buildTimestampLabel(message)
    }
    if (label.isBlank() && !showRegen) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
        modifier = Modifier.padding(
            start = if (message.isUser) 0.dp else 4.dp,
            end = if (message.isUser) 4.dp else 0.dp,
            top = 2.dp
        )
    ) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showRegen) {
            if (label.isNotBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
            }
            IconButton(
                onClick = onRegenerate!!,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                                        imageVector = Icons.Filled.Refresh,
                    contentDescription = "Regenerate",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
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
