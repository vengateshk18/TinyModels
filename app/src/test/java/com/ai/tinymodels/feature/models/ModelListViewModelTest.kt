package com.ai.tinymodels.feature.models

import com.ai.tinymodels.core.common.AppError
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.network.NetworkMonitor
import com.ai.tinymodels.domain.model.CATALOG_PAGE_SIZE
import com.ai.tinymodels.domain.model.CatalogPage
import com.ai.tinymodels.domain.model.ModelFilter
import com.ai.tinymodels.domain.model.ModelSummary
import com.ai.tinymodels.domain.repository.ModelRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
        repo.result = AppResult.Success(CatalogPage(listOf(sampleModel()), hasMore = false))
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
        repo.result = AppResult.Success(CatalogPage(listOf(sampleModel()), hasMore = false))
        networkMonitor.isOnline.value = true
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(null, state.error)
        assertEquals(1, state.models.size)
    }

    @Test
    fun `fetch exposes hasMore from the repository page`() = runTest {
        repo.result = AppResult.Success(CatalogPage(listOf(sampleModel()), hasMore = true))
        val viewModel = vm()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasMore)
    }

    @Test
    fun `load-more widens the window by one page`() = runTest {
        repo.result = AppResult.Success(CatalogPage(listOf(sampleModel()), hasMore = true))
        val viewModel = vm()
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(CATALOG_PAGE_SIZE * 2, repo.calls.last().limit)
        assertFalse(viewModel.uiState.value.isLoadingMore)
    }

    @Test
    fun `load-more is ignored when there is nothing more to fetch`() = runTest {
        repo.result = AppResult.Success(CatalogPage(listOf(sampleModel()), hasMore = false))
        val viewModel = vm()
        advanceUntilIdle()
        val callsBefore = repo.calls.size

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(callsBefore, repo.calls.size)
    }

    @Test
    fun `load-more keeps the current list visible while fetching`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repo.onListModels = { call ->
            if (call.limit > CATALOG_PAGE_SIZE) gate.await()
            AppResult.Success(CatalogPage(listOf(sampleModel()), hasMore = true))
        }
        val viewModel = vm()
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        val loading = viewModel.uiState.value
        assertTrue(loading.isLoadingMore)
        assertFalse(loading.isLoading)
        assertEquals(1, loading.models.size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoadingMore)
    }

    @Test
    fun `a new search resets the paging window`() = runTest {
        repo.result = AppResult.Success(CatalogPage(listOf(sampleModel()), hasMore = true))
        val viewModel = vm()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(CATALOG_PAGE_SIZE * 2, repo.calls.last().limit)

        viewModel.search("qwen")
        advanceTimeBy(501)
        advanceUntilIdle()

        assertEquals("qwen", repo.calls.last().search)
        assertEquals(CATALOG_PAGE_SIZE, repo.calls.last().limit)
    }

    @Test
    fun `a stale in-flight fetch cannot overwrite a newer one`() = runTest {
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        repo.onListModels = { call ->
            when (call.search) {
                "a" -> {
                    gateA.await()
                    AppResult.Success(CatalogPage(listOf(sampleModel("org/a")), hasMore = false))
                }
                "b" -> {
                    gateB.await()
                    AppResult.Success(CatalogPage(listOf(sampleModel("org/b")), hasMore = false))
                }
                else -> AppResult.Success(CatalogPage(emptyList(), hasMore = false))
            }
        }
        val viewModel = vm()
        advanceUntilIdle()

        viewModel.search("a")
        advanceTimeBy(501) // debounce fires; the "a" fetch suspends on gateA
        viewModel.search("b")
        advanceTimeBy(501) // the "b" fetch must cancel the in-flight "a" fetch

        gateB.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("org/b"), viewModel.uiState.value.models.map { it.modelId })

        gateA.complete(Unit) // late arrival — must not clobber the newer result
        advanceUntilIdle()
        assertEquals(listOf("org/b"), viewModel.uiState.value.models.map { it.modelId })
    }

    private fun sampleModel(modelId: String = "org/model") = ModelSummary(
        id = modelId,
        modelId = modelId,
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
    data class Call(val search: String?, val filter: ModelFilter, val limit: Int)

    var result: AppResult<CatalogPage> = AppResult.Success(CatalogPage(emptyList(), hasMore = false))
    val calls = mutableListOf<Call>()
    var onListModels: (suspend (Call) -> AppResult<CatalogPage>)? = null

    override suspend fun listModels(
        search: String?,
        filter: ModelFilter,
        limit: Int
    ): AppResult<CatalogPage> {
        val call = Call(search, filter, limit)
        calls += call
        return onListModels?.invoke(call) ?: result
    }

    override suspend fun getModelDetails(modelId: String) = throw NotImplementedError()

    override fun observeDownloadedModels() = MutableStateFlow<List<com.ai.tinymodels.domain.model.DownloadedModel>>(emptyList())
    override suspend fun getDownloadedModel(modelId: String) = null
    override suspend fun deleteDownloadedModel(modelId: String) {}
    override fun observeModelFiles(modelId: String) = MutableStateFlow<List<com.ai.tinymodels.domain.model.DownloadedModelFile>>(emptyList())
    override fun observeDownloadedFiles() = MutableStateFlow<List<com.ai.tinymodels.domain.model.DownloadedModelFile>>(emptyList())
    override suspend fun getModelFile(modelId: String, fileName: String) = null
    override suspend fun getDownloadedFileForModel(modelId: String) = null
    override suspend fun getLastDownloadedModel() = null
    override suspend fun preRegisterModel(model: com.ai.tinymodels.domain.model.ModelDetails) {}
    override suspend fun updateFileStatus(
        modelId: String,
        fileName: String,
        status: com.ai.tinymodels.domain.model.FileDownloadStatus,
        sizeBytes: Long?,
        localPath: String?,
        error: String?
    ) {}
    override suspend fun deleteModelFile(modelId: String, fileName: String) {}
    override suspend fun exportModelFile(
        modelId: String,
        fileName: String,
        destinationUri: android.net.Uri
    ): com.ai.tinymodels.core.common.AppResult<android.net.Uri> =
        com.ai.tinymodels.core.common.AppResult.Error(
            com.ai.tinymodels.core.common.AppError.Unknown("not supported in tests")
        )
    override suspend fun importModel(
        sourceUri: android.net.Uri
    ): com.ai.tinymodels.core.common.AppResult<com.ai.tinymodels.domain.model.DownloadedModel> =
        com.ai.tinymodels.core.common.AppResult.Error(
            com.ai.tinymodels.core.common.AppError.Unknown("not supported in tests")
        )
}