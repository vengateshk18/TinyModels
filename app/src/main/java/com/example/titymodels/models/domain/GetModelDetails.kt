package com.example.titymodels.models.domain

import com.example.titymodels.models.data.ModelDetails
import com.example.titymodels.models.data.ModelDetailsRepository
import com.example.titymodels.utils.Result

class GetModelDetails(
    private val repository: ModelDetailsRepository
) {
    suspend operator fun invoke(modelId: String): Result<ModelDetails> =
        repository.getModelDetails(modelId)
}
