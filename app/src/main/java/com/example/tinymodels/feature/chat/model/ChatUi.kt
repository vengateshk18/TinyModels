package com.example.tinymodels.feature.chat.model

import androidx.compose.runtime.Immutable
import com.example.tinymodels.domain.model.InferenceSettings

/** A message rendered in the chat list. */
@Immutable
data class UiChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val isStreaming: Boolean = false,
    val timestamp: Long = 0L,
    /** Wall-clock time when the assistant finished replying (null for user msgs / in-flight). */
    val completedAt: Long? = null
)

/** State of the model chip shown in the top bar. */
sealed interface ModelChipState {
    data object NotSelected : ModelChipState
    data object Loading : ModelChipState
    data class Ready(
        val modelId: String,
        val backendLabel: String,
        /** The specific model file loaded (e.g. "model.int4.litertlm"), if known. */
        val fileName: String? = null
    ) : ModelChipState
    data class Error(val message: String) : ModelChipState
}

/** Generation lifecycle for the input bar (send vs stop). */
enum class GenerationState { IDLE, GENERATING }

/** Context-window usage shown to the user. */
@Immutable
data class ContextUsage(
    val usedTokens: Int = 0,
    val maxTokens: Int = 2048,
    val nearFull: Boolean = false
)

/** A row in the chat-history drawer. */
@Immutable
data class ChatListItem(
    val id: String,
    val title: String,
    val preview: String?,
    val updatedAt: Long,
    /** The model this chat was last used with (for the history row metadata). */
    val modelId: String? = null,
    /** Total number of messages in this chat. */
    val messageCount: Int = 0
)

/** Transient, user-facing error surfaced via snackbar/banner. */
@Immutable
data class ChatError(val message: String, val actionLabel: String? = null)

/** Model loading progress for the chat screen. */
@Immutable
data class ModelLoadProgress(
    val modelId: String,
    val progress: Float = 0f,
    val stage: LoadStage = LoadStage.INITIALIZING
) {
    enum class LoadStage {
        INITIALIZING,
        LOADING_WEIGHTS,
        READY
    }
}

/** The single, immutable UI state for the chat screen (MVI). */
@Immutable
data class ChatUiState(
    val chats: List<ChatListItem> = emptyList(),
    val activeChatId: String? = null,
    val messages: List<UiChatMessage> = emptyList(),
    val model: ModelChipState = ModelChipState.NotSelected,
    val generation: GenerationState = GenerationState.IDLE,
    /** ID of the assistant message currently being streamed, if any.
     *  Used by [observeMessages] to merge the optimistic placeholder with Room data. */
    val streamingMessageId: String? = null,
    val contextUsage: ContextUsage = ContextUsage(),
    val hasDownloadedModels: Boolean = true,
    val inferenceSettings: InferenceSettings = InferenceSettings(),
    val error: ChatError? = null,
    val modelLoadProgress: ModelLoadProgress? = null,
    /** Whether the inference-settings bottom sheet is open. */
    val showInferenceSettings: Boolean = false,
    /**
     * Digest of older turns that were summarized away to fit the model's
     * context window (context compaction). Non-null only when compaction
     * has occurred for the active chat; shown as a system chip in the list.
     */
    val compactedSummary: String? = null,
    /**
     * The model's TRUE compiled context window, detected from the model file
     * name (null when detection failed). Used as the automatic compaction
     * budget — NOT user-tunable anymore.
     */
    val detectedContextTokens: Int? = null,
    /** How many times compaction has run for the active chat (metric). */
    val compactionCount: Int = 0,
    /** True while a (manual or auto) compaction is running. */
    val isCompacting: Boolean = false,
    /** Result message of the last manual compaction (shown in the metrics sheet). */
    val compactionMessage: String? = null
) {
    val canSend: Boolean
        get() = model is ModelChipState.Ready && generation == GenerationState.IDLE
}

/** One-way events from the UI into the ViewModel (MVI intents). */
sealed interface ChatEvent {
    /** Selects a model — and optionally the exact file within it — to load. */
    data class SelectModel(val modelId: String, val fileName: String? = null) : ChatEvent
    data class SendMessage(val text: String) : ChatEvent
    data object CancelGeneration : ChatEvent
    data object Regenerate : ChatEvent
    data class EditMessage(val messageId: String, val newText: String) : ChatEvent
    data object NewChat : ChatEvent
    data class OpenChat(val chatId: String) : ChatEvent
    data class DeleteChat(val chatId: String) : ChatEvent
    data object DismissError : ChatEvent
    data class UpdateInferenceSettings(val settings: InferenceSettings) : ChatEvent
    data object OpenInferenceSettings : ChatEvent
    data object CloseInferenceSettings : ChatEvent
    /** Manually compact the conversation (from the context metrics sheet). */
    data object CompactConversation : ChatEvent
}
