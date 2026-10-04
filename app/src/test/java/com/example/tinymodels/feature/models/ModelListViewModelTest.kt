package com.example.tinymodels.feature.models

import com.example.tinymodels.core.common.AppError
import com.example.tinymodels.core.common.AppResult
import com.example.tinymodels.core.network.NetworkMonitor
import com.example.tinymodels.domain.model.ModelFilter
import com.example.tinymodels.domain.model.ModelSummary
import com.example.tinymodels.domain.repository.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelListViewModelTest {

    private val repo = FakeListModelRepository()
    private val networkMonitor = FakeNetworkMonitor()

    private class FakeNetworkMonitor : NetworkMonitor {
        override val isOnline = MutableStateFlow(true)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo.result = AppResult.Success(listOf(sampleModel()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = ModelListViewModel(repo, networkMonitor)

    @Test
    fun `fetch succeeds and populates models`() = runTest {
        val viewModel = vm()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.models.isNotEmpty())
        assertEquals(null, state.error)
    }

    @Test
    fun `fetch fails fast with NoConnection when offline`() = runTest {
        networkMonitor.isOnline.value = false
        val viewModel = vm()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.error is AppError.NoConnection)
        assertTrue(state.isOfflineError)
        assertTrue(state.isOffline)
    }

    @Test
    fun `fetch surfaces typed error when the API fails`() = runTest {
        repo.result = AppResult.Error(AppError.ServerError("Hugging Face is having trouble right now.", 500))
        val viewModel = vm()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.error)
        assertFalse(state.isOfflineError)
        assertTrue(state.errorMessage!!.contains("Hugging Face"))
    }

    @Test
    fun `auto-retries the failed fetch when connectivity returns`() = runTest {
        // Start offline → NoConnection error.
        networkMonitor.isOnline.value = false
        val viewModel = vm()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.error is AppError.NoConnection)

        // Back online → the fetch should retry automatically and succeed.
        repo.result = AppResult.Success(listOf(sampleModel()))
        networkMonitor.isOnline.value = true
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(null, state.error)
        assertEquals(1, state.models.size)
    }

    private fun sampleModel() = ModelSummary(
        id = "org/model",
        modelId = "org/model",
        author = "org",
        likes = 10,
        downloads = 100L,
        tags = emptyList(),
        libraryName = "litert-lm",
        pipelineTag = "text-generation",
        lastModified = null,
        siblings = listOf("model.litertlm")
    )
}

/** Minimal in-memory ModelRepository for tests (list-focused). */
private class FakeListModelRepository : ModelRepository {
    var result: AppResult<List<ModelSummary>> = AppResult.Success(emptyList())

    override suspend fun listModels(): AppResult<List<ModelSummary>> = result

    override suspend fun listModels(search: String?, pipelineTag: String?): AppResult<List<ModelSummary>> = result

    override suspend fun getModelDetails(modelId: String) = throw NotImplementedError()

    override fun observeDownloadedModels() = MutableStateFlow<List<com.example.tinymodels.domain.model.DownloadedModel>>(emptyList())
    override suspend fun getDownloadedModel(modelId: String) = null
    override suspend fun deleteDownloadedModel(modelId: String) {}
    override fun observeModelFiles(modelId: String) = MutableStateFlow<List<com.example.tinymodels.domain.model.DownloadedModelFile>>(emptyList())
    override fun observeDownloadedFiles() = MutableStateFlow<List<com.example.tinymodels.domain.model.DownloadedModelFile>>(emptyList())
    override suspend fun getModelFile(modelId: String, fileName: String) = null
    override suspend fun getDownloadedFileForModel(modelId: String) = null
    override suspend fun getLastDownloadedModel() = null
    override suspend fun preRegisterModel(model: com.example.tinymodels.domain.model.ModelDetails) {}
    override suspend fun updateFileStatus(
        modelId: String,
        fileName: String,
        status: com.example.tinymodels.domain.model.FileDownloadStatus,
        sizeBytes: Long?,
        localPath: String?,
        error: String?
    ) {}
    override suspend fun deleteModelFile(modelId: String, fileName: String) {}
}