package com.ai.tinymodels.feature.models

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.tinymodels.core.common.AppError
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.domain.model.ModelCollection
import com.ai.tinymodels.domain.model.ModelSummary
import com.ai.tinymodels.domain.repository.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CollectionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val modelRepository: ModelRepository
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val collection: ModelCollection? = null,
        val models: List<ModelSummary> = emptyList(),
        val error: AppError? = null
    )

    private val slug: String = checkNotNull(savedStateHandle["slug"])

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = modelRepository.getCollectionModels(slug)) {
                is AppResult.Success -> _uiState.value = UiState(
                    isLoading = false,
                    collection = result.data.collection,
                    models = result.data.models
                )
                is AppResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.error
                )
            }
        }
    }
}
