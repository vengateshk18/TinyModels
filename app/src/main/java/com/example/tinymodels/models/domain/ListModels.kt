package com.example.tinymodels.models.domain

import com.example.tinymodels.models.data.Model
import com.example.tinymodels.models.data.ModelsRepository
import com.example.tinymodels.utils.Result

class ListModels(val modelsRepository: ModelsRepository) {
    suspend operator fun invoke(): Result<List<Model>>{
        return modelsRepository.getListOfModels()
    }
}