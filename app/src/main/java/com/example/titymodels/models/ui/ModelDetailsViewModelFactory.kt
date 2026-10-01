package com.example.titymodels.models.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.titymodels.models.domain.GetModelDetails
import com.example.titymodels.models.local.ModelDownloadRepository

class ModelDetailsViewModelFactory(
    private val getModelDetails: GetModelDetails
    , private val downloads: ModelDownloadRepository
    , private val context: android.content.Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ModelDetailsViewModel(getModelDetails, downloads, context) as T
    }
}
