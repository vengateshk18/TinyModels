package com.ai.tinymodels.domain.repository

import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.domain.model.CATALOG_PAGE_SIZE
import com.ai.tinymodels.domain.model.CatalogPage
import com.ai.tinymodels.domain.model.DownloadedModel
import com.ai.tinymodels.domain.model.DownloadedModelFile
import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.model.ModelCollectionDetail
import com.ai.tinymodels.domain.model.ModelDetails
import com.ai.tinymodels.domain.model.ModelFilter
import com.ai.tinymodels.domain.model.ModelSummary
import kotlinx.coroutines.flow.Flow
import android.net.Uri

interface ModelRepository {

    // ---- Remote catalog ----

    /**
     * Searches/browses the mobile-focused catalog.
     *
     * @param search user query; blank browses [filter]'s default branches.
     * @param filter chip selection — controls default branches + format filtering.
     * @param feed ordering/feed (downloads, trending, newest, recently updated).
     * @param tag optional Hub tag filter from model tag taxonomy.
     * @param limit raw models fetched per branch (grows with "load more").
     */
    suspend fun listModels(
        search: String? = null,
        filter: ModelFilter = ModelFilter.ALL,
        feed: com.ai.tinymodels.domain.model.ModelFeed = com.ai.tinymodels.domain.model.ModelFeed.POPULAR,
        tag: String? = null,
        limit: Int = CATALOG_PAGE_SIZE
    ): AppResult<CatalogPage>

    /** Fetch Hub model tag taxonomy grouped by type. */
    suspend fun getModelTagTypes(): AppResult<Map<String, List<String>>>

    /** Fetch file sizes from the model repo tree (path -> bytes) for device-fit checks. */
    suspend fun getModelFileSizes(modelId: String): AppResult<Map<String, Long>>

    suspend fun getModelDetails(modelId: String): AppResult<ModelDetails>

    // ---- Collections ----

    /** Fetch browsable HF collections from curated owners. */
    suspend fun getBrowseCollections(): AppResult<List<com.ai.tinymodels.domain.model.ModelCollection>>

    /** Fetch a curated HF collection and resolve its model items to browsable summaries. */
    suspend fun getCollectionModels(slug: String): AppResult<ModelCollectionDetail>

    // ---- Local downloads (parents) ----
    fun observeDownloadedModels(): Flow<List<DownloadedModel>>
    suspend fun getDownloadedModel(modelId: String): DownloadedModel?
    suspend fun deleteDownloadedModel(modelId: String)

    // ---- Local downloads (per-file children) ----
    fun observeModelFiles(modelId: String): Flow<List<DownloadedModelFile>>
    fun observeDownloadedFiles(): Flow<List<DownloadedModelFile>>

    /** Files stopped by the user, partial bytes kept — the "Paused" group on the downloads screens. */
    fun observePausedFiles(): Flow<List<DownloadedModelFile>>
    suspend fun getModelFile(modelId: String, fileName: String): DownloadedModelFile?
    /** First DOWNLOADED file for a model (used to resolve the file to load for chat). */
    suspend fun getDownloadedFileForModel(modelId: String): DownloadedModelFile?

    /** Returns the most recently downloaded model, ordered by downloadedAt descending. */
    suspend fun getLastDownloadedModel(): DownloadedModel?
    suspend fun preRegisterModel(model: ModelDetails)
    suspend fun updateFileStatus(
        modelId: String,
        fileName: String,
        status: FileDownloadStatus,
        sizeBytes: Long? = null,
        localPath: String? = null,
        error: String? = null
    )
    suspend fun deleteModelFile(modelId: String, fileName: String)

    // ---- Export / Import ----
    /** Export one downloaded file to a user-selected folder (SAF). */
    suspend fun exportModelFile(modelId: String, fileName: String, destinationUri: Uri): AppResult<Uri>
    
    /** Import a model file (.litertlm, .task, .tflite or .gguf) via SAF. */
    suspend fun importModel(
        sourceUri: Uri,
        onProgress: (bytesCopied: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): AppResult<DownloadedModel>
}
