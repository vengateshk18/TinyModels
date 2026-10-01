package com.example.titymodels.models.data

import com.example.titymodels.utils.Result

class ModelDetailsRepository(
    private val remoteRepository: ModelDetailsRemoteRepository
) {
    suspend fun getModelDetails(modelId: String): Result<ModelDetails> =
        remoteRepository.getModelDetails(modelId)
}
