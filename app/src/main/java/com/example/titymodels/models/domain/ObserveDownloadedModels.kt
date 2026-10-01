package com.example.titymodels.models.domain

import com.example.titymodels.models.local.DownloadedModelEntity
import com.example.titymodels.models.local.LocalModelsRepository
import kotlinx.coroutines.flow.Flow

class ObserveDownloadedModels(private val repository: LocalModelsRepository) {
    operator fun invoke(): Flow<List<DownloadedModelEntity>> = repository.observeModels()
}
