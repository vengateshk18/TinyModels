package com.ai.tinymodels.feature.storage

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.tinymodels.core.database.ChatDao
import com.ai.tinymodels.core.database.ModelFileDao
import com.ai.tinymodels.core.database.TinyModelsDatabase
import com.ai.tinymodels.core.inference.ModelManager
import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.repository.ChatRepository
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.repository.SettingsRepository
import com.ai.tinymodels.domain.usecase.model.DownloadModelUseCase
import com.ai.tinymodels.domain.usecase.model.DownloadPaths
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class StorageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelFileDao: ModelFileDao,
    private val chatDao: ChatDao,
    private val chatRepository: ChatRepository,
    private val modelRepository: ModelRepository,
    private val modelManager: ModelManager,
    private val downloadModelUseCase: DownloadModelUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow<StorageUiState>(StorageUiState.Loading)
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val previous = _state.value as? StorageUiState.Success
            if (previous == null) _state.value = StorageUiState.Loading
            val result = withContext(Dispatchers.IO) {
                runCatching { computeBreakdown() }
            }
            _state.value = result.fold(
                onSuccess = { StorageUiState.Success(it) },
                onFailure = { StorageUiState.Error(it.message ?: "Failed to load storage details") }
            )
        }
    }

    fun consumeMessage() {
        _state.update { current ->
            if (current is StorageUiState.Success) current.copy(message = null) else current
        }
    }

    fun deleteDownloadedModelFile(modelId: String, fileName: String) {
        runAction("Deleting model file…") {
            withContext(Dispatchers.IO) {
                if (modelManager.loadedModelId == modelId) {
                    modelManager.unloadModel()
                }
                val file = modelFileDao.getByModelIdAndFile(modelId, fileName)
                val dir = file?.localPath
                if (dir != null) {
                    File(dir, fileName.substringAfterLast("/")).delete()
                }
                modelRepository.deleteModelFile(modelId, fileName)
                val hasRemainingDownloaded = modelFileDao.getByModelId(modelId)
                    .any { it.status == FileDownloadStatus.DOWNLOADED.name }
                if (!hasRemainingDownloaded) {
                    modelRepository.deleteDownloadedModel(modelId)
                }
            }
            "Model file deleted"
        }
    }

    fun deletePartialDownload(item: PartialDownloadStorageItem) {
        runAction("Deleting partial download…") {
            withContext(Dispatchers.IO) {
                val file = File(item.path)
                val modelId = item.modelId
                val fileName = item.fileName
                // Best effort: if we can map to a tracked file, cancel/reset DB state.
                if (modelId != null && fileName != null) {
                    runCatching { downloadModelUseCase.cancelFile(modelId, fileName) }
                    runCatching { downloadModelUseCase.discardPartial(modelId, fileName) }
                    modelRepository.updateFileStatus(modelId, fileName, FileDownloadStatus.NOT_DOWNLOADED)
                } else {
                    file.delete()
                }
                // Ensure the .part is gone even if path mapping differed.
                if (file.exists()) file.delete()
            }
            "Partial download deleted"
        }
    }

    fun deleteOrphanFile(path: String) {
        runAction("Deleting orphaned file…") {
            withContext(Dispatchers.IO) { File(path).delete() }
            "Orphaned file deleted"
        }
    }

    fun deleteChat(chatId: String) {
        runAction("Deleting chat…") {
            chatRepository.deleteChat(chatId)
            "Chat deleted"
        }
    }

    fun resetAppData() {
        runAction("Resetting app data…") {
            withContext(Dispatchers.IO) {
                // Unload active model and stop in-flight work best-effort.
                runCatching { modelManager.unloadModel() }
                // Clear chats and chat images
                runCatching { chatRepository.clearAllChats() }
                runCatching { File(context.filesDir, "chat_images").deleteRecursively() }

                // Delete all downloaded models (repo removes dirs/rows)
                val models = modelRepository.observeDownloadedModels().first()
                models.forEach { model ->
                    runCatching { modelRepository.deleteDownloadedModel(model.modelId) }
                }
                // Remove any remaining model storage/partials/orphans
                runCatching { File(context.filesDir, "models").deleteRecursively() }

                // Reset settings to defaults (including onboarding/token/preferences)
                runCatching { settingsRepository.setHuggingFaceToken(null) }
                runCatching { settingsRepository.setOnboardingCompleted(false) }
                runCatching { settingsRepository.setThemeMode(com.ai.tinymodels.domain.model.ThemeMode.SYSTEM) }
                runCatching { settingsRepository.setAppTheme(com.ai.tinymodels.domain.model.AppTheme.DEFAULT) }
                runCatching { settingsRepository.setDynamicColor(true) }
                runCatching { settingsRepository.setFontChoice(com.ai.tinymodels.domain.model.FontChoice.SYSTEM) }
                runCatching { settingsRepository.setFontScale(1.0f) }
                runCatching { settingsRepository.setWebSearchEnabled(false) }
                runCatching { settingsRepository.setAutoLoadLastModel(true) }
                runCatching { settingsRepository.setKeepScreenOn(false) }
                runCatching { settingsRepository.setShowTokenCounts(false) }
                runCatching { settingsRepository.setWifiOnlyDownloads(false) }
                runCatching { settingsRepository.setDefaultBackend(com.ai.tinymodels.domain.model.BackendPreference.AUTO) }
            }
            "App data reset"
        }
    }

    private fun runAction(progress: String, block: suspend () -> String) {
        val current = _state.value as? StorageUiState.Success ?: return
        viewModelScope.launch {
            _state.value = current.copy(actionInProgress = progress, message = null)
            val message = runCatching { block() }
                .getOrElse { "Action failed: ${it.message ?: "unknown error"}" }
            val refreshed = withContext(Dispatchers.IO) { runCatching { computeBreakdown() }.getOrNull() }
            _state.value = if (refreshed != null) {
                StorageUiState.Success(refreshed, actionInProgress = null, message = message)
            } else {
                StorageUiState.Success(current.breakdown, actionInProgress = null, message = message)
            }
        }
    }

    private suspend fun computeBreakdown(): StorageBreakdown {
        val modelsRoot = File(context.filesDir, "models")
        val allModelFiles = modelFileDao.getAll()
        val downloaded = allModelFiles.filter { it.status == FileDownloadStatus.DOWNLOADED.name }
        val pausedTracked = allModelFiles.filter {
            it.status == FileDownloadStatus.PAUSED.name || it.status == FileDownloadStatus.DOWNLOADING.name
        }

        val downloadedItems = downloaded.mapNotNull { entity ->
            val dir = entity.localPath ?: return@mapNotNull null
            val file = File(dir, entity.fileName.substringAfterLast("/"))
            if (!file.isFile) return@mapNotNull null
            DownloadedModelStorageItem(
                modelId = entity.modelId,
                fileName = entity.fileName,
                displayName = entity.fileName.substringAfterLast("/"),
                sizeBytes = file.length(),
                path = file.absolutePath
            )
        }
        val trackedPaths = downloadedItems.map { it.path }.toSet()

        // Map known part-file paths to tracked model/file entries for proper discard/reset.
        val partPathToTracked = pausedTracked.associateBy { entity ->
            DownloadPaths.partFile(context.filesDir, entity.modelId, entity.fileName).absolutePath
        }
        val partFiles = StorageCalculator.findPartFiles(modelsRoot)
        val partialItems = partFiles.map { file ->
            val tracked = partPathToTracked[file.absolutePath]
            PartialDownloadStorageItem(
                modelId = tracked?.modelId,
                fileName = tracked?.fileName,
                displayName = file.name.removeSuffix(".part"),
                detail = tracked?.modelId ?: (file.parentFile?.name ?: "models"),
                sizeBytes = file.length(),
                path = file.absolutePath
            )
        }

        val orphanFiles = StorageCalculator.findOrphanModelFiles(modelsRoot, trackedPaths)
        val orphanItems = orphanFiles.map { file ->
            OrphanFileStorageItem(
                displayName = file.name,
                detail = file.parentFile?.name ?: "models",
                sizeBytes = file.length(),
                path = file.absolutePath
            )
        }

        val dbFile = context.getDatabasePath(TinyModelsDatabase.NAME)
        val dbBytes = if (dbFile.exists()) dbFile.length() else 0L
        val walBytes = File(dbFile.path + "-wal").let { if (it.exists()) it.length() else 0L } +
            File(dbFile.path + "-shm").let { if (it.exists()) it.length() else 0L }

        val chatImagesDir = File(context.filesDir, "chat_images")
        val chatImageBytes = StorageCalculator.directorySize(chatImagesDir)
        val chatImageCount = if (chatImagesDir.isDirectory) {
            chatImagesDir.listFiles()?.count { it.isFile } ?: 0
        } else 0

        val chatItems = chatDao.observeChatSummaries().first().map {
            ChatStorageItem(chatId = it.id, title = it.title.ifBlank { "Untitled chat" }, messageCount = it.messageCount)
        }

        return StorageBreakdown(
            downloadedModelsBytes = downloadedItems.sumOf { it.sizeBytes },
            downloadedModelItems = downloadedItems.sortedByDescending { it.sizeBytes },
            partialDownloadBytes = partialItems.sumOf { it.sizeBytes },
            partialDownloadItems = partialItems.sortedByDescending { it.sizeBytes },
            orphanFileBytes = orphanItems.sumOf { it.sizeBytes },
            orphanFileItems = orphanItems.sortedByDescending { it.sizeBytes },
            databaseBytes = dbBytes,
            databaseWalBytes = walBytes,
            chatImageBytes = chatImageBytes,
            chatImageCount = chatImageCount,
            chatItems = chatItems,
            messageCount = chatDao.totalMessages(),
            totalTokensGenerated = chatDao.totalTokensGenerated(),
            pausedDownloadCount = pausedTracked.size
        )
    }
}
