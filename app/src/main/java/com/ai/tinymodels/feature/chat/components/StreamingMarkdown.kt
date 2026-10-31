package com.ai.tinymodels.feature.chat.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import kotlinx.coroutines.delay
import org.intellij.markdown.MarkdownTokenTypes
import kotlin.math.abs

/**
 * Private-use marker appended to the streamed source. The Markdown annotator
 * swaps it for an inline caret composable, so the caret lands at the true end
 * of the last line (after lists, emphasis, inline code, ...) instead of being
 * a detached box next to the text.
 */
internal const val STREAMING_CARET_MARKER = "\uE000"

private const val STREAMING_CARET_ID = "tinymodels_streaming_caret"

/**
 * Appends [STREAMING_CARET_MARKER] to the reply being streamed.
 *
 * A fenced code block is skipped: code is rendered outside the annotator, so a
 * marker there would show up as a stray glyph. The caret reappears as soon as
 * the closing fence arrives.
 */
internal fun buildStreamingMarkdown(content: String): String {
    if (content.isEmpty()) return STREAMING_CARET_MARKER
    if (content.endsWith(STREAMING_CARET_MARKER)) return content
    val fences = content.split("```").size - 1
    return if (fences % 2 == 0) content + STREAMING_CARET_MARKER else content
}

/**
 * Renders an assistant reply as live Markdown.
 *
 * [rememberMarkdownState] parses on a background dispatcher and — with
 * `retainState = true` — keeps the previous render on screen while the next
 * token is parsed. That is what makes Markdown appear *while generating*
 * without the bubble blanking or jittering on every token.
 *
 * The caret is a real inline composable, so its breathing animation only
 * recomposes that tiny box, never the whole Markdown tree.
 */
@Composable
fun StreamingMarkdown(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    // Hold the caret in the source until its fade-out finishes, then drop it.
    // The retained Markdown state keeps the old render visible in between, so
    // removing the marker never blanks the reply.
    var keepCaret by remember { mutableStateOf(isStreaming) }
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            keepCaret = true
        } else {
            delay(160)
            keepCaret = false
        }
    }

    val source = remember(content, keepCaret) {
        if (keepCaret) buildStreamingMarkdown(content) else content
    }
    val markdownState = rememberMarkdownState(content = source, retainState = true)
    val accent = MaterialTheme.colorScheme.primary

    val annotator = remember {
        markdownAnnotator(annotate = { text, node ->
            if (node.type != MarkdownTokenTypes.TEXT) {
                false
            } else {
                val value = node.getUnescapedTextInNode(text)
                val isTail = value.endsWith(STREAMING_CARET_MARKER) &&
                    text.substring(node.endOffset).trim().isEmpty()
                if (isTail) {
                    append(value.dropLast(STREAMING_CARET_MARKER.length))
                    appendInlineContent(STREAMING_CARET_ID, "\u200B")
                    true
                } else {
                    false
                }
            }
        })
    }

    val inlineContent = markdownInlineContent(
        remember(accent, isStreaming) {
            mapOf(
                STREAMING_CARET_ID to InlineTextContent(
                    Placeholder(
                        width = 3.sp,
                        height = 18.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                    )
                ) {
                    StreamingCaret(color = accent, active = isStreaming)
                }
            )
        }
    )

    Markdown(
        markdownState = markdownState,
        modifier = modifier.fillMaxWidth(),
        annotator = annotator,
        inlineContent = inlineContent,
        loading = { loadingModifier ->
            // First frame only (a few milliseconds): show the raw text instead
            // of an empty bubble while the initial parse runs.
            Text(
                text = content,
                modifier = loadingModifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    )
}

/**
 * The caret itself: a slim rounded bar with a soft breathing pulse, faded out
 * when generation ends. Only this composable recomposes per frame.
 */
@Composable
private fun StreamingCaret(color: Color, active: Boolean) {
    val transition = rememberInfiniteTransition(label = "streamingCaret")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 480, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "streamingCaretPulse"
    )
    val visibility by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "streamingCaretVisibility"
    )
    Box(
        modifier = Modifier
            .fillMaxHeight(0.82f)
            .fillMaxWidth(0.68f)
            .clip(RoundedCornerShape(1.dp))
            .background(color.copy(alpha = pulse * visibility))
    )
}

/**
 * "Waiting for the first token" indicator: three dots with a smooth pulse
 * travelling left to right (the same feel as Google AI Core / Gemini).
 */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typingIndicator")
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "typingWave"
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            // Each dot trails the previous one by 18% of the cycle.
            val phase = (wave - index * 0.18f + 1f) % 1f
            val pulse = 1f - abs(phase * 2f - 1f)
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f + 0.8f * pulse)
                    )
            )
        }
    }
}
