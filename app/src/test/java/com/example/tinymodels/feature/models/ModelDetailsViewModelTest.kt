package com.example.tinymodels.feature.models

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.example.tinymodels.core.common.AppError
import com.example.tinymodels.core.common.AppResult
import com.example.tinymodels.core.common.StorageUtils
import com.example.tinymodels.core.network.NetworkMonitor
import com.example.tinymodels.domain.model.DownloadedModel
import com.example.tinymodels.domain.model.DownloadedModelFile
import com.example.tinymodels.domain.model.FileDownloadStatus
import com.example.tinymodels.domain.model.ModelDetails
import com.example.tinymodels.domain.model.ModelSummary
import com.example.tinymodels.domain.repository.ModelRepository
import com.example.tinymodels.domain.usecase.model.DownloadModelUseCase
import com.example.tinymodels.domain.usecase.model.DownloadState
import com.example.tinymodels.domain.usecase.model.DownloadStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelDetailsViewModelTest {

    @get:Rule
    val instantExecutor = InstantTaskExecutorRule()

    private val repo = FakeModelRepository()
    private val downloadUseCase = mockk<DownloadModelUseCase>(relaxed = true)
    private val storage = mockk<StorageUtils>(relaxed = true)
    private val networkMonitor = FakeNetworkMonitor()

    /** Mocked app context — MemoryUtils.totalRamBytes is stubbed to a fixed value. */
    private val appContext = mockk<android.content.Context>()

    /** In-memory monitor so tests can toggle connectivity. */
    private class FakeNetworkMonitor : NetworkMonitor {
        override val isOnline = MutableStateFlow(true)
    }

    private val sampleModel = ModelDetails(
        id = "org/model-270m",
        author = "org",
        pipelineTag = "text-generation",
        libraryName = "litert-lm",
        tags = listOf("gemma3", "gemma"),
        downloads = 1151L,
        likes = 247,
        lastModified = "2026-08-31T13:49:04.000Z",
        createdAt = "2026-02-25T23:13:08.000Z",
        siblings = listOf("README.md", "model_G5.litertlm", "model_G6.litertlm"),
        usedStorage = 1_432_447_106L,
        sha = "abc123",
        gated = "auto",
        disabled = false,
        widgetPrompts = listOf("Hi!", "Explain quantum computing."),
        baseModel = "google/base"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo.reset(sampleModel)
        every { storage.hasSpaceFor(any()) } returns true
        // Stub totalRamBytes: a 4GB device. MemoryUtils is a Kotlin object,
        // so mockkObject (not mockkStatic) is required.
        io.mockk.mockkObject(com.example.tinymodels.core.common.MemoryUtils)
        every { com.example.tinymodels.core.common.MemoryUtils.totalRamBytes(any()) } returns 4L * 1024 * 1024 * 1024
    }

    @After
    fun tearDownMemory() {
        io.mockk.unmockkObject(com.example.tinymodels.core.common.MemoryUtils)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(modelId: String = sampleModel.id) = ModelDetailsViewModel(
        SavedStateHandle(mapOf("modelId" to modelId)),
        appContext,
        repo,
        downloadUseCase,
        storage,
        networkMonitor
    )

    @Test
    fun `init loads model details and exposes total size from usedStorage`() = runTest {
        val viewModel = vm()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(sampleModel.id, state.model?.id)
        assertEquals(1_432_447_106L, state.totalSizeBytes)
        assertEquals(2, state.model?.liteRtFiles?.size)
        assertTrue(state.model?.isGated == true)
    }

    @Test
    fun `init surfaces error when details fail to load`() = runTest {
        repo.detailsResult = AppResult.Error(AppError.Network("boom"))
        val viewModel = vm()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.error)
        assertEquals("boom", state.error?.message)
    }

    @Test
    fun `load fails fast with NoConnection when offline`() = runTest {
        networkMonitor.isOnline.value = false
        val viewModel = vm()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.error is AppError.NoConnection)
        assertTrue(state.isOfflineError)
    }

    @Test
    fun `load auto-retries when connectivity returns after a failure`() = runTest {
        networkMonitor.isOnline.value = false
        val viewModel = vm()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.error is AppError.NoConnection)

        // Back online — the failed load should retry automatically.
        networkMonitor.isOnline.value = true
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.error)
        assertEquals(sampleModel.id, state.model?.id)
    }

    @Test
    fun `onDownloadClick streams DOWNLOADING then COMPLETED`() = runTest {
        every { downloadUseCase.observeExisting(any()) } returns null
        every { downloadUseCase.execute(any(), any()) } returns flow {
            emit(DownloadState(status = DownloadStatus.DOWNLOADING, progress = 0.5f,
                downloadedBytes = 700_000_000L, totalBytes = 1_432_447_106L, bytesPerSecond = 5_000_000L))
            emit(DownloadState(status = DownloadStatus.COMPLETED, progress = 1f,
                downloadedBytes = 1_432_447_106L, totalBytes = 1_432_447_106L))
        }

        val viewModel = vm()
        advanceUntilIdle()
        viewModel.onDownloadClick()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DownloadStatus.COMPLETED, state.download.status)
        assertEquals(1f, state.download.progress, 0.001f)
        assertEquals(1_432_447_106L, state.download.totalBytes)
    }

    @Test
    fun `onDownloadClick rejects when not enough storage`() = runTest {
        every { downloadUseCase.observeExisting(any()) } returns null
        every { storage.hasSpaceFor(any()) } returns false

        val viewModel = vm()
        advanceUntilIdle()
        viewModel.onDownloadClick()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DownloadStatus.FAILED, state.download.status)
        assertNotNull(state.download.error)
    }

    @Test
    fun `onDownloadClick toggles to cancel when already downloading`() = runTest {
        every { downloadUseCase.observeExisting(any()) } returns null
        every { downloadUseCase.execute(any(), any()) } returns MutableStateFlow(
            DownloadState(status = DownloadStatus.DOWNLOADING, progress = 0.4f)
        )

        val viewModel = vm()
        advanceUntilIdle()
        viewModel.onDownloadClick() // start
        advanceUntilIdle()
        viewModel.onDownloadClick() // toggle cancel
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DownloadStatus.IDLE, state.download.status)
    }
}

