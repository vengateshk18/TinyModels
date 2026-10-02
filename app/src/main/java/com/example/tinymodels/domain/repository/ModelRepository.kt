package com.example.tinymodels.domain.repository

import com.example.tinymodels.core.common.AppResult
import com.example.tinymodels.domain.model.DownloadedModel
import com.example.tinymodels.domain.model.DownloadedModelFile
import com.example.tinymodels.domain.model.FileDownloadStatus
import com.example.tinymodels.domain.model.ModelDetails
import com.example.tinymodels.domain.model.ModelSummary
import kotlinx.coroutines.flow.Flow

interface ModelRepository {

    // ---- Remote catalog ----
    suspend fun listModels(): AppResult<List<ModelSummary>>
    suspend fun listModels(search: String?, pipelineTag: String?): AppResult<List<ModelSummary>>
    suspend fun getModelDetails(modelId: String): AppResult<ModelDetails>

    // ---- Local downloads (parents) ----
    fun observeDownloadedModels(): Flow<List<DownloadedModel>>
    suspend fun getDownloadedModel(modelId: String): DownloadedModel?
    suspend fun deleteDownloadedModel(modelId: String)

    // ---- Local downloads (per-file children) ----
    fun observeModelFiles(modelId: String): Flow<List<DownloadedModelFile>>
    fun observeDownloadedFiles(): Flow<List<DownloadedModelFile>>
    suspend fun getModelFile(modelId: String, fileName: String): DownloadedModelFile?
    /** First DOWNLOADED file for a model (used to resolve the file to load for chat). */
    suspend fun getDownloadedFileForModel(modelId: String): DownloadedModelFile?
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
}
