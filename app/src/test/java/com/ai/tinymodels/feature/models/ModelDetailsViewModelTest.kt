package com.ai.tinymodels.feature.models

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.ai.tinymodels.core.common.AppError
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.common.StorageUtils
import com.ai.tinymodels.core.network.NetworkMonitor
import com.ai.tinymodels.domain.model.DownloadedModel
import com.ai.tinymodels.domain.model.DownloadedModelFile
import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.model.ModelDetails
import com.ai.tinymodels.domain.model.CatalogPage
import com.ai.tinymodels.domain.model.ModelFilter
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.usecase.model.ActiveDownload
import com.ai.tinymodels.domain.usecase.model.DownloadModelUseCase
import com.ai.tinymodels.domain.usecase.model.DownloadState
import com.ai.tinymodels.domain.usecase.model.DownloadStatus
import com.ai.tinymodels.domain.usecase.model.FileRowPhase
import com.ai.tinymodels.domain.usecase.model.FileSizeStatus
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
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
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ModelDetailsViewModelTest {

    @get:Rule
    val instantExecutor = InstantTaskExecutorRule()

    private val repo = FakeModelRepository()
    private val downloadUseCase = mockk<DownloadModelUseCase>(relaxed = true)
    private val storage = mockk<StorageUtils>(relaxed = true)
    private val networkMonitor = FakeNetworkMonitor()

    /** Mocked app context — MemoryUtils.totalRamBytes is stubbed to a fixed value. */
    private val appContext = mockk<android.content.Context>().also { ctx ->
        every { ctx.getString(any()) } answers { firstArg<Int>().toString() }
        every { ctx.getString(any(), *anyVararg()) } answers {
            firstArg<Int>().toString() + "(" + invocation.args.drop(1)
                .flatMap { arg -> if (arg is Array<*>) arg.toList() else listOf(arg) }
                .joinToString(",") + ")"
        }
    }

    /** Live jobs the ViewModel is told about; tests push entries here. */
    private val activeDownloads = MutableStateFlow<List<ActiveDownload>>(emptyList())

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
        every { downloadUseCase.observeActiveDownloads() } returns activeDownloads
        // Stub totalRamBytes: a 4GB device. MemoryUtils is a Kotlin object,
        // so mockkObject (not mockkStatic) is required.
        io.mockk.mockkObject(com.ai.tinymodels.core.common.MemoryUtils)
        every { com.ai.tinymodels.core.common.MemoryUtils.totalRamBytes(any()) } returns 4L * 1024 * 1024 * 1024
    }

    @After
    fun tearDownMemory() {
        io.mockk.unmockkObject(com.ai.tinymodels.core.common.MemoryUtils)
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

    // ---- per-file downloads ----

    private fun file(name: String, status: FileDownloadStatus = FileDownloadStatus.NOT_DOWNLOADED) =
        DownloadedModelFile(
            modelId = sampleModel.id,
            fileName = name,
            status = status,
            sizeBytes = 100L,
            localPath = null,
            error = null
        )

    private fun active(fileName: String, progress: Float = 0.4f) = ActiveDownload(
        workId = UUID.randomUUID(),
        modelId = sampleModel.id,
        fileName = fileName,
        status = DownloadStatus.DOWNLOADING,
        progress = progress,
        downloadedBytes = 40L,
        totalBytes = 100L,
        bytesPerSecond = 10L
    )

    @Test
    fun `only the file with a live job is shown as downloading`() = runTest {
        repo.seedFiles(file("a.litertlm"), file("b.litertlm"))
        activeDownloads.value = listOf(active("a.litertlm", progress = 0.4f))

        val viewModel = vm()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(FileRowPhase.DOWNLOADING, state.fileState("a.litertlm").phase)
        assertEquals(0.4f, state.fileState("a.litertlm").progress, 0.001f)
        assertEquals(FileRowPhase.IDLE, state.fileState("b.litertlm").phase)
    }

    @Test
    fun `two live jobs both report progress`() = runTest {
        repo.seedFiles(file("a.litertlm"), file("b.litertlm"))
        activeDownloads.value = listOf(active("a.litertlm", 0.2f), active("b.litertlm", 0.8f))

        val viewModel = vm()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0.2f, state.fileState("a.litertlm").progress, 0.001f)
        assertEquals(0.8f, state.fileState("b.litertlm").progress, 0.001f)
    }

    @Test
    fun `persisted status shows when no job is running`() = runTest {
        repo.seedFiles(
            file("a.litertlm", FileDownloadStatus.PAUSED),
            file("b.litertlm", FileDownloadStatus.DOWNLOADED)
        )

        val viewModel = vm()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(FileRowPhase.PAUSED, state.fileState("a.litertlm").phase)
        assertEquals(FileRowPhase.DOWNLOADED, state.fileState("b.litertlm").phase)
    }

    @Test
    fun `re-entering the screen reattaches to a running job`() = runTest {
        repo.seedFiles(file("a.litertlm", FileDownloadStatus.DOWNLOADING))
        activeDownloads.value = listOf(active("a.litertlm", 0.55f))

        // Brand new ViewModel — the job outlived the previous one.
        val viewModel = vm()
        advanceUntilIdle()

        assertEquals(FileRowPhase.DOWNLOADING, viewModel.uiState.value.fileState("a.litertlm").phase)
        assertEquals(0.55f, viewModel.uiState.value.fileState("a.litertlm").progress, 0.001f)
    }

    @Test
    fun `downloading a file records DOWNLOADING and enqueues it`() = runTest {
        repo.seedFiles(file("a.litertlm"))
        every { downloadUseCase.executeFile(any(), any(), any(), any()) } returns MutableStateFlow(DownloadState())

        val viewModel = vm()
        advanceUntilIdle()
        viewModel.onDownloadFileClicked("a.litertlm")
        advanceUntilIdle()

        assertEquals(FileDownloadStatus.DOWNLOADING, repo.statusOf("a.litertlm"))
        verify(exactly = 1) { downloadUseCase.executeFile(any(), "a.litertlm", any(), false) }
    }

    @Test
    fun `pausing a file cancels its job and records PAUSED`() = runTest {
        repo.seedFiles(file("a.litertlm", FileDownloadStatus.DOWNLOADING))

        val viewModel = vm()
        advanceUntilIdle()
        viewModel.onPauseFile("a.litertlm")
        advanceUntilIdle()

        verify { downloadUseCase.cancelFile(sampleModel.id, "a.litertlm") }
        assertEquals(FileDownloadStatus.PAUSED, repo.statusOf("a.litertlm"))
    }

    @Test
    fun `resuming a paused file re-enqueues it and records DOWNLOADING`() = runTest {
        repo.seedFiles(file("a.litertlm", FileDownloadStatus.PAUSED))
        every { downloadUseCase.executeFile(any(), any(), any(), any()) } returns MutableStateFlow(DownloadState())

        val viewModel = vm()
        advanceUntilIdle()
        viewModel.onResumeFile("a.litertlm")
        advanceUntilIdle()

        verify { downloadUseCase.executeFile(any(), "a.litertlm", any(), true) }
        assertEquals(FileDownloadStatus.DOWNLOADING, repo.statusOf("a.litertlm"))
    }

    @Test
    fun `discarding a paused file drops the partial and clears the row`() = runTest {
        repo.seedFiles(file("a.litertlm", FileDownloadStatus.PAUSED))

        val viewModel = vm()
        advanceUntilIdle()
        viewModel.onDiscardFile("a.litertlm")
        advanceUntilIdle()

        verify { downloadUseCase.cancelFile(sampleModel.id, "a.litertlm") }
        coVerify { downloadUseCase.discardPartial(sampleModel.id, "a.litertlm") }
        assertEquals(FileDownloadStatus.NOT_DOWNLOADED, repo.statusOf("a.litertlm"))
    }

    @Test
    fun `discarding one file keeps the parent row while another is downloaded`() = runTest {
        repo.seedFiles(
            file("a.litertlm", FileDownloadStatus.PAUSED),
            file("b.litertlm", FileDownloadStatus.DOWNLOADED)
        )

        val viewModel = vm()
        advanceUntilIdle()
        viewModel.onDiscardFile("a.litertlm")
        advanceUntilIdle()

        assertEquals(0, repo.deleteCalls)
    }

    @Test
    fun `discarding the last file keeps the file rows so progress can be tracked again`() = runTest {
        repo.seedFiles(file("a.litertlm", FileDownloadStatus.PAUSED))

        val viewModel = vm()
        advanceUntilIdle()
        viewModel.onDownloadFileClicked("a.litertlm")
        advanceUntilIdle()
        viewModel.onPauseFile("a.litertlm")
        advanceUntilIdle()
        viewModel.onDiscardFile("a.litertlm")
        advanceUntilIdle()
        viewModel.onDownloadFileClicked("a.litertlm")
        advanceUntilIdle()

        // The rows must stay, or the live job below has nothing to attach to.
        assertEquals(0, repo.deleteCalls)
        assertEquals(FileDownloadStatus.DOWNLOADING, repo.statusOf("a.litertlm"))

        // Simulate the worker actually running again.
        activeDownloads.value = listOf(active("a.litertlm", 0.35f))
        advanceUntilIdle()

        val state = viewModel.uiState.value.fileState("a.litertlm")
        assertEquals(FileRowPhase.DOWNLOADING, state.phase)
        assertEquals(0.35f, state.progress, 0.001f)
    }

    @Test
    fun `file size is known once its HEAD request succeeds`() = runTest {
        io.mockk.coEvery { downloadUseCase.calculateFileSize(any(), any()) } returns 100L

        val viewModel = vm()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(FileSizeStatus.KNOWN, state.fileSizeStatus("model_G5.litertlm"))
        assertEquals(100L, state.getFileSize("model_G5.litertlm"))
    }

    @Test
    fun `file size is marked undetermined when its HEAD request fails`() = runTest {
        io.mockk.coEvery { downloadUseCase.calculateFileSize(any(), any()) } throws RuntimeException("404")

        val viewModel = vm()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(FileSizeStatus.UNKNOWN, state.fileSizeStatus("model_G5.litertlm"))
        assertEquals(0L, state.getFileSize("model_G5.litertlm"))
    }

    @Test
    fun `file size is undetermined once the size sweep fails for a file`() = runTest {
        io.mockk.coEvery {
            downloadUseCase.calculateFileSize(any(), any())
        } throws RuntimeException("gone")

        val viewModel = vm()
        advanceUntilIdle()

        // Every file in the model resolved as undetermined — never stuck on Loading.
        assertEquals(FileSizeStatus.UNKNOWN, viewModel.uiState.value.fileSizeStatus("model_G5.litertlm"))
        assertEquals(FileSizeStatus.UNKNOWN, viewModel.uiState.value.fileSizeStatus("model_G6.litertlm"))
    }

    @Test
    fun `paused file reports how many bytes are left on the device`() = runTest {
        val tmp = java.nio.file.Files.createTempDirectory("vm-test").toFile()
        val part = com.ai.tinymodels.domain.usecase.model.DownloadPaths
            .partFile(tmp, sampleModel.id, "model_G5.litertlm")
        part.parentFile?.mkdirs()
        part.writeBytes(ByteArray(30))
        every { appContext.filesDir } returns tmp
        io.mockk.coEvery { downloadUseCase.calculateFileSize(any(), any()) } returns 100L
        repo.seedFiles(file("model_G5.litertlm", FileDownloadStatus.PAUSED))

        val viewModel = vm()
        advanceUntilIdle()

        val state = viewModel.uiState.value.fileState("model_G5.litertlm")
        assertEquals(FileRowPhase.PAUSED, state.phase)
        assertEquals(30L, state.downloadedBytes)
        assertEquals(100L, state.totalBytes)
        assertEquals(0.3f, state.progress, 0.001f)
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
    private val statusUpdates = mutableListOf<Pair<String, FileDownloadStatus>>()

    /** How many times the parent downloaded_models row was removed. */
    var deleteCalls = 0
        private set

    fun seedFiles(vararg files: DownloadedModelFile) {
        modelFiles.value = files.toList()
    }

    fun statusOf(fileName: String): FileDownloadStatus? =
        statusUpdates.lastOrNull { it.first == fileName }?.second

    fun reset(details: ModelDetails) {
        detailsResult = AppResult.Success(details)
    }

    override suspend fun listModels(
        search: String?,
        filter: ModelFilter,
        limit: Int
    ): AppResult<CatalogPage> = throw NotImplementedError()

    override suspend fun getModelDetails(modelId: String): AppResult<ModelDetails> = detailsResult

    override fun observeDownloadedModels() = downloaded
    override suspend fun getDownloadedModel(modelId: String): DownloadedModel? = null
    override suspend fun deleteDownloadedModel(modelId: String) {
        deleteCalls++
    }

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
    ) {
        statusUpdates += fileName to status
        modelFiles.value = modelFiles.value.map {
            if (it.fileName == fileName) it.copy(status = status, error = error) else it
        }
    }
    override suspend fun deleteModelFile(modelId: String, fileName: String) {}
    override suspend fun exportModelFile(
        modelId: String,
        fileName: String,
        destinationUri: android.net.Uri
    ): AppResult<android.net.Uri> = AppResult.Error(AppError.Unknown("not supported in tests"))
    override suspend fun importModel(
        sourceUri: android.net.Uri
    ): AppResult<DownloadedModel> = AppResult.Error(AppError.Unknown("not supported in tests"))
}
