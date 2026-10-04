package com.example.tinymodels.feature.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.tinymodels.feature.chat.model.GenerationState

/**
 * Message composer — no background rectangle; just the input interface:
 * a rounded text field with a circular send/stop button beside it.
 *
 *  - Multi-line text field (up to 5 lines), transparent border.
 *  - Circular [FilledIconButton] for Send (primary) / Stop (error) so the
 *    action is clearly identifiable.
 *  - Send/Stop cross-fade via AnimatedContent.
 *  - Keyboard "Send" action triggers [onSend].
 *  - IME insets are handled by the parent (ChatRoomScreen Scaffold).
 */
@Composable
fun ChatInputBar(
    generation: GenerationState,
    canSend: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    val isGenerating = generation == GenerationState.GENERATING
    val keyboard = LocalSoftwareKeyboardController.current
    val sendEnabled = canSend && text.isNotBlank() && !isGenerating

    fun doSend() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty() && !isGenerating) {
            onSend(trimmed)
            text = ""
            keyboard?.hide()
        }
    }

    // NOTE: no navigationBarsPadding/imePadding here — the parent Scaffold's
    // contentWindowInsets already include system bars + IME, so adding them
    // again would double-pad and push this bar over the app bar.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp, max = 160.dp),
            placeholder = {
                Text(
                    if (canSend) "Message\u2026" else "Load a model to chat",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            shape = RoundedCornerShape(26.dp),
            maxLines = 5,
            enabled = canSend,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { doSend() })
        )
        Spacer(modifier = Modifier.width(8.dp))
        AnimatedContent(
            targetState = isGenerating,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "sendStop"
        ) { generating ->
            if (generating) {
                // Circular stop button — clearly identifiable while generating.
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop generating")
                }
            } else {
                // Circular send button with a filled background circle.
                FilledIconButton(
                    onClick = { doSend() },
                    enabled = sendEnabled,
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (sendEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (sendEnabled) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/**
 * A proper blinking caret shown while the assistant streams a reply.
 * A slim rounded bar that fades in/out — replaces the old block character.
 */
@Composable
fun StreamingCursor(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "cursorBlink")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    Box(
        modifier = modifier
            .size(width = 3.dp, height = 16.dp)
            .alpha(alpha)
            .background(color = color, shape = RoundedCornerShape(2.dp))
    )
}
