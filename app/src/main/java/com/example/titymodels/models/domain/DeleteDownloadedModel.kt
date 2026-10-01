package com.example.titymodels.models.domain

import com.example.titymodels.models.local.DownloadedModelEntity
import com.example.titymodels.models.local.LocalModelsRepository

class DeleteDownloadedModel(private val repository: LocalModelsRepository) {
    suspend operator fun invoke(model: DownloadedModelEntity) = repository.deleteModel(model)
}
