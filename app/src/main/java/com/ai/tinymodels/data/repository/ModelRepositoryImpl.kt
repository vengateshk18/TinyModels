package com.ai.tinymodels.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.ai.tinymodels.core.common.AppError
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.database.DownloadedModelDao
import com.ai.tinymodels.core.database.ModelFileDao
import com.ai.tinymodels.core.database.entities.DownloadedModelEntity
import com.ai.tinymodels.core.database.entities.ModelFileEntity
import com.ai.tinymodels.core.network.HuggingFaceApi
import com.ai.tinymodels.core.network.NetworkErrorMapper
import com.ai.tinymodels.core.network.dto.ModelDtoParser
import dagger.hilt.android.qualifiers.ApplicationContext
import com.ai.tinymodels.domain.model.CatalogPage
import com.ai.tinymodels.domain.model.DownloadedModel
import com.ai.tinymodels.domain.model.DownloadedModelFile
import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.model.MobileFit
import com.ai.tinymodels.domain.model.ModelDetails
import com.ai.tinymodels.domain.model.ModelFilter
import com.ai.tinymodels.domain.model.ModelFormat
import com.ai.tinymodels.domain.model.ModelSummary
import com.ai.tinymodels.domain.model.modelFileDisplayName
import com.ai.tinymodels.domain.repository.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    private val catalogCache = CatalogCache()

    // ---- Remote catalog ----

    override suspend fun listModels(search: String?, filter: ModelFilter, limit: Int): AppResult<CatalogPage> =
        withContext(dispatchers.io) {
            val query = search?.trim().orEmpty()
            val cacheKey = "${filter.name}|${query.lowercase()}|$limit"
            catalogCache.get(cacheKey)?.let { return@withContext AppResult.Success(it) }

            AppResult.runCatching(errorMapper = NetworkErrorMapper::toAppError) {
                val branches = branchesFor(query, filter)

                // Branches run in parallel; one flaky branch is skipped rather
                // than failing the whole catalog.
                val outcomes = coroutineScope {
                    branches.map { branch ->
                        async { runCatchingNonCancellable { fetchBranch(branch, limit) } }
                    }.awaitAll()
                }
                val successes = outcomes.mapNotNull { it.getOrNull() }
                if (successes.isEmpty()) {
                    throw outcomes.first().exceptionOrNull()
                        ?: IllegalStateException("Model catalog unavailable")
                }

                val merged = LinkedHashMap<String, ModelSummary>()
                successes.flatMap { it.models }.forEach { merged.putIfAbsent(it.modelId, it) }

                val page = CatalogPage(
                    models = merged.values
                        .filter { it.hasRuntimeFiles }
                        .filter { model -> filter.format?.let(model::hasFormat) ?: true }
                        .filter { !MobileFit.isTooLargeForMobile(it) }
                        .sortedWith(
                            compareByDescending<ModelSummary> { MobileFit.isMobileFriendly(it) }
                                .thenByDescending { it.downloads ?: 0L }
                        ),
                    hasMore = successes.any { it.hitLimit }
                )
                catalogCache.put(cacheKey, page)
                page
            }
        }

    /** One API query feeding the catalog — a search keyword or an author feed. */
    private data class CatalogBranch(val search: String? = null, val author: String? = null)

    /** Result of one branch query, before merging and filtering. */
    private data class BranchFetch(val models: List<ModelSummary>, val hitLimit: Boolean)

    /**
     * The HF hub has no single "mobile chat models" feed, so browse merges
     * several branches (mirroring how models are found on the web):
     *  - `search=litertlm` — community LiteRT-LM uploads,
     *  - `author=litert-community` — the main publisher of `.litertlm`,
     *  - `search=gguf` — quantized models for llama.cpp-style runtimes.
     * Format chips narrow this down to their own keyword; a typed query
     * replaces the branches entirely.
     */
    private fun branchesFor(query: String, filter: ModelFilter): List<CatalogBranch> {
        if (query.isNotBlank()) return listOf(CatalogBranch(search = query))
        return when (filter.format) {
            null -> listOf(
                CatalogBranch(search = "litertlm"),
                LITERT_COMMUNITY_BRANCH,
                CatalogBranch(search = "gguf")
            )
            ModelFormat.GGUF -> listOf(CatalogBranch(search = "gguf"))
            ModelFormat.LITERTLM -> listOf(CatalogBranch(search = "litertlm"), LITERT_COMMUNITY_BRANCH)
            ModelFormat.TASK -> listOf(CatalogBranch(search = "mediapipe"), LITERT_COMMUNITY_BRANCH)
            ModelFormat.TFLITE -> listOf(CatalogBranch(search = "tflite"), LITERT_COMMUNITY_BRANCH)
        }
    }

    private fun fetchBranch(branch: CatalogBranch, limit: Int): BranchFetch {
        val json = api.listModels(branch.search, CATALOG_PIPELINE_TAG, branch.author, limit)
        val models = ModelDtoParser.parseModelList(json)
        return BranchFetch(models = models, hitLimit = models.size >= limit)
    }

    /** Like [Result.runCatching] but lets coroutine cancellation abort the fetch. */
    private suspend fun <T> runCatchingNonCancellable(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
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
            model.allRunnableFiles.forEach { fileName ->
                val existing = modelFileDao.getByModelIdAndFile(model.id, fileName)
                if (existing == null) {
                    val format = ModelFormat.fromFileName(fileName)
                    modelFileDao.insert(
                        ModelFileEntity(
                            modelId = model.id,
                            fileName = fileName,
                            status = FileDownloadStatus.NOT_DOWNLOADED.name,
                            sizeBytes = 0L,
                            localPath = null,
                            error = null,
                            format = format
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
        isImported = isImported,
        format = format
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
                    "models/${modelId.replace("/", "_")}/$baseName"
                )
                if (!sourceFile.exists()) {
                    throw IllegalStateException("File not found on device: $baseName")
                }

                val resolver = context.contentResolver
                // Parent = the user-picked folder (tree URI from OpenDocumentTree).
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
                    destinationUri,
                    DocumentsContract.getTreeDocumentId(destinationUri)
                )
                val destUri = DocumentsContract.createDocument(
                    resolver, parentDocUri, "application/octet-stream", baseName
                ) ?: throw IllegalStateException("Failed to create destination file: $baseName")
                resolver.openOutputStream(destUri)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Cannot write to destination file: $baseName")

                destUri
            }
        }

    override suspend fun importModel(sourceUri: Uri): AppResult<DownloadedModel> =
        withContext(dispatchers.io) {
            AppResult.runCatching(
                errorMapper = { e -> AppError.Unknown(e.message, e) }
            ) {
                val fileName = getFileNameFromUri(sourceUri)
                    ?: throw IllegalStateException("Cannot determine file name")

                if (!fileName.isModelFile()) {
                    throw IllegalArgumentException(
                        "Unsupported file type. Supported: .litertlm, .task, .tflite, .gguf"
                    )
                }
                val format = ModelFormat.fromFileName(fileName)

                // Extract modelId from filename (remove extension)
                val modelId = fileName.modelFileDisplayName().replace("_", "/")

                // Check if already exists
                val existingModel = downloadedModelDao.getById(modelId)
                val existingFiles = modelFileDao.getByModelId(modelId).map { it.fileName }.toSet()

                if (existingModel != null && existingFiles.contains(fileName)) {
                    throw IllegalStateException("This model file already exists in your library")
                }

                // Copy to internal storage
                val modelDir = File(context.filesDir, "models/${modelId.replace("/", "_")}")
                modelDir.mkdirs()
                val targetFile = File(modelDir, fileName)

                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Cannot open source file")

                val fileSize = targetFile.length()
                val now = System.currentTimeMillis()

                // Insert or get parent model
                val libraryName = when (format) {
                    ModelFormat.TASK -> "mediapipe"
                    ModelFormat.TFLITE -> "tensorflow-lite"
                    ModelFormat.GGUF -> "llama.cpp"
                    else -> "litertlm"
                }
                val pipelineTag = "text-generation"
                val modelEntity = downloadedModelDao.getById(modelId) ?: DownloadedModelEntity(
                    modelId = modelId,
                    author = "imported",
                    libraryName = libraryName,
                    pipelineTag = pipelineTag,
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
                        isImported = true,
                        format = format
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
                    uri.lastPathSegment?.takeIf { it.isModelFile() }
                }
            }
        } catch (e: Exception) {
            uri.lastPathSegment?.takeIf { it.isModelFile() }
        }
    }

    /**
     * Checks if a filename has a supported model file extension.
     */
    private fun String.isModelFile(): Boolean {
        return this.endsWith(".litertlm", ignoreCase = true) ||
            this.endsWith(".task", ignoreCase = true) ||
            this.endsWith(".tflite", ignoreCase = true) ||
            this.endsWith(".gguf", ignoreCase = true)
    }

    private companion object {
        /** Every catalog branch is limited to chat models — this is a chat app. */
        const val CATALOG_PIPELINE_TAG = "text-generation"

        /** The main publisher of ready-to-run LiteRT-LM files. */
        val LITERT_COMMUNITY_BRANCH = CatalogBranch(author = "litert-community")
    }
}
