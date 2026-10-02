package com.example.tinymodels.data.repository

import android.content.Context
import com.example.tinymodels.core.common.AppError
import com.example.tinymodels.core.common.AppResult
import com.example.tinymodels.core.common.DispatcherProvider
import com.example.tinymodels.core.database.DownloadedModelDao
import com.example.tinymodels.core.database.ModelFileDao
import com.example.tinymodels.core.database.entities.DownloadedModelEntity
import com.example.tinymodels.core.database.entities.ModelFileEntity
import com.example.tinymodels.core.network.HuggingFaceApi
import com.example.tinymodels.core.network.dto.ModelDtoParser
import dagger.hilt.android.qualifiers.ApplicationContext
import com.example.tinymodels.domain.model.DownloadedModel
import com.example.tinymodels.domain.model.DownloadedModelFile
import com.example.tinymodels.domain.model.FileDownloadStatus
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
    @ApplicationContext private val context: Context,
    private val downloadedModelDao: DownloadedModelDao,
    private val modelFileDao: ModelFileDao,
    private val api: HuggingFaceApi,
    private val dispatchers: DispatcherProvider
) : ModelRepository {

    // ---- Remote catalog ----

    override suspend fun listModels(): AppResult<List<ModelSummary>> =
        listModels(null, null)

    override suspend fun listModels(search: String?, pipelineTag: String?): AppResult<List<ModelSummary>> =
        withContext(dispatchers.io) {
            AppResult.runCatching(
                errorMapper = { AppError.Network(it.message) }
            ) {
                val models = ModelDtoParser.parseModelList(api.listModels(search, pipelineTag, null))
                // Client-side filter: drop models whose siblings are known but contain no runtime files.
                models.filter { it.siblings.isEmpty() || it.hasRuntimeFiles }
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
            modelFileDao.deleteAllForModel(modelId)
            downloadedModelDao.delete(modelId)
        }

    // ---- Local downloads (per-file children) ----

    override fun observeModelFiles(modelId: String): Flow<List<DownloadedModelFile>> =
        modelFileDao.observeByModelId(modelId).map { entities -> entities.map { it.toDomain() } }

    override fun observeDownloadedFiles(): Flow<List<DownloadedModelFile>> =
        modelFileDao.observeDownloaded().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getModelFile(modelId: String, fileName: String): DownloadedModelFile? =
        withContext(dispatchers.io) { modelFileDao.getByModelIdAndFile(modelId, fileName)?.toDomain() }

    override suspend fun getDownloadedFileForModel(modelId: String): DownloadedModelFile? =
        withContext(dispatchers.io) {
            modelFileDao.getFirstDownloadedForModel(modelId)?.toDomain()
        }

    override suspend fun preRegisterModel(model: ModelDetails) =
        withContext(dispatchers.io) {
            val directory = File(context.filesDir, "models/${model.id.replace("/", "_")}")
            val now = System.currentTimeMillis()
            downloadedModelDao.insertIfAbsent(
                DownloadedModelEntity(
                    modelId = model.id,
                    author = model.author,
                    libraryName = model.libraryName,
                    pipelineTag = model.pipelineTag,
                    localPath = directory.absolutePath,
                    downloadedAt = now
                )
            )
            model.runtimeFiles.ifEmpty { model.liteRtFiles }.forEach { fileName ->
                val existing = modelFileDao.getByModelIdAndFile(model.id, fileName)
                if (existing == null) {
                    modelFileDao.insert(
                        ModelFileEntity(
                            modelId = model.id,
                            fileName = fileName,
                            status = FileDownloadStatus.NOT_DOWNLOADED.name,
                            sizeBytes = 0L,
                            localPath = null,
                            error = null
                        )
                    )
                }
            }
        }

    override suspend fun updateFileStatus(
        modelId: String,
        fileName: String,
        status: FileDownloadStatus,
        sizeBytes: Long?,
        localPath: String?,
        error: String?
    ) = withContext(dispatchers.io) {
        modelFileDao.updateStatus(modelId, fileName, status.name, sizeBytes, localPath, error)
    }

    override suspend fun deleteModelFile(modelId: String, fileName: String) =
        withContext(dispatchers.io) { modelFileDao.delete(modelId, fileName) }

    // ---- Mapping ----

    private fun DownloadedModelEntity.toDomain() = DownloadedModel(
        modelId = modelId,
        author = author,
        libraryName = libraryName,
        pipelineTag = pipelineTag,
        localPath = localPath,
        downloadedAt = downloadedAt
    )

    private fun ModelFileEntity.toDomain() = DownloadedModelFile(
        modelId = modelId,
        fileName = fileName,
        status = runCatching { FileDownloadStatus.valueOf(status) }
            .getOrDefault(FileDownloadStatus.NOT_DOWNLOADED),
        sizeBytes = sizeBytes,
        localPath = localPath,
        error = error
    )
}
