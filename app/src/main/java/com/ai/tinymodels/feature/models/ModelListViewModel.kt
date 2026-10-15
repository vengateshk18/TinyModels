package com.ai.tinymodels.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.tinymodels.core.common.AppError
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.network.NetworkErrorMapper
import com.ai.tinymodels.core.network.NetworkMonitor
import com.ai.tinymodels.domain.model.CATALOG_PAGE_SIZE
import com.ai.tinymodels.domain.model.ModelFilter
import com.ai.tinymodels.domain.model.ModelSummary
import com.ai.tinymodels.domain.repository.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
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
        val selectedFilter: ModelFilter = ModelFilter.ALL,
        /** True when the hub may hold more matches — shows the "Load more" footer. */
        val hasMore: Boolean = false,
        /** True while "Load more" is fetching; the current list stays visible. */
        val isLoadingMore: Boolean = false
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
    private var fetchJob: Job? = null

    /** Raw models fetched per branch; "Load more" grows this in page steps. */
    private var pageLimit = CATALOG_PAGE_SIZE

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
        pageLimit = CATALOG_PAGE_SIZE
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(500)
            fetch(query, _uiState.value.selectedFilter)
        }
    }

    fun setFilter(filter: ModelFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
        pageLimit = CATALOG_PAGE_SIZE
        fetch(_uiState.value.searchQuery, filter)
    }

    /** Widens the fetched window by one page; driven by the "Load more" footer. */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore) return
        pageLimit += CATALOG_PAGE_SIZE
        fetch(state.searchQuery, state.selectedFilter, isLoadMore = true)
    }

    private fun fetch(query: String, filter: ModelFilter, isLoadMore: Boolean = false) {
        // Last request wins: cancel any in-flight fetch so a stale response can
        // never overwrite a newer one (rapid typing, filter taps, load-more).
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            // Fail fast when the device is offline — no point hitting the API.
            if (!networkMonitor.isOnline.value) {
                _uiState.update {
                    it.copy(isLoading = false, isLoadingMore = false, error = AppError.NoConnection())
                }
                return@launch
            }
            _uiState.update {
                it.copy(isLoading = !isLoadMore, isLoadingMore = isLoadMore, error = null)
            }
            when (val result = modelRepository.listModels(query, filter, pageLimit)) {
                is AppResult.Success ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            models = result.data.models,
                            hasMore = result.data.hasMore
                        )
                    }
                is AppResult.Error ->
                    _uiState.update {
                        it.copy(isLoading = false, isLoadingMore = false, error = result.error)
                    }
            }
        }
    }
}