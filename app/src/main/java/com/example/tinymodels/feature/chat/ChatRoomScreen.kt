package com.example.tinymodels.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.example.tinymodels.feature.chat.model.ChatEvent
import com.example.tinymodels.feature.chat.model.ChatUiState
import com.example.tinymodels.feature.chat.model.ModelChipState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect as ComposeLaunchedEffect
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
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
    val downloadedModels by viewModel.downloadedModels.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingMessage by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showInferenceSheet by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it.message, actionLabel = it.actionLabel)
            viewModel.onEvent(ChatEvent.DismissError)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.chats.firstOrNull()?.title ?: "Chat",
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
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
                            title = "Loading model...",
                            body = "Preparing the model for this chat."
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


    if (showInferenceSheet) {
        InferenceSettingsSheet(
            current = uiState.inferenceSettings,
            onSave = {
                viewModel.onEvent(ChatEvent.UpdateInferenceSettings(it))
                showInferenceSheet = false
            },
            onDismiss = { showInferenceSheet = false }
        )
    }
}

@Composable
private fun ChatRoomEmpty(title: String, body: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.text?.length) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(uiState.messages, key = { it.id }) { message ->
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

