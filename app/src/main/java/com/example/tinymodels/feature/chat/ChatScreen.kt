package com.example.tinymodels.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tinymodels.feature.chat.components.ChatHistoryDrawer
import com.example.tinymodels.feature.chat.components.ChatInputBar
import com.example.tinymodels.feature.chat.components.MessageBubble
import com.example.tinymodels.feature.chat.components.ModelChip
import com.example.tinymodels.feature.chat.components.ModelPickerSheet
import com.example.tinymodels.feature.chat.model.ChatEvent
import com.example.tinymodels.feature.chat.model.ChatUiState
import com.example.tinymodels.feature.chat.model.GenerationState
import com.example.tinymodels.feature.chat.model.ModelChipState
import kotlinx.coroutines.launch

/** Top-level chat destination. Wires the MVI ViewModel to stateless components. */
@Composable
fun ChatScreen(
    onNavigateToModels: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadedModels by viewModel.downloadedModels.collectAsStateWithLifecycle()

    ChatScreenContent(
        uiState = uiState,
        downloadedModels = downloadedModels,
        onEvent = viewModel::onEvent,
        onNavigateToModels = onNavigateToModels,
        onNavigateToSettings = onNavigateToSettings
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreenContent(
    uiState: ChatUiState,
    downloadedModels: List<com.example.tinymodels.domain.model.DownloadedModel>,
    onEvent: (ChatEvent) -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showModelPicker by remember { mutableStateOf(false) }

    // Surface transient errors as snackbars.
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it.message, actionLabel = it.actionLabel)
            onEvent(ChatEvent.DismissError)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                chats = uiState.chats,
                activeChatId = uiState.activeChatId,
                onOpenChat = {
                    onEvent(ChatEvent.OpenChat(it))
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    onEvent(ChatEvent.NewChat)
                    scope.launch { drawerState.close() }
                },
                onDeleteChat = { onEvent(ChatEvent.DeleteChat(it)) }
            )
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        ModelChip(
                            state = uiState.model,
                            onClick = { showModelPicker = true }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open chats")
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        !uiState.hasDownloadedModels ->
                            EmptyState(
                                title = "No models yet",
                                body = "Download a LiteRT-LM model to start chatting offline.",
                                actionLabel = "Browse models",
                                onAction = onNavigateToModels
                            )
                        uiState.model is ModelChipState.NotSelected ->
                            EmptyState(
                                title = "Pick a model",
                                body = "Choose a downloaded model above to begin.",
                                actionLabel = "Choose",
                                onAction = { showModelPicker = true }
                            )
                        uiState.messages.isEmpty() ->
                            EmptyState(
                                title = "Say hello",
                                body = "Your conversation stays on this device."
                            )
                                                else -> MessageList(uiState, onEvent)
                    }
                }

                ChatInputBar(
                    generation = uiState.generation,
                    canSend = uiState.canSend,
                    onSend = { onEvent(ChatEvent.SendMessage(it)) },
                    onStop = { onEvent(ChatEvent.CancelGeneration) }
                )
            }
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            models = downloadedModels,
            activeModelId = (uiState.model as? ModelChipState.Ready)?.modelId,
            onSelect = {
                onEvent(ChatEvent.SelectModel(it))
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }
}

@Composable
private fun MessageList(uiState: ChatUiState, onEvent: (ChatEvent) -> Unit) {
    val listState = rememberLazyListState()
    // Auto-scroll to the latest message when the list grows or the last item updates.
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.text?.length) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // Extra bottom spacing so the last bubble isn't hidden behind the input bar.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
    ) {
                items(uiState.messages, key = { it.id }) { message ->
            MessageBubble(
                message = message,
                onRegenerate = if (!message.isUser && !message.isStreaming) {
                    { onEvent(ChatEvent.Regenerate) }
                } else null
            )
        }
    }
}

@Composable
private fun EmptyState(
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
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (actionLabel != null && onAction != null) {
                androidx.compose.material3.TextButton(
                    onClick = onAction,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
