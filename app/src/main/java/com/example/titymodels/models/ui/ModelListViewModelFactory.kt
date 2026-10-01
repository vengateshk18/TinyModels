package com.example.titymodels.models.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.titymodels.models.domain.ListModels

class ModelListViewModelFactory(val listModels: ListModels): ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ModelListViewModel(listModels) as T
    }
}