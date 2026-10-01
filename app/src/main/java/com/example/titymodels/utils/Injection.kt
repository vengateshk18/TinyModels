package com.example.titymodels.utils

import com.example.titymodels.models.data.ModelsRepository
import com.example.titymodels.models.data.ModelDetailsRemoteRepository
import com.example.titymodels.models.data.ModelDetailsRepository
import com.example.titymodels.models.local.LocalModelsRepository
import com.example.titymodels.models.local.TityModelsDatabase
import com.example.titymodels.models.local.ModelDownloadRepository
import com.example.titymodels.models.domain.GetModelDetails
import com.example.titymodels.models.domain.ObserveDownloadedModels
import com.example.titymodels.models.domain.DeleteDownloadedModel
import androidx.room.Room
import android.content.Context
import com.example.titymodels.models.domain.ListModels

object Injection {
    private lateinit var database: TityModelsDatabase

    fun initialize(context: Context) {
        database = Room.databaseBuilder(
            context.applicationContext,
            TityModelsDatabase::class.java,
            "tity_models.db"
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