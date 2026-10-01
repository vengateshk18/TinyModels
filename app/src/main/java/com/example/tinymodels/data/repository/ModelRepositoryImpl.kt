package com.example.tinymodels.data.repository

import com.example.tinymodels.core.common.AppError
import com.example.tinymodels.core.common.AppResult
import com.example.tinymodels.core.common.DispatcherProvider
import com.example.tinymodels.core.database.DownloadedModelDao
import com.example.tinymodels.core.database.entities.DownloadedModelEntity
import com.example.tinymodels.domain.model.DownloadedModel
import com.example.tinymodels.domain.model.ModelDetails
import com.example.tinymodels.domain.model.ModelSummary
import com.example.tinymodels.domain.repository.ModelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-downloads side of [ModelRepository]. The remote-catalog side is provided
 * by the models feature (Slice 5) which owns the network layer; here we expose the
 * Room-backed downloaded models needed by the chat feature.
 */
@Singleton
class ModelRepositoryImpl @Inject constructor(
    private val downloadedModelDao: DownloadedModelDao,
    private val dispatchers: DispatcherProvider
) : ModelRepository {

    override fun observeDownloadedModels(): Flow<List<DownloadedModel>> =
        downloadedModelDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getDownloadedModel(modelId: String): DownloadedModel? =
        withContext(dispatchers.io) { downloadedModelDao.getById(modelId)?.toDomain() }

    override suspend fun deleteDownloadedModel(modelId: String) =
        withContext(dispatchers.io) {
            downloadedModelDao.getById(modelId)?.let { File(it.localPath).deleteRecursively() }
            downloadedModelDao.delete(modelId)
        }

    // Remote catalog implemented in Slice 5 (models feature owns the network layer).
    override suspend fun listModels(): AppResult<List<ModelSummary>> =
        AppResult.Error(AppError.Unknown("Remote catalog not wired yet"))

    override suspend fun getModelDetails(modelId: String): AppResult<ModelDetails> =
        AppResult.Error(AppError.Unknown("Remote catalog not wired yet"))

    private fun DownloadedModelEntity.toDomain() = DownloadedModel(
        modelId = modelId,
        author = author,
        libraryName = libraryName,
        pipelineTag = pipelineTag,
        localPath = localPath,
        files = files.lineSequence().filter { it.isNotBlank() }.toList(),
        sizeBytes = sizeBytes,
        downloadedAt = downloadedAt
    )
}
