package com.example.tinymodels.models.domain

import com.example.tinymodels.core.database.entities.DownloadedModelEntity
import com.example.tinymodels.models.local.LocalModelsRepository

class DeleteDownloadedModel(private val repository: LocalModelsRepository) {
    suspend operator fun invoke(model: DownloadedModelEntity) = repository.deleteModel(model)
}
