package com.example.tinymodels.models.data

import com.example.tinymodels.utils.Result

class ModelDetailsRepository(
    private val remoteRepository: ModelDetailsRemoteRepository
) {
    suspend fun getModelDetails(modelId: String): Result<ModelDetails> =
        remoteRepository.getModelDetails(modelId)
}
