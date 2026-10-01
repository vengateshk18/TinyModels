package com.example.tinymodels.models.domain

import com.example.tinymodels.models.local.DownloadedModelEntity
import com.example.tinymodels.models.local.LocalModelsRepository
import kotlinx.coroutines.flow.Flow

class ObserveDownloadedModels(private val repository: LocalModelsRepository) {
    operator fun invoke(): Flow<List<DownloadedModelEntity>> = repository.observeModels()
}