/** Minimal in-memory ModelRepository for tests. */
private class FakeModelRepository : ModelRepository {
    var detailsResult: AppResult<ModelDetails> = AppResult.Success(ModelDetails(
        id = "x", author = null, pipelineTag = null, libraryName = null, tags = emptyList(),
        downloads = null, likes = null, lastModified = null, createdAt = null,
        siblings = emptyList(), usedStorage = null, sha = null, gated = null,
        disabled = false, widgetPrompts = emptyList(), baseModel = null
    ))
    private val downloaded = MutableStateFlow<List<DownloadedModel>>(emptyList())
    private val modelFiles = MutableStateFlow<List<DownloadedModelFile>>(emptyList())

    fun reset(details: ModelDetails) {
        detailsResult = AppResult.Success(details)
    }

    override suspend fun listModels(): AppResult<List<ModelSummary>>
        = throw NotImplementedError()

    override suspend fun listModels(search: String?, pipelineTag: String?): AppResult<List<ModelSummary>>
        = throw NotImplementedError()

    override suspend fun getModelDetails(modelId: String): AppResult<ModelDetails> = detailsResult

    override fun observeDownloadedModels() = downloaded
    override suspend fun getDownloadedModel(modelId: String): DownloadedModel? = null
    override suspend fun deleteDownloadedModel(modelId: String) {}

    override fun observeModelFiles(modelId: String): Flow<List<DownloadedModelFile>> = modelFiles
    override fun observeDownloadedFiles(): Flow<List<DownloadedModelFile>> = modelFiles
    override suspend fun getModelFile(modelId: String, fileName: String): DownloadedModelFile? = null
    override suspend fun getDownloadedFileForModel(modelId: String): DownloadedModelFile? = null
    override suspend fun getLastDownloadedModel(): DownloadedModel? = null
    override suspend fun preRegisterModel(model: ModelDetails) {}
    override suspend fun updateFileStatus(
        modelId: String,
        fileName: String,
        status: FileDownloadStatus,
        sizeBytes: Long?,
        localPath: String?,
        error: String?
    ) {}
    override suspend fun deleteModelFile(modelId: String, fileName: String) {}
}
