package com.example.tinymodels.feature.chat

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.example.tinymodels.feature.chat.model.GenerationState
import androidx.compose.foundation.layout.fillMaxWidth

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
    }
}

