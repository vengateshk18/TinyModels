package com.example.tinymodels.data.repository

import com.example.tinymodels.core.common.AppError
import com.example.tinymodels.core.common.AppResult
import com.example.tinymodels.core.common.DispatcherProvider
import com.example.tinymodels.core.database.DownloadedModelDao
import com.example.tinymodels.core.database.entities.DownloadedModelEntity
import com.example.tinymodels.core.network.HuggingFaceApi
import com.example.tinymodels.core.network.dto.ModelDtoParser
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

/** Room + HuggingFace-backed implementation of [ModelRepository]. */
@Singleton
class ModelRepositoryImpl @Inject constructor(
    private val downloadedModelDao: DownloadedModelDao,
    private val api: HuggingFaceApi,
    private val dispatchers: DispatcherProvider
) : ModelRepository {

    // ---- Remote catalog ----

    override suspend fun listModels(): AppResult<List<ModelSummary>> =
        withContext(dispatchers.io) {
            AppResult.runCatching(
                errorMapper = { AppError.Network(it.message) }
            ) {
                ModelDtoParser.parseModelList(api.listModels())
            }
        }

    override suspend fun getModelDetails(modelId: String): AppResult<ModelDetails> =
        withContext(dispatchers.io) {
            AppResult.runCatching(
                errorMapper = { AppError.Network(it.message) }
            ) {
                ModelDtoParser.parseDetails(api.getModelDetails(modelId))
            }
        }

    // ---- Local downloads ----

    override fun observeDownloadedModels(): Flow<List<DownloadedModel>> =
        downloadedModelDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getDownloadedModel(modelId: String): DownloadedModel? =
        withContext(dispatchers.io) { downloadedModelDao.getById(modelId)?.toDomain() }

    override suspend fun deleteDownloadedModel(modelId: String) =
        withContext(dispatchers.io) {
            downloadedModelDao.getById(modelId)?.let { File(it.localPath).deleteRecursively() }
            downloadedModelDao.delete(modelId)
        }

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
