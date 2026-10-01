package com.example.tinymodels.models.domain

import com.example.tinymodels.models.data.ModelDetails
import com.example.tinymodels.models.data.ModelDetailsRepository
import com.example.tinymodels.utils.Result

class GetModelDetails(
    private val repository: ModelDetailsRepository
) {
    suspend operator fun invoke(modelId: String): Result<ModelDetails> =
        repository.getModelDetails(modelId)
}
