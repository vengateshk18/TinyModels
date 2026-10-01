package com.example.tinymodels.utils

import com.example.tinymodels.models.data.ModelsRepository
import com.example.tinymodels.models.data.ModelDetailsRemoteRepository
import com.example.tinymodels.models.data.ModelDetailsRepository
import com.example.tinymodels.models.local.LocalModelsRepository
import com.example.tinymodels.core.database.TinyModelsDatabase
import com.example.tinymodels.models.local.ModelDownloadRepository
import com.example.tinymodels.models.domain.GetModelDetails
import com.example.tinymodels.models.domain.ObserveDownloadedModels
import com.example.tinymodels.models.domain.DeleteDownloadedModel
import androidx.room.Room
import android.content.Context
import com.example.tinymodels.models.domain.ListModels

object Injection {
    private lateinit var database: TinyModelsDatabase

    fun initialize(context: Context) {
        database = Room.databaseBuilder(
            context.applicationContext,
            TinyModelsDatabase::class.java,
            "tiny_models.db"
        ).build()
    }
    object Repository{
        fun getModelRepository(): ModelsRepository{
            return ModelsRepository.getInstance()
        }
    }
    object UseCases{
        fun getListUseCase(): ListModels{
            return ListModels(Repository.getModelRepository())
        }

        fun getModelDetailsUseCase(): GetModelDetails {
            return GetModelDetails(
                ModelDetailsRepository(ModelDetailsRemoteRepository())
            )
        }

        fun getDownloadedModelsUseCase(): ObserveDownloadedModels {
            return ObserveDownloadedModels(LocalModelsRepository(database.downloadedModelDao()))
        }

        fun getDeleteDownloadedModelUseCase(): DeleteDownloadedModel {
            return DeleteDownloadedModel(LocalModelsRepository(database.downloadedModelDao()))
        }

        fun getModelDownloadRepository(filesDirectory: java.io.File): ModelDownloadRepository {
            return ModelDownloadRepository(database.downloadedModelDao(), filesDirectory)
        }
    }
}