package com.example.titymodels.models.local

import java.io.File
import kotlinx.coroutines.flow.Flow

class LocalModelsRepository(private val dao: DownloadedModelDao) {
    fun observeModels(): Flow<List<DownloadedModelEntity>> = dao.observeAll()

    suspend fun deleteModel(model: DownloadedModelEntity) {
        File(model.localPath).deleteRecursively()
        dao.delete(model.modelId)
    }
}
