package com.example.tinymodels.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tinymodels.core.common.AppError
import com.example.tinymodels.core.common.AppResult
import com.example.tinymodels.core.network.NetworkErrorMapper
import com.example.tinymodels.core.network.NetworkMonitor
import com.example.tinymodels.domain.model.ModelFilter
import com.example.tinymodels.domain.model.ModelSummary
import com.example.tinymodels.domain.repository.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelListViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val models: List<ModelSummary> = emptyList(),
        /** Typed error from the last failed fetch — drives offline vs error UI. */
        val error: AppError? = null,
        /** Mirrors the device's connectivity (kept fresh by the monitor). */
        val isOffline: Boolean = false,
        val searchQuery: String = "",
        val selectedFilter: ModelFilter = ModelFilter.ALL
    ) {
        /** Friendly, user-presentable copy for [error]. */
        val errorMessage: String?
            get() = error?.let(NetworkErrorMapper::friendlyMessage)

        /** True when the current error is simply "no connection". */
        val isOfflineError: Boolean
            get() = error is AppError.NoConnection
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        refresh()
        observeConnectivity()
    }

    /** Tracks connectivity and auto-retries the last failed fetch on reconnect. */
    private fun observeConnectivity() {
        viewModelScope.launch {
            var wasOnline: Boolean? = null
            networkMonitor.isOnline.collect { online ->
                _uiState.update { it.copy(isOffline = !online) }
                // Auto-retry once when we come back online after a failed fetch.
                if (wasOnline == false && online && _uiState.value.error != null) {
                    fetch(_uiState.value.searchQuery, _uiState.value.selectedFilter)
                }
                wasOnline = online
            }
        }
    }

    fun refresh() {
        fetch(_uiState.value.searchQuery, _uiState.value.selectedFilter)
    }

    /** Debounced search — waits 500ms after the user stops typing. */
    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(500)
            fetch(query, _uiState.value.selectedFilter)
        }
    }

    fun setFilter(filter: ModelFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
        fetch(_uiState.value.searchQuery, filter)
    }

    private fun fetch(query: String, filter: ModelFilter) {
        viewModelScope.launch {
            // Fail fast when the device is offline — no point hitting the API.
            if (!networkMonitor.isOnline.value) {
                _uiState.update {
                    it.copy(isLoading = false, error = AppError.NoConnection())
                }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = modelRepository.listModels(query, filter.pipelineTag)) {
                is AppResult.Success ->
                    _uiState.update { it.copy(isLoading = false, models = result.data) }
                is AppResult.Error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = result.error)
                    }
            }
        }
    }
}