package com.ai.tinymodels.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.ai.tinymodels.R
import com.ai.tinymodels.core.common.AppError
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.common.TinyConstants
import com.ai.tinymodels.core.database.DownloadedModelDao
import com.ai.tinymodels.core.database.ModelFileDao
import com.ai.tinymodels.core.database.entities.DownloadedModelEntity
import com.ai.tinymodels.core.database.entities.ModelFileEntity
import com.ai.tinymodels.core.network.HuggingFaceApi
import com.ai.tinymodels.core.network.NetworkErrorMapper
import com.ai.tinymodels.core.network.dto.ModelDtoParser
import dagger.hilt.android.qualifiers.ApplicationContext
import com.ai.tinymodels.domain.model.DownloadedModel
import com.ai.tinymodels.domain.model.DownloadedModelFile
import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.model.ModelDetails
import com.ai.tinymodels.domain.model.ModelSummary
import com.ai.tinymodels.domain.repository.ModelRepository
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
                errorMapper = NetworkErrorMapper::toAppError
            ) {
                val models = ModelDtoParser.parseModelList(api.listModels(search, pipelineTag, null))
                // Client-side filter: only keep models that actually have runnable
                // LiteRT files — no point showing models we can never run.
                models.filter { it.hasRuntimeFiles }
            }
        }

    override suspend fun getModelDetails(modelId: String): AppResult<ModelDetails> =
        withContext(dispatchers.io) {
            AppResult.runCatching(
                errorMapper = NetworkErrorMapper::toAppError
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

    override suspend fun getLastDownloadedModel(): DownloadedModel? =
        withContext(dispatchers.io) {
            downloadedModelDao.getLastDownloaded()?.toDomain()
        }

    override suspend fun preRegisterModel(model: ModelDetails) =
        withContext(dispatchers.io) {
            // Only pre-register file tracking rows — do NOT insert into downloaded_models yet.
            // A DownloadedModelEntity is only inserted by the worker after a successful download.
            // This prevents the model from appearing in the "downloaded" list prematurely.
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
        error = error,
        isImported = isImported
    )

    // ---- Export / Import ----

    override suspend fun exportModelFile(
        modelId: String,
        fileName: String,
        destinationUri: Uri
    ): AppResult<Uri> =
        withContext(dispatchers.io) {
            AppResult.runCatching(
                errorMapper = { e -> AppError.Unknown(e.message, e) }
            ) {
                val baseName = fileName.substringAfterLast("/")
                val sourceFile = File(
                    context.filesDir,
                    "${TinyConstants.MODEL_DOWNLOAD_PATH_PREFIX}${modelId.replace("/", "_")}/$baseName"
                )
                if (!sourceFile.exists()) {
                    throw IllegalStateException(context.getString(R.string.model_import_file_not_found, baseName))
                }

                val resolver = context.contentResolver
                // Parent = the user-picked folder (tree URI from OpenDocumentTree).
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
                    destinationUri,
                    DocumentsContract.getTreeDocumentId(destinationUri)
                )
                val destUri = DocumentsContract.createDocument(
                    resolver, parentDocUri, TinyConstants.MIME_TYPE_OCTET_STREAM, baseName
                ) ?: throw IllegalStateException(context.getString(R.string.model_import_create_failed, baseName))
                resolver.openOutputStream(destUri)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException(context.getString(R.string.model_import_write_failed, baseName))

                destUri
            }
        }

    override suspend fun importModel(sourceUri: Uri): AppResult<DownloadedModel> =
        withContext(dispatchers.io) {
            AppResult.runCatching(
                errorMapper = { e -> AppError.Unknown(e.message, e) }
            ) {
                val fileName = getFileNameFromUri(sourceUri)
                    ?: throw IllegalStateException(context.getString(R.string.model_import_no_file_name))
                
                if (!fileName.endsWith(TinyConstants.MODEL_FILE_EXTENSION, ignoreCase = true)) {
                    throw IllegalArgumentException(context.getString(R.string.model_import_invalid_file_type))
                }

                // Extract modelId from filename (remove .litertlm)
                val modelId = fileName.removeSuffix(TinyConstants.MODEL_FILE_EXTENSION).replace("_", "/")
                
                // Check if already exists
                val existingModel = downloadedModelDao.getById(modelId)
                val existingFiles = modelFileDao.getByModelId(modelId).map { it.fileName }.toSet()
                
                if (existingModel != null && existingFiles.contains(fileName)) {
                    throw IllegalStateException(context.getString(R.string.model_import_already_exists))
                }

                // Copy to internal storage
                val modelDir = File(context.filesDir, "${TinyConstants.MODEL_DIRECTORY_NAME}/${modelId.replace("/", "_")}")
                modelDir.mkdirs()
                val targetFile = File(modelDir, fileName)

                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException(context.getString(R.string.model_import_open_failed))

                val fileSize = targetFile.length()
                val now = System.currentTimeMillis()

                // Insert or get parent model
                val modelEntity = downloadedModelDao.getById(modelId) ?: DownloadedModelEntity(
                    modelId = modelId,
                    author = "imported",  // library tag for user-imported models
                    libraryName = "litertlm",
                    pipelineTag = "text-generation",
                    localPath = modelDir.absolutePath,
                    downloadedAt = now
                ).also { downloadedModelDao.insertIfAbsent(it) }

                // Insert file record with isImported = true
                modelFileDao.insert(
                    ModelFileEntity(
                        modelId = modelId,
                        fileName = fileName,
                        status = FileDownloadStatus.DOWNLOADED.name,
                        sizeBytes = fileSize,
                        localPath = modelDir.absolutePath,
                        error = null,
                        isImported = true
                    )
                )

                existingModel?.toDomain() ?: modelEntity.toDomain()
            }
        }

    /**
     * Extracts file name from a content URI.
     */
    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                } else {
                    // Fallback: try last path segment
                    uri.lastPathSegment?.takeIf { it.endsWith(TinyConstants.MODEL_FILE_EXTENSION, ignoreCase = true) }
                }
            }
        } catch (e: Exception) {
            uri.lastPathSegment?.takeIf { it.endsWith(TinyConstants.MODEL_FILE_EXTENSION, ignoreCase = true) }
        }
    }
}
