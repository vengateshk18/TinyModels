package com.example.tinymodels.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tinymodels.core.common.AppResult
import com.example.tinymodels.domain.model.ModelSummary
import com.example.tinymodels.domain.repository.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelListViewModel @Inject constructor(
    private val modelRepository: ModelRepository
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val models: List<ModelSummary> = emptyList(),
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = modelRepository.listModels()) {
                is AppResult.Success ->
                    _uiState.update { it.copy(isLoading = false, models = result.data) }
                is AppResult.Error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = result.error.message ?: "Failed to load models")
                    }
            }
        }
    }
}
