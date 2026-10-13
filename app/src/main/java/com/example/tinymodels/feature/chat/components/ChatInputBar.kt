package com.example.tinymodels.feature.chat.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.tinymodels.feature.chat.model.GenerationState
import com.example.tinymodels.feature.chat.model.PlusMenuItem
import com.example.tinymodels.feature.chat.model.buildPlusMenuItems

/**
 * Message composer — no background rectangle; just the input interface:
 * a rounded text field with a circular send/stop button beside it.
 *
 *  - Multi-line text field (up to 5 lines), transparent border.
 *  - Circular [FilledIconButton] for Send (primary) / Stop (error) so the
 *    action is clearly identifiable.
 *  - Photo attach entry (inside the "+" menu) + thumbnail preview for
 *    multimodal models (Gemma): the photo travels with the message via
 *    [onSend]'s Uri parameter. For text-only models, the entry is shown
 *    disabled with explanatory text.
 *  - Send/Stop cross-fade via AnimatedContent.
 *  - Keyboard "Send" action triggers [onSend].
 *  - IME insets are handled by the parent (ChatRoomScreen Scaffold).
 */
@Composable
fun ChatInputBar(
    generation: GenerationState,
    canSend: Boolean,
    onSend: (String, android.net.Uri?) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    /** Global availability gate for the web_search tool (Settings switch). */
    webSearchAvailable: Boolean = true,
    /** Per-chat opt-in state for the web_search tool. */
    webSearchEnabled: Boolean = false,
    /** Toggle the per-chat web_search tool. */
    onToggleWebSearch: (Boolean) -> Unit = {},
    /** Whether the loaded model has a vision encoder (hides photo entry when false). */
    supportsVision: Boolean = false
) {
    var text by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var pickedImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val isGenerating = generation == GenerationState.GENERATING
    val keyboard = LocalSoftwareKeyboardController.current
    val sendEnabled = canSend && (text.isNotBlank() || pickedImageUri != null) && !isGenerating
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> pickedImageUri = uri }

    fun doSend() {
        val trimmed = text.trim()
        if ((trimmed.isNotEmpty() || pickedImageUri != null) && !isGenerating) {
            onSend(trimmed, pickedImageUri)
            text = ""
            pickedImageUri = null
            keyboard?.hide()
        }
    }

    // NOTE: no navigationBarsPadding/imePadding here — the parent Scaffold's
    // contentWindowInsets already include system bars + IME, so adding them
    // again would double-pad and push this bar over the app bar.
    Column(modifier = modifier.fillMaxWidth()) {
        // Picked-photo preview with a remove button.
        pickedImageUri?.let { uri ->
            PhotoPreviewRow(
                uri = uri,
                onRemove = { pickedImageUri = null }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
        // "+" menu — photo attach + per-chat tool toggles (web search).
        // Contents come from buildPlusMenuItems so the structure and the
        // switch state stay pinned by unit tests (ChatOptionsMenuTest).
        val menuItems = buildPlusMenuItems(
            webSearchAvailable = webSearchAvailable,
            webSearchEnabled = webSearchEnabled,
            canAttachPhoto = canSend && !isGenerating,
            supportsVision = supportsVision
        )
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Chat options",
                    tint = if (webSearchEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                menuItems.forEach { item ->
                    when (item) {
                        is PlusMenuItem.AttachPhoto -> DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Attach photo")
                                    item.disabledReason?.let { reason ->
                                        Text(
                                            reason,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            maxLines = 2,
                                            modifier = Modifier.padding(top = 2.dp, start = 8.dp)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                if (item.enabled) {
                                    pickPhoto.launch("image/*")
                                    menuExpanded = false
                                }
                            },
                            enabled = item.enabled,
                            leadingIcon = {
                                Icon(Icons.Filled.Image, contentDescription = null)
                            }
                        )
                        is PlusMenuItem.WebSearch -> DropdownMenuItem(
                            text = { Text("Web search") },
                            onClick = {
                                onToggleWebSearch(!webSearchEnabled)
                                menuExpanded = false
                            },
                            enabled = item.enabled,
                            leadingIcon = {
                                Icon(Icons.Filled.Search, contentDescription = null)
                            },
                            trailingIcon = {
                                Switch(
                                    checked = item.checked,
                                    onCheckedChange = {
                                        onToggleWebSearch(it)
                                        menuExpanded = false
                                    },
                                    enabled = item.enabled
                                )
                            }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
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
}

/**
 * Thumbnail of the picked photo above the input row, with a remove button.
 * Decoded downsampled from the content URI so the preview stays light.
 */
@Composable
private fun PhotoPreviewRow(
    uri: android.net.Uri,
    onRemove: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap = remember(uri) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val bounds = android.graphics.BitmapFactory.Options()
                    .apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
                android.graphics.BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        } catch (_: Throwable) {
            null
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Photo to send",
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "Photo attached",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove photo",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
