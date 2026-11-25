package com.ai.tinymodels.feature.models

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.tinymodels.R
import com.ai.tinymodels.core.common.AppError
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.network.NetworkErrorMapper
import com.ai.tinymodels.core.network.NetworkMonitor
import com.ai.tinymodels.domain.model.CATALOG_PAGE_SIZE
import com.ai.tinymodels.domain.model.ModelCollection
import com.ai.tinymodels.domain.model.ModelFeed
import com.ai.tinymodels.domain.model.ModelFilter
import com.ai.tinymodels.domain.model.ModelSummary
import com.ai.tinymodels.domain.repository.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelImportProgress(
    val bytesCopied: Long,
    val totalBytes: Long
) {
    val percent: Int?
        get() = if (totalBytes > 0L) {
            ((bytesCopied.coerceAtMost(totalBytes) * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else null
}

@HiltViewModel
class ModelListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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
        val selectedFeed: ModelFeed = ModelFeed.POPULAR,
        val selectedTag: String? = null,
        val tagTypes: Map<String, List<String>> = emptyMap(),
        /** True when the hub may hold more matches — shows the "Load more" footer. */
        val hasMore: Boolean = false,
        /** True while "Load more" is fetching; the current list stays visible. */
        val isLoadingMore: Boolean = false,
        val isImporting: Boolean = false,
        val importProgress: ModelImportProgress? = null,
        val collections: List<ModelCollection> = emptyList(),
        val isCollectionsLoading: Boolean = false
    ) {
        /** Friendly, user-presentable copy for [error], as a localized resource. */
        @get:StringRes
        val errorMessageRes: Int?
            get() = error?.let(NetworkErrorMapper::friendlyMessageRes)

        /** True when the current error is simply "no connection". */
        val isOfflineError: Boolean
            get() = error is AppError.NoConnection
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _importToast = MutableStateFlow<String?>(null)
    val importToast: StateFlow<String?> = _importToast.asStateFlow()

    private var searchJob: Job? = null
    private var fetchJob: Job? = null

    /** Raw models fetched per branch; "Load more" grows this in page steps. */
    private var pageLimit = CATALOG_PAGE_SIZE

    fun consumeImportToast() { _importToast.value = null }

    fun importModel(sourceUri: Uri) {
        if (_uiState.value.isImporting) return
        _uiState.update { it.copy(isImporting = true, importProgress = ModelImportProgress(0L, -1L)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = modelRepository.importModel(sourceUri) { copied, total ->
                    _uiState.update { it.copy(importProgress = ModelImportProgress(copied, total)) }
                }
                _importToast.value = when (result) {
                    is AppResult.Success ->
                        context.getString(R.string.settings_model_imported, result.data.modelId)
                    is AppResult.Error ->
                        context.getString(R.string.settings_import_failed, result.error.message ?: "")
                }
            } finally {
                _uiState.update { it.copy(isImporting = false, importProgress = null) }
            }
        }
    }

    init {
        refresh()
        loadTags()
        loadCollections()
        observeConnectivity()
    }

    fun loadCollections() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCollectionsLoading = true) }
            when (val result = modelRepository.getBrowseCollections()) {
                is AppResult.Success -> _uiState.update {
                    it.copy(collections = result.data, isCollectionsLoading = false)
                }
                is AppResult.Error -> _uiState.update { it.copy(isCollectionsLoading = false) }
            }
        }
    }

    private fun loadTags() {
        viewModelScope.launch {
            when (val result = modelRepository.getModelTagTypes()) {
                is AppResult.Success -> _uiState.update { it.copy(tagTypes = result.data) }
                is AppResult.Error -> Unit
            }
        }
    }

    /** Tracks connectivity and auto-retries the last failed fetch on reconnect. */
    private fun observeConnectivity() {
        viewModelScope.launch {
            var wasOnline: Boolean? = null
            networkMonitor.isOnline.collect { online ->
                _uiState.update { it.copy(isOffline = !online) }
                // Auto-retry once when we come back online after a failed fetch.
                if (wasOnline == false && online && _uiState.value.error != null) {
                    val s = _uiState.value
                    fetch(s.searchQuery, s.selectedFilter, s.selectedFeed, s.selectedTag)
                }
                wasOnline = online
            }
        }
    }

    fun refresh() {
        val s = _uiState.value
        fetch(s.searchQuery, s.selectedFilter, s.selectedFeed, s.selectedTag)
    }

    /** Debounced search — waits 500ms after the user stops typing. */
    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        pageLimit = CATALOG_PAGE_SIZE
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(500)
            val s = _uiState.value
            fetch(query, s.selectedFilter, s.selectedFeed, s.selectedTag)
        }
    }

    fun setFilter(filter: ModelFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
        pageLimit = CATALOG_PAGE_SIZE
        val s = _uiState.value
        fetch(s.searchQuery, filter, s.selectedFeed, s.selectedTag)
    }

    fun setFeed(feed: ModelFeed) {
        _uiState.update { it.copy(selectedFeed = feed) }
        pageLimit = CATALOG_PAGE_SIZE
        val s = _uiState.value
        fetch(s.searchQuery, s.selectedFilter, feed, s.selectedTag)
    }

    fun setTag(tag: String?) {
        _uiState.update { it.copy(selectedTag = tag) }
        pageLimit = CATALOG_PAGE_SIZE
        val s = _uiState.value
        fetch(s.searchQuery, s.selectedFilter, s.selectedFeed, tag)
    }

    fun applyBrowseOptions(feed: ModelFeed, filter: ModelFilter, tag: String?) {
        _uiState.update { it.copy(selectedFeed = feed, selectedFilter = filter, selectedTag = tag) }
        pageLimit = CATALOG_PAGE_SIZE
        fetch(_uiState.value.searchQuery, filter, feed, tag)
    }

    /** Widens the fetched window by one page; driven by the "Load more" footer. */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore) return
        pageLimit += CATALOG_PAGE_SIZE
        fetch(state.searchQuery, state.selectedFilter, state.selectedFeed, state.selectedTag, isLoadMore = true)
    }

    private fun fetch(
        query: String,
        filter: ModelFilter,
        feed: ModelFeed = _uiState.value.selectedFeed,
        tag: String? = _uiState.value.selectedTag,
        isLoadMore: Boolean = false
    ) {
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
            when (val result = modelRepository.listModels(query, filter, feed, tag, pageLimit)) {
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