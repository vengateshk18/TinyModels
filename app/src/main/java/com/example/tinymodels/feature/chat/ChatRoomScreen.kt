package com.example.tinymodels.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tinymodels.core.ui.components.bottomSheetTopSafePadding
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tinymodels.feature.chat.components.ChatInputBar
import com.example.tinymodels.feature.chat.components.EditMessageDialog
import com.example.tinymodels.feature.chat.components.InferenceSettingsSheet
import com.example.tinymodels.feature.chat.components.MessageBubble
import com.example.tinymodels.feature.chat.components.ModelChip
import com.example.tinymodels.feature.chat.components.ModelFilePickerSheet
import com.example.tinymodels.feature.chat.components.ModelLoadingDialog
import com.example.tinymodels.feature.chat.model.ChatEvent
import com.example.tinymodels.feature.chat.model.ChatUiState
import com.example.tinymodels.feature.chat.model.ModelChipState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect as ComposeLaunchedEffect
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.example.tinymodels.feature.chat.model.GenerationState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides

/**
 * Full-screen chat room for a single session (chatId from nav args).
 * Reuses MessageList + ChatInputBar. Scoped ChatViewModel via SavedStateHandle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadedFiles by viewModel.downloadedFiles.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingMessage by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showContextSheet by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it.message, actionLabel = it.actionLabel)
            viewModel.onEvent(ChatEvent.DismissError)
        }
    }

    // Include the IME inset so the Scaffold's content padding shrinks when
    // the keyboard opens — the message list then ends exactly at the input
    // bar and never slides under the keyboard.
    val safeDrawing = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .union(WindowInsets.ime)

    Scaffold(
        contentWindowInsets = safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.chats
                                .firstOrNull { it.id == uiState.activeChatId }?.title
                                ?: "New chat",
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        // Always-tappable model chip — opens the file picker sheet.
                        ModelChip(
                            state = uiState.model,
                            onClick = { showModelPicker = true }
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.model is ModelChipState.Ready) {
                        // Circular context-usage indicator — fills with the
                        // fraction of the model's context window in use.
                        // Tap to open the context metrics sheet (with a
                        // manual Compact action).
                        ContextUsageIcon(
                            usedTokens = uiState.contextUsage.usedTokens,
                            maxTokens = uiState.detectedContextTokens ?: uiState.contextUsage.maxTokens,
                            onClick = { showContextSheet = true }
                        )
                        IconButton(onClick = { viewModel.onEvent(ChatEvent.OpenInferenceSettings) }) {
                            Icon(Icons.Filled.Tune, contentDescription = "Session settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        // NOTE: IME insets are already part of `safeDrawing` (Scaffold's
        // contentWindowInsets), so `padding` shrinks the content when the
        // keyboard opens. No extra imePadding here — that would double-pad
        // and push the input bar up over the app bar.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                when {
                    !uiState.hasDownloadedModels ->
                        ChatRoomEmpty(
                            title = "No models yet",
                            body = "Download a model to start chatting."
                        )
                    uiState.model is ModelChipState.NotSelected ->
                        ChatRoomEmpty(
                            title = "Pick a model",
                            body = "Choose a downloaded model to begin.",
                            actionLabel = "Choose",
                            onAction = { showModelPicker = true }
                        )
                    uiState.messages.isEmpty() ->
                        ChatRoomEmpty(
                            title = "Say hello",
                            body = "Your conversation stays on this device."
                        )
                    else -> MessageListRoom(uiState, viewModel::onEvent) { id, text -> editingMessage = id to text }
                }
            }

            ChatInputBar(
                generation = uiState.generation,
                canSend = uiState.canSend,
                onSend = { viewModel.onEvent(ChatEvent.SendMessage(it)) },
                onStop = { viewModel.onEvent(ChatEvent.CancelGeneration) }
            )
        }
    }

    editingMessage?.let { (messageId, originalText) ->
        EditMessageDialog(
            originalText = originalText,
            onConfirm = { newText ->
                viewModel.onEvent(ChatEvent.EditMessage(messageId, newText))
                editingMessage = null
            },
            onDismiss = { editingMessage = null }
        )
    }

    // Model loading dialog — non-dismissible, removed by ViewModel on completion.
    uiState.modelLoadProgress?.let { progress ->
        ModelLoadingDialog(progress = progress)
    }

    // File-level model picker sheet for selecting a model file mid-chat.
    if (showModelPicker) {
        val activeChip = uiState.model as? ModelChipState.Ready
        ModelFilePickerSheet(
            files = downloadedFiles,
            activeModelId = activeChip?.modelId,
            activeFileName = activeChip?.fileName,
            onSelect = { modelId, fileName ->
                viewModel.onEvent(ChatEvent.SelectModel(modelId, fileName))
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }

    // Context metrics sheet — opened from the circular usage icon.
    if (showContextSheet) {
        ContextMetricsSheet(
            usedTokens = uiState.contextUsage.usedTokens,
            maxTokens = uiState.contextUsage.maxTokens,
            compactionCount = uiState.compactionCount,
            compactedSummary = uiState.compactedSummary,
            isCompacting = uiState.isCompacting,
            compactionMessage = uiState.compactionMessage,
            onCompact = { viewModel.onEvent(ChatEvent.CompactConversation) },
            onDismiss = { showContextSheet = false }
        )
    }

    // Inference settings sheet.
    if (uiState.showInferenceSettings) {
        InferenceSettingsSheet(
            current = uiState.inferenceSettings,
            onSave = {
                viewModel.onEvent(ChatEvent.UpdateInferenceSettings(it))
                viewModel.onEvent(ChatEvent.CloseInferenceSettings)
            },
            onDismiss = { viewModel.onEvent(ChatEvent.CloseInferenceSettings) }
        )
    }
}

@Composable
private fun ChatRoomEmpty(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun MessageListRoom(
    uiState: ChatUiState,
    onEvent: (ChatEvent) -> Unit,
    onEditMessage: (String, String) -> Unit
) {
    var showSummary by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // The list is REVERSED: item 0 renders at the BOTTOM of the viewport and
    // the list grows upward. This is the standard chat layout — the newest
    // message is permanently anchored just above the input bar, on first
    // load and while the keyboard animates open/closed, with no scroll
    // gymnastics required. Older messages scroll up out of view naturally.
    val reversedMessages = remember(uiState.messages) { uiState.messages.asReversed() }

    // With reverseLayout, "scroll to newest" is simply index 0. Only needed
    // when the user has scrolled up and new content arrives.
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.text?.length) {
        if (reversedMessages.isNotEmpty() && listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        // Padding is in VIEWPORT space: `bottom` is next to the input bar.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(reversedMessages, key = { it.id }) { message ->
            MessageBubble(
                message = message,
                onRegenerate = if (!message.isUser && !message.isStreaming) {
                    { onEvent(ChatEvent.Regenerate) }
                } else null,
                onEdit = if (message.isUser && !message.isStreaming) {
                    { text -> onEditMessage(message.id, text) }
                } else null
            )
        }

        // Context-compaction chip — rendered as the LAST item so it appears at
        // the TOP of the reversed list, above the oldest visible message.
        if (uiState.compactedSummary != null) {
            item(key = "compaction-chip") {
                CompactionChip(onClick = { showSummary = true })
            }
        }
    }

    if (showSummary) {
        uiState.compactedSummary?.let { summary ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSummary = false },
                title = { Text("Summarized conversation") },
                text = {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showSummary = false }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}

/** Circular context-usage icon for the top bar. Fills with the used fraction. */
@Composable
private fun ContextUsageIcon(
    usedTokens: Int,
    maxTokens: Int,
    onClick: () -> Unit
) {
    if (maxTokens <= 0) return
    val ratio = (usedTokens.toFloat() / maxTokens).coerceIn(0f, 1f)
    val color = when {
        ratio >= 0.9f -> MaterialTheme.colorScheme.error
        ratio >= 0.7f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(
                progress = { ratio },
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                color = color
            )
            Text(
                text = "${(ratio * 100).toInt()}%",
                fontSize = 8.sp,
                lineHeight = 10.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Bottom sheet with context metrics + a manual Compact action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextMetricsSheet(
    usedTokens: Int,
    maxTokens: Int,
    compactionCount: Int,
    compactedSummary: String?,
    isCompacting: Boolean,
    compactionMessage: String?,
    onCompact: () -> Unit,
    onDismiss: () -> Unit
) {
    val ratio = if (maxTokens > 0) (usedTokens.toFloat() / maxTokens).coerceIn(0f, 1f) else 0f
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0) },
        // Same treatment as the inference-settings sheet: keep the sheet
        // surface below the status bar, and never partially expand (which
        // left the Compact button under the gesture pill).
        modifier = Modifier.bottomSheetTopSafePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .padding(top = 8.dp, bottom = 24.dp)
        ) {
            Text("Context usage", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(20.dp))

            // Big circular gauge.
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier.size(120.dp),
                        strokeWidth = 10.dp,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        color = when {
                            ratio >= 0.9f -> MaterialTheme.colorScheme.error
                            ratio >= 0.7f -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        // Keep the labels clear of the ring's inner edge.
                        modifier = Modifier.padding(horizontal = 20.dp)
                    ) {
                        Text(
                            "${(ratio * 100).toInt()}%",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            "$usedTokens / $maxTokens tokens",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Metrics rows — observability only, no user knobs.
            ContextMetricRow("Tokens in context", "$usedTokens")
            ContextMetricRow("Context budget", "$maxTokens tokens")
            ContextMetricRow(
                "Compactions run",
                "$compactionCount time${if (compactionCount == 1) "" else "s"}"
            )
            ContextMetricRow(
                "Compacted summary",
                if (compactedSummary != null) "Present (${compactedSummary.length} chars)"
                else "None yet"
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Auto-compaction note — its own quiet block, not a cramped row.
            Text(
                "Older messages are summarized automatically when usage reaches 90% of the budget.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Manual compact action.
            androidx.compose.material3.Button(
                onClick = onCompact,
                modifier = Modifier.fillMaxWidth(),
                enabled = usedTokens > 0 && !isCompacting
            ) {
                if (isCompacting) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Compacting…")
                } else {
                    Text("Compact conversation")
                }
            }
            if (compactionMessage != null) {
                Text(
                    compactionMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Text(
                    "Summarizes older messages into a digest to free context space.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ContextMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}


/** Subtle system chip indicating older messages were compacted into a summary. */
@Composable
private fun CompactionChip(onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Text(
            text = "Older messages were summarized to fit the context window",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

