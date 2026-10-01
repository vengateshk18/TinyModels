package com.example.tinymodels.domain.repository

import com.example.tinymodels.core.common.AppResult
import com.example.tinymodels.domain.model.DownloadedModel
import com.example.tinymodels.domain.model.ModelDetails
import com.example.tinymodels.domain.model.ModelSummary
import kotlinx.coroutines.flow.Flow

interface ModelRepository {

    // ---- Remote catalog ----
    suspend fun listModels(): AppResult<List<ModelSummary>>
    suspend fun getModelDetails(modelId: String): AppResult<ModelDetails>

    // ---- Local downloads ----
    fun observeDownloadedModels(): Flow<List<DownloadedModel>>
    suspend fun getDownloadedModel(modelId: String): DownloadedModel?
    suspend fun deleteDownloadedModel(modelId: String)
}
