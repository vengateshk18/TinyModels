package com.ai.tinymodels.feature.storage

data class DownloadedModelStorageItem(
    val modelId: String,
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val path: String
)

data class PartialDownloadStorageItem(
    val modelId: String?,
    val fileName: String?,
    val displayName: String,
    val detail: String,
    val sizeBytes: Long,
    val path: String
)

data class OrphanFileStorageItem(
    val displayName: String,
    val detail: String,
    val sizeBytes: Long,
    val path: String
)

data class ChatStorageItem(
    val chatId: String,
    val title: String,
    val messageCount: Int
)

data class StorageBreakdown(
    val downloadedModelsBytes: Long = 0L,
    val downloadedModelItems: List<DownloadedModelStorageItem> = emptyList(),
    val partialDownloadBytes: Long = 0L,
    val partialDownloadItems: List<PartialDownloadStorageItem> = emptyList(),
    val orphanFileBytes: Long = 0L,
    val orphanFileItems: List<OrphanFileStorageItem> = emptyList(),
    val databaseBytes: Long = 0L,
    val databaseWalBytes: Long = 0L,
    val chatImageBytes: Long = 0L,
    val chatImageCount: Int = 0,
    val chatItems: List<ChatStorageItem> = emptyList(),
    val messageCount: Int = 0,
    val totalTokensGenerated: Long = 0L,
    val pausedDownloadCount: Int = 0
) {
    val chatCount: Int get() = chatItems.size
    val chatStorageBytes: Long get() = databaseBytes + databaseWalBytes + chatImageBytes
    val totalTrackedBytes: Long get() = downloadedModelsBytes + partialDownloadBytes + orphanFileBytes + chatStorageBytes
}

sealed interface StorageUiState {
    data object Loading : StorageUiState
    data class Success(
        val breakdown: StorageBreakdown,
        val actionInProgress: String? = null,
        val message: String? = null
    ) : StorageUiState
    data class Error(val message: String) : StorageUiState
}
