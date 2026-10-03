package com.example.tinymodels.feature.home.model

import androidx.compose.runtime.Immutable
import com.example.tinymodels.domain.model.DownloadedModel
import com.example.tinymodels.feature.home.DeviceInfoState

/** A model from the curated recommendation catalog (see [RecommendedModels]). */
@Immutable
data class RecommendedModel(
    val tier: String,
    val modelId: String,
    val fileName: String,
    val sizeBytes: Long,
    val displayName: String,
    /** Hugging Face page URL — used for the gated-license "open model page" action. */
    val hfUrl: String
)

/** Aggregated usage stats for the dashboard. */
@Immutable
data class UsageStats(
    val totalChats: Int = 0,
    val totalTokens: Long = 0L,
    val modelsTried: Int = 0
)

/** Storage breakdown for the dashboard. */
@Immutable
data class StorageBreakdown(
    val modelBytes: Long = 0L,
    val freeBytes: Long = 0L
)

/** The last chat session, for the Resume button. */
@Immutable
data class LastChatSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val contextTokens: Int
)

/** One downloaded model, summarized for the dashboard's "Your models" list. */
@Immutable
data class DownloadedModelInfo(
    val modelId: String,
    /** Display name — the repo's short name (after the last '/'). */
    val displayName: String,
    /** Total bytes of downloaded files for this model. */
    val sizeBytes: Long,
    /** Number of downloaded .litertlm files. */
    val fileCount: Int,
    val downloadedAt: Long,
    /** True when this is the last-used model. */
    val isLastUsed: Boolean
)

/**
 * The single, immutable UI state for the Home tab. Branched on whether the
 * user has downloaded at least one model:
 *  - [FirstTime] — onboarding hero + capability verdict + recommended model.
 *  - [Dashboard] — launchpad with last-used model, stats, and storage.
 */
sealed interface HomeUiState {

    /** Device info still loading. */
    data object Loading : HomeUiState

    /** No models downloaded yet — onboarding layout. */
    data class FirstTime(
        val device: DeviceInfoState,
        val recommended: RecommendedModel?,
        val freeStorageBytes: Long
    ) : HomeUiState

    /** At least one model downloaded — dashboard layout. */
    data class Dashboard(
        val device: DeviceInfoState,
        val lastUsedModel: DownloadedModel?,
        /** The specific file of the last-used model, if resolvable. */
        val lastUsedFileName: String?,
        val lastChat: LastChatSummary?,
        val usage: UsageStats,
        val storage: StorageBreakdown,
        /** Average generation speed of the most recent session, if computable. */
        val tokensPerSecond: Float?,
        /** All downloaded models, newest first, for the "Your models" list. */
        val models: List<DownloadedModelInfo> = emptyList()
    ) : HomeUiState
}
