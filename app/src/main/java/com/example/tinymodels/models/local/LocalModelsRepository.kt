package com.example.tinymodels.models.local

import com.example.tinymodels.core.database.DownloadedModelDao
import com.example.tinymodels.core.database.entities.DownloadedModelEntity
import java.io.File
import kotlinx.coroutines.flow.Flow

class LocalModelsRepository(private val dao: DownloadedModelDao) {
    fun observeModels(): Flow<List<DownloadedModelEntity>> = dao.observeAll()

    suspend fun deleteModel(model: DownloadedModelEntity) {
        File(model.localPath).deleteRecursively()
        dao.delete(model.modelId)
    }
}
