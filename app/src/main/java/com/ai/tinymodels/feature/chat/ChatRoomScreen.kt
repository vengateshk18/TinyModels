package com.ai.tinymodels.feature.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import com.ai.tinymodels.R
import com.ai.tinymodels.core.ui.components.bottomSheetTopSafePadding
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.tinymodels.feature.chat.components.ChatInputBar
import com.ai.tinymodels.feature.chat.components.EditMessageDialog
import com.ai.tinymodels.feature.chat.components.InferenceSettingsSheet
import com.ai.tinymodels.feature.chat.components.MessageBubble
import com.ai.tinymodels.feature.chat.components.ModelChip
import com.ai.tinymodels.feature.chat.components.ModelFilePickerSheet
import com.ai.tinymodels.feature.chat.components.ModelLoadingDialog
import com.ai.tinymodels.feature.chat.model.ChatEvent
import com.ai.tinymodels.feature.chat.model.ChatUiState
import com.ai.tinymodels.feature.chat.model.ModelChipState
import com.ai.tinymodels.feature.chat.voice.SpeechTextChunker
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect as ComposeLaunchedEffect
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.ai.tinymodels.feature.chat.model.GenerationState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import java.util.Locale
import java.util.UUID

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
    val copyScope = rememberCoroutineScope()
    val copiedToClipboardMessage = stringResource(R.string.chat_room_copied_to_clipboard)
    var editingMessage by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showContextSheet by remember { mutableStateOf(false) }

    // Keep the screen awake while generating (Settings → Chat). Flag is
    // cleared on dispose so it never leaks into other screens.
    val holdAwake = uiState.keepScreenOn &&
        uiState.generation == com.ai.tinymodels.feature.chat.model.GenerationState.GENERATING
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(holdAwake) {
        view.keepScreenOn = holdAwake
        onDispose { view.keepScreenOn = false }
    }

    val context = LocalContext.current

    // ---- Voice output (TTS) ----
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    val speechChunker = remember { SpeechTextChunker() }
    var lastSpokenMessageId by remember { mutableStateOf<String?>(null) }
    var lastSpokenTextLength by remember { mutableStateOf(0) }

    // Lazy-init TTS only when voice output is enabled to avoid blocking chat entry.
    LaunchedEffect(uiState.voiceOutputEnabled) {
        if (uiState.voiceOutputEnabled && tts == null) {
            val engine = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    viewModel.onEvent(ChatEvent.SetSpeaking(true))
                }

                override fun onDone(utteranceId: String?) {
                    viewModel.onEvent(ChatEvent.SetSpeaking(false))
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    viewModel.onEvent(ChatEvent.SetSpeaking(false))
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    viewModel.onEvent(ChatEvent.SetSpeaking(false))
                }
            })
            tts = engine
        }
        if (!uiState.voiceOutputEnabled) {
            runCatching { tts?.stop() }
            speechChunker.reset()
            lastSpokenMessageId = null
            lastSpokenTextLength = 0
            viewModel.onEvent(ChatEvent.SetSpeaking(false))
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            viewModel.onEvent(ChatEvent.SetSpeaking(false))
            tts = null
            ttsReady = false
        }
    }

    // Speak assistant replies as they stream/complete.
    LaunchedEffect(
        uiState.voiceOutputEnabled,
        ttsReady,
        uiState.messages,
        uiState.streamingMessageId,
        uiState.generation
    ) {
        val engine = tts
        if (!uiState.voiceOutputEnabled || !ttsReady || engine == null) return@LaunchedEffect

        val last = uiState.messages.lastOrNull() ?: run {
            speechChunker.reset(); lastSpokenMessageId = null; lastSpokenTextLength = 0; return@LaunchedEffect
        }
        // Stop/reset if conversation ended with a user turn (new prompt) or nothing to speak.
        if (last.isUser) {
            runCatching { engine.stop() }
            speechChunker.reset()
            lastSpokenMessageId = null
            lastSpokenTextLength = 0
            viewModel.onEvent(ChatEvent.SetSpeaking(false))
            return@LaunchedEffect
        }

        val assistant = last
        if (assistant.id != lastSpokenMessageId) {
            runCatching { engine.stop() }
            speechChunker.reset()
            lastSpokenMessageId = assistant.id
            lastSpokenTextLength = 0
        }

        val text = assistant.text
        if (text.length < lastSpokenTextLength) {
            runCatching { engine.stop() }
            speechChunker.reset()
            lastSpokenTextLength = 0
        }

        val newChunks = speechChunker.consume(text)
        newChunks.forEach { chunk ->
            val utteranceId = "${assistant.id}-${UUID.randomUUID()}"
            engine.speak(chunk, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }

        val isDoneStreaming = !assistant.isStreaming &&
            (uiState.streamingMessageId == null || uiState.streamingMessageId != assistant.id)
        if (isDoneStreaming) {
            val tail = speechChunker.flush(text)
            if (!tail.isNullOrBlank()) {
                val utteranceId = "${assistant.id}-tail-${UUID.randomUUID()}"
                engine.speak(tail, TextToSpeech.QUEUE_ADD, null, utteranceId)
            }
        }
        lastSpokenTextLength = text.length
    }

    // ---- Voice input (STT), press-and-hold to speak ----
    val speechRecognizerAvailable = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var voiceRmsDb by remember { mutableStateOf(0f) }
    var holdToTalkActive by remember { mutableStateOf(false) }
    var pendingVoiceStart by remember { mutableStateOf(false) }

    fun stopListeningCancel() {
        holdToTalkActive = false
        pendingVoiceStart = false
        voiceRmsDb = 0f
        runCatching { speechRecognizer?.cancel() }
        viewModel.onEvent(ChatEvent.SetListening(false))
    }

    fun stopListeningAndSend() {
        holdToTalkActive = false
        pendingVoiceStart = false
        voiceRmsDb = 0f
        // Finalize recognition so onResults can deliver the transcript.
        runCatching { speechRecognizer?.stopListening() }
    }

    DisposableEffect(context) {
        onDispose {
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
    }

    fun startListeningHold() {
        if (!speechRecognizerAvailable) {
            copyScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_voice_input_unavailable))
            }
            return
        }
        if (!uiState.isModelReady || uiState.generation == GenerationState.GENERATING) return
        if (uiState.isListening) return

        // Stop any ongoing TTS so the mic doesn't pick it up.
        runCatching { tts?.stop() }
        viewModel.onEvent(ChatEvent.SetSpeaking(false))

        holdToTalkActive = true
        voiceRmsDb = 0f

        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { speechRecognizer = it }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                viewModel.onEvent(ChatEvent.SetListening(true))
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                voiceRmsDb = rmsdB
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                // Keep listening state until results/error for hold-to-talk flow.
                voiceRmsDb = 0f
            }

            override fun onError(error: Int) {
                val wasHolding = holdToTalkActive
                holdToTalkActive = false
                voiceRmsDb = 0f
                viewModel.onEvent(ChatEvent.SetListening(false))
                // Don't spam errors for expected no-speech during quick press/release.
                if (wasHolding && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    copyScope.launch { snackbarHostState.showSnackbar(context.getString(R.string.chat_voice_input_error)) }
                } else if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    copyScope.launch { snackbarHostState.showSnackbar(context.getString(R.string.chat_voice_input_error)) }
                }
            }

            override fun onResults(results: Bundle?) {
                holdToTalkActive = false
                voiceRmsDb = 0f
                viewModel.onEvent(ChatEvent.SetListening(false))
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull()?.trim().orEmpty()
                if (transcript.isNotEmpty()) {
                    viewModel.onEvent(ChatEvent.SendMessage(transcript, null))
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        runCatching {
            recognizer.startListening(intent)
            viewModel.onEvent(ChatEvent.SetListening(true))
        }.onFailure {
            holdToTalkActive = false
            voiceRmsDb = 0f
            viewModel.onEvent(ChatEvent.SetListening(false))
            copyScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_voice_input_unavailable))
            }
        }
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingVoiceStart) {
            pendingVoiceStart = false
            startListeningHold()
        } else {
            pendingVoiceStart = false
            if (!granted) copyScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_voice_input_permission_denied))
            }
        }
    }

    val onVoicePressStart: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startListeningHold()
        } else {
            pendingVoiceStart = true
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val onVoicePressRelease: () -> Unit = {
        if (holdToTalkActive || uiState.isListening) {
            stopListeningAndSend()
        } else {
            pendingVoiceStart = false
        }
    }

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
                                ?: stringResource(R.string.chat_new_chat),
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.chat_room_back_content_description))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.onEvent(ChatEvent.ToggleVoiceOutput) }) {
                        Icon(
                            imageVector = if (uiState.voiceOutputEnabled) {
                                Icons.AutoMirrored.Filled.VolumeUp
                            } else {
                                Icons.AutoMirrored.Filled.VolumeOff
                            },
                            contentDescription = stringResource(R.string.chat_voice_output_toggle)
                        )
                    }
                    if (uiState.model is ModelChipState.Ready) {
                        // Circular context-usage indicator — fills with the
                        // fraction of the model's context window in use.
                        // Tap to open the context metrics sheet (with a
                        // manual Compact action).
                        ContextUsageIcon(
                            usedTokens = uiState.contextUsage.usedTokens,
                            maxTokens = uiState.maxContextTokens,
                            onClick = { showContextSheet = true }
                        )
                        IconButton(onClick = { viewModel.onEvent(ChatEvent.OpenInferenceSettings) }) {
                            Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.chat_room_session_settings_content_description))
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
                            title = stringResource(R.string.chat_room_no_models_title),
                            body = stringResource(R.string.chat_room_no_models_body)
                        )
                    uiState.model is ModelChipState.NotSelected ->
                        ChatRoomEmpty(
                            title = stringResource(R.string.chat_room_pick_model_title),
                            body = stringResource(R.string.chat_room_pick_model_body),
                            actionLabel = stringResource(R.string.chat_room_choose),
                            onAction = { showModelPicker = true }
                        )
                    uiState.messages.isEmpty() ->
                        ChatRoomEmpty(
                            title = stringResource(R.string.chat_room_say_hello_title),
                            body = stringResource(R.string.chat_room_say_hello_body)
                        )
                    else -> MessageListRoom(
                        uiState = uiState,
                        onEvent = viewModel::onEvent,
                        onEditMessage = { id, text -> editingMessage = id to text },
                        onCopied = {
                            copyScope.launch { snackbarHostState.showSnackbar(copiedToClipboardMessage) }
                        }
                    )
                }
            }

            ChatInputBar(
                generation = uiState.generation,
                canSend = uiState.canSend,
                isModelReady = uiState.isModelReady,
                onSend = { text, uri -> viewModel.onEvent(ChatEvent.SendMessage(text, uri?.toString())) },
                onStop = { viewModel.onEvent(ChatEvent.CancelGeneration) },
                webSearchAvailable = uiState.webSearchAvailable,
                webSearchEnabled = uiState.webSearchEnabled,
                onToggleWebSearch = { viewModel.onEvent(ChatEvent.ToggleWebSearch(it)) },
                supportsVision = uiState.isVisionModel,
                isListening = uiState.isListening,
                voiceRmsDb = voiceRmsDb,
                onVoicePressStart = onVoicePressStart,
                onVoicePressRelease = onVoicePressRelease
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
            maxTokens = uiState.maxContextTokens,
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
            onDismiss = { viewModel.onEvent(ChatEvent.CloseInferenceSettings) },
            voiceOutputEnabled = uiState.voiceOutputEnabled,
            onVoiceOutputChange = { viewModel.onEvent(ChatEvent.ToggleVoiceOutput) }
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
    onEditMessage: (String, String) -> Unit,
    onCopied: () -> Unit
) {
    var showSummary by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // The list is REVERSED: item 0 renders at the BOTTOM of the viewport and
    // the list grows upward. This is the standard chat layout — the newest
    // message is permanently anchored just above the input bar, on first
    // load and while the keyboard animates open/closed, with no scroll
    // gymnastics required. Older messages scroll up out of view naturally.
    val reversedMessages = remember(uiState.messages) { uiState.messages.asReversed() }

    // With reverseLayout, "scroll to newest" is simply index 0. Follow the
    // stream only while the user is already near the bottom and not actively
    // dragging: an animated scroll fights the user's fling (each new token
    // relaunches the effect and yanks the list back down), while an instant
    // jump applied only near the bottom leaves scrolled-up readers alone.
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.text?.length) {
        if (reversedMessages.isNotEmpty() &&
            !listState.isScrollInProgress &&
            listState.firstVisibleItemIndex <= 2
        ) {
            listState.scrollToItem(0)
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
                } else null,
                onCopied = onCopied,
                showTokenCounts = uiState.showTokenCounts
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
                title = { Text(stringResource(R.string.chat_room_summarized_conversation)) },
                text = {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showSummary = false }) {
                        Text(stringResource(R.string.common_close))
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
                text = stringResource(R.string.common_percent, (ratio * 100).toInt()),
                // "100%" is a character wider than "9%" — step the size down
                // at full usage so the text stays inside the 28dp ring
                // instead of overlapping the stroke.
                fontSize = if (ratio >= 0.995f) 7.sp else 8.sp,
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
            Text(stringResource(R.string.chat_room_context_usage_title), style = MaterialTheme.typography.titleLarge)
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
                    // Ring inner diameter ≈ 100dp; keep text inside it with a
                    // couple dp of slack. Token counts shrink with length
                    // (thousands separators included) so the line can never
                    // touch or cross the ring stroke — big GGUF windows
                    // ("used / 131,072") previously overflowed onto it.
                    val usageText = stringResource(
                        R.string.chat_room_tokens_used_of_total,
                        usedTokens,
                        maxTokens
                    )
                    val usageFontSize = (96f / (0.62f * usageText.length))
                        .coerceIn(9f, 15f).sp
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 14.dp)
                    ) {
                        Text(
                            stringResource(R.string.common_percent, (ratio * 100).toInt()),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Text(
                            usageText,
                            fontSize = usageFontSize,
                            lineHeight = 16.sp,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Metrics rows — observability only, no user knobs.
            ContextMetricRow(stringResource(R.string.chat_room_tokens_in_context), "$usedTokens")
            ContextMetricRow(
                stringResource(R.string.chat_room_context_budget),
                stringResource(R.string.chat_room_tokens_label, maxTokens)
            )
            ContextMetricRow(
                stringResource(R.string.chat_room_compactions_run),
                pluralStringResource(
                    R.plurals.chat_room_compactions_count,
                    compactionCount,
                    compactionCount
                )
            )
            ContextMetricRow(
                stringResource(R.string.chat_room_compacted_summary),
                if (compactedSummary != null) {
                    stringResource(R.string.chat_room_summary_present, compactedSummary.length)
                } else {
                    stringResource(R.string.chat_room_summary_none)
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Auto-compaction note — its own quiet block, not a cramped row.
            Text(
                stringResource(R.string.chat_room_context_explainer, 90),
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
                    Text(stringResource(R.string.chat_room_compacting))
                } else {
                    Text(stringResource(R.string.chat_room_compact_conversation))
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
                    stringResource(R.string.chat_room_compact_hint),
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
            text = stringResource(R.string.chat_room_compacted_notice),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

