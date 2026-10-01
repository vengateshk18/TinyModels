package com.example.tinymodels.feature.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.tinymodels.feature.chat.model.GenerationState

/**
 * Message composer with a polished, modern look.
 *
 * - Surface container with tonal elevation.
 * - Multi-line text field (up to 5 lines) with transparent border + filled bg.
 * - Trailing button cross-fades between Send and Stop depending on [generation].
 * - Keyboard "Send" action triggers [onSend].
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

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 160.dp),
                placeholder = {
                    Text(
                        if (canSend) "Message\u2026" else "Load a model to chat",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                shape = RoundedCornerShape(24.dp),
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
                    FilledIconButton(
                        onClick = onStop,
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop generating")
                    }
                } else {
                    FilledIconButton(
                        onClick = { doSend() },
                        enabled = sendEnabled,
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}
