package com.example.titymodels.models.domain

import com.example.titymodels.models.data.Model
import com.example.titymodels.models.data.ModelsRepository
import com.example.titymodels.utils.Result

class ListModels(val modelsRepository: ModelsRepository) {
    suspend operator fun invoke(): Result<List<Model>>{
        return modelsRepository.getListOfModels()
    }
}