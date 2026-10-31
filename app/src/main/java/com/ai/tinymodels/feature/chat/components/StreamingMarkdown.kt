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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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

/** How often the typewriter advances the visible text. ~30 fps. */
private const val REVEAL_TICK_MS = 33L

/**
 * Characters to reveal on the next tick for [backlog] unrevealed chars.
 *
 * The generous minimum (2) keeps slow token streams visibly moving, while the
 * backlog-proportional part lets a large native batch (the GGUF engine groups
 * callbacks by ~256 chars) catch up within roughly a second instead of
 * appearing as four lines at once. The cap stops a huge batch from turning
 * back into a single jump.
 */
internal fun revealStep(backlog: Int): Int =
    if (backlog <= 0) 0 else (backlog / 8).coerceIn(2, 10)

/**
 * Clamps a requested reveal offset to [text] and never leaves a high surrogate
 * dangling, which would render as a replacement glyph for one frame.
 */
internal fun safeRevealEnd(text: String, requested: Int): Int {
    val end = requested.coerceIn(0, text.length)
    return if (end in 1 until text.length && text[end - 1].isHighSurrogate()) end - 1 else end
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
    // ── Typewriter reveal ──
    // GGUF callbacks arrive in ~256-char batches (the native engine groups
    // tokens to cut JNI crossings), while LiteRT emits per token. Revealing
    // the buffered text a few chars at a time decouples what the user sees
    // from how the runtime delivers it, so both formats feel like typing.
    var revealedLength by rememberSaveable {
        mutableIntStateOf(if (isStreaming) 0 else content.length)
    }
    val latestContent by rememberUpdatedState(content)
    val latestStreaming by rememberUpdatedState(isStreaming)

    LaunchedEffect(Unit) {
        while (true) {
            val target = latestContent.length
            when {
                // Finished (or history restored): show the final text at once.
                !latestStreaming || revealedLength > target -> {
                    if (revealedLength != target) revealedLength = target
                    delay(120)
                }
                revealedLength < target -> {
                    revealedLength = minOf(
                        revealedLength + revealStep(target - revealedLength),
                        target
                    )
                    delay(REVEAL_TICK_MS)
                }
                else -> delay(50) // caught up, waiting for the next batch
            }
        }
    }

    val visibleEnd = safeRevealEnd(content, revealedLength)
    val displayed = if (visibleEnd >= content.length) content else content.substring(0, visibleEnd)

    // Hold the caret in the source until its fade-out finishes, then drop it.
    // The retained Markdown state keeps the old render visible in between, so
    // removing the marker never blanks the reply.
    var keepCaret by remember { mutableStateOf(isStreaming) }
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            keepCaret = true
        } else {
            // Generation just finished: show the complete reply immediately,
            // then let the caret fade out on top of it.
            revealedLength = content.length
            delay(160)
            keepCaret = false
        }
    }

    val source = remember(displayed, keepCaret) {
        if (keepCaret) buildStreamingMarkdown(displayed) else displayed
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
                text = displayed,
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
