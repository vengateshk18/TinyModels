package com.ai.tinymodels.feature.models

import android.net.Uri
import com.ai.tinymodels.core.common.AppError
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.inference.ModelManager
import com.ai.tinymodels.domain.model.DownloadedModel
import com.ai.tinymodels.domain.model.DownloadedModelFile
import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.model.ModelDetails
import com.ai.tinymodels.domain.model.CatalogPage
import com.ai.tinymodels.domain.model.ModelFilter
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.usecase.model.ActiveDownload
import com.ai.tinymodels.domain.usecase.model.DownloadModelUseCase
import com.ai.tinymodels.domain.usecase.model.DownloadStatus
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * Feature tests for [DownloadedModelsViewModel]: the on-disk availability
 * filter, storage totals and delete/active-model handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadedModelsViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val repo = FakeDownloadedModelsRepository()
    private val modelManager = mockk<ModelManager>(relaxed = true)
    private val downloadUseCase = mockk<DownloadModelUseCase>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { downloadUseCase.observeActiveDownloads() } returns flowOf(emptyList())
        every { modelManager.loadedModelId } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun presentFile(name: String = "present.litertlm", size: Long = 100L) =
        DownloadedModelFile(
            modelId = "m",
            fileName = name,
            status = FileDownloadStatus.DOWNLOADED,
            sizeBytes = size,
            localPath = temp.root.absolutePath,
            error = null
        ).also { temp.newFile(name) }

    private suspend fun awaitFiles(
        viewModel: DownloadedModelsViewModel,
        expected: Int
    ): List<DownloadedModelFile> {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5_000) {
            val current = viewModel.downloadedFiles.value
            if (current.size == expected) return current
            delay(50)
        }
        error("Timed out waiting for $expected files, got ${viewModel.downloadedFiles.value}")
    }

    @Test
    fun `files missing on disk are hidden`() = runBlocking {
        repo.downloadedFiles.value = listOf(
            presentFile(),
            DownloadedModelFile(
                modelId = "m",
                fileName = "gone.litertlm",
                status = FileDownloadStatus.DOWNLOADED,
                sizeBytes = 50L,
                localPath = temp.root.absolutePath,
                error = null
            )
        )
        val viewModel = DownloadedModelsViewModel(repo, modelManager, downloadUseCase)

        val visible = awaitFiles(viewModel, expected = 1)
        assertEquals("present.litertlm", visible.single().fileName)
    }

    @Test
    fun `imported files are shown with flag preserved`() = runBlocking {
        val imported = presentFile("imported.litertlm").copy(isImported = true)
        repo.downloadedFiles.value = listOf(imported)
        val viewModel = DownloadedModelsViewModel(repo, modelManager, downloadUseCase)

        val visible = awaitFiles(viewModel, expected = 1)
        assertTrue(visible.single().isImported)
    }

    @Test
    fun `total size sums only visible files`() = runBlocking {
        repo.downloadedFiles.value = listOf(
            presentFile("a.litertlm", size = 100L),
            presentFile("b.litertlm", size = 200L),
            DownloadedModelFile(
                modelId = "m",
                fileName = "gone.litertlm",
                status = FileDownloadStatus.DOWNLOADED,
                sizeBytes = 9_999L,
                localPath = temp.root.absolutePath,
                error = null
            )
        )
        val viewModel = DownloadedModelsViewModel(repo, modelManager, downloadUseCase)

        awaitFiles(viewModel, expected = 2)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5_000) {
            if (viewModel.totalSizeBytes.value == 300L) break
            delay(50)
        }
        assertEquals(300L, viewModel.totalSizeBytes.value)
    }

    @Test
    fun `delete unloads active model first`() = runBlocking {
        every { modelManager.loadedModelId } returns "m"
        val viewModel = DownloadedModelsViewModel(repo, modelManager, downloadUseCase)
        val model = DownloadedModel("m", null, null, null, temp.root.absolutePath, 0L)

        viewModel.delete(model)

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5_000) {
            if (repo.deletedModels.contains("m")) break
            delay(50)
        }
        coVerify { modelManager.unloadModel() }
        assertTrue(repo.deletedModels.contains("m"))
    }

    @Test
    fun `pausing an active download stops the job and marks the file paused`() = runBlocking {
        repo.seedFile(fileName = "a.litertlm", status = FileDownloadStatus.DOWNLOADING)
        val viewModel = DownloadedModelsViewModel(repo, modelManager, downloadUseCase)
        val download = ActiveDownload(UUID.randomUUID(), "m", "a.litertlm", DownloadStatus.DOWNLOADING)

        viewModel.pauseDownload(download)

        awaitStatus("m", "a.litertlm", FileDownloadStatus.PAUSED)
        coVerify { downloadUseCase.cancelActive(download.workId) }
    }

    @Test
    fun `pausing never downgrades a finished file`() = runBlocking {
        repo.seedFile(fileName = "done.litertlm", status = FileDownloadStatus.DOWNLOADED)
        val viewModel = DownloadedModelsViewModel(repo, modelManager, downloadUseCase)
        val download = ActiveDownload(UUID.randomUUID(), "m", "done.litertlm", DownloadStatus.DOWNLOADING)

        viewModel.pauseDownload(download)
        delay(150)

        assertEquals(FileDownloadStatus.DOWNLOADED, repo.statusOf("m", "done.litertlm"))
    }

    @Test
    fun `pausing a whole-model job marks every unfinished file paused`() = runBlocking {
        repo.seedFile(fileName = "a.litertlm", status = FileDownloadStatus.DOWNLOADING)
        repo.seedFile(fileName = "b.litertlm", status = FileDownloadStatus.NOT_DOWNLOADED)
        repo.seedFile(fileName = "c.litertlm", status = FileDownloadStatus.DOWNLOADED)
        val viewModel = DownloadedModelsViewModel(repo, modelManager, downloadUseCase)
        val download = ActiveDownload(UUID.randomUUID(), "m", null, DownloadStatus.DOWNLOADING)

        viewModel.pauseDownload(download)

        awaitStatus("m", "a.litertlm", FileDownloadStatus.PAUSED)
        awaitStatus("m", "b.litertlm", FileDownloadStatus.PAUSED)
        assertEquals(FileDownloadStatus.DOWNLOADED, repo.statusOf("m", "c.litertlm"))
        coVerify { downloadUseCase.cancelActive(download.workId) }
    }

    @Test
    fun `resume re-enqueues the file and marks it downloading`() = runBlocking {
        repo.seedFile(fileName = "a.litertlm", status = FileDownloadStatus.PAUSED, sizeBytes = 100L)
        val viewModel = DownloadedModelsViewModel(repo, modelManager, downloadUseCase)

        viewModel.resumeFile("m", "a.litertlm")

        awaitStatus("m", "a.litertlm", FileDownloadStatus.DOWNLOADING)
        coVerify { downloadUseCase.executeFile(any(), eq("a.litertlm"), eq(100L), replace = true) }
    }

    @Test
    fun `discard stops the job, throws away the partial and resets the row`() = runBlocking {
        repo.seedFile(fileName = "a.litertlm", status = FileDownloadStatus.PAUSED)
        val viewModel = DownloadedModelsViewModel(repo, modelManager, downloadUseCase)
        val workId = UUID.randomUUID()

        viewModel.discardDownload(workId, "m", "a.litertlm")

        awaitStatus("m", "a.litertlm", FileDownloadStatus.NOT_DOWNLOADED)
        coVerify { downloadUseCase.cancelActiveAndWait(workId) }
        coVerify { downloadUseCase.discardPartial("m", "a.litertlm") }
    }

    @Test
    fun `discarding a whole-model job drops every partial and resets only unfinished rows`() =
        runBlocking {
            repo.seedFile(fileName = "a.litertlm", status = FileDownloadStatus.PAUSED)
            repo.seedFile(fileName = "b.litertlm", status = FileDownloadStatus.DOWNLOADING)
            repo.seedFile(fileName = "c.litertlm", status = FileDownloadStatus.DOWNLOADED)
            val viewModel = DownloadedModelsViewModel(repo, modelManager, downloadUseCase)
            val workId = UUID.randomUUID()

            viewModel.discardDownload(workId, "m", null)

            awaitStatus("m", "a.litertlm", FileDownloadStatus.NOT_DOWNLOADED)
            awaitStatus("m", "b.litertlm", FileDownloadStatus.NOT_DOWNLOADED)
            assertEquals(FileDownloadStatus.DOWNLOADED, repo.statusOf("m", "c.litertlm"))
            coVerify { downloadUseCase.cancelActiveAndWait(workId) }
            coVerify { downloadUseCase.discardModelPartials("m") }
        }

    @Test
    fun `paused files are exposed for the section`() = runBlocking {
        val paused = DownloadedModelFile(
            modelId = "m",
            fileName = "p.litertlm",
            status = FileDownloadStatus.PAUSED,
            sizeBytes = 42L,
            localPath = temp.root.absolutePath,
            error = null
        )
        repo.pausedFiles.value = listOf(paused)
        val viewModel = DownloadedModelsViewModel(repo, modelManager, downloadUseCase)

        // Subscribing triggers WhileSubscribed upstream collection.
        val names = viewModel.pausedFiles.first { it.isNotEmpty() }.map { it.fileName }
        assertEquals(listOf("p.litertlm"), names)
    }

    /** Polls the fake repo until [fileName] reaches [expected] (Room lags the call). */
    private suspend fun awaitStatus(
        modelId: String,
        fileName: String,
        expected: FileDownloadStatus
    ) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5_000) {
            if (repo.statusOf(modelId, fileName) == expected) return
            delay(50)
        }
        error(
            "Timed out waiting for $expected, got ${repo.statusOf(modelId, fileName)} " +
                "(updates: ${repo.statusUpdates})"
        )
    }
}

/** In-memory ModelRepository fake for the downloaded-models screen. */
private class FakeDownloadedModelsRepository : ModelRepository {
    val downloadedFiles = MutableStateFlow<List<DownloadedModelFile>>(emptyList())
    val deletedModels = mutableListOf<String>()

    /** All model files this fake knows about — backs the file queries below. */
    val modelFiles = MutableStateFlow<List<DownloadedModelFile>>(emptyList())

    /** Files the screen should render in its "Paused" group. */
    val pausedFiles = MutableStateFlow<List<DownloadedModelFile>>(emptyList())

    /** Every status write, as "modelId/fileName" to the new status. */
    val statusUpdates = mutableListOf<Pair<String, FileDownloadStatus>>()

    /** Inserts (or replaces) a file row so tests can seed a known state. */
    fun seedFile(
        modelId: String = "m",
        fileName: String,
        status: FileDownloadStatus,
        sizeBytes: Long = 100L
    ) {
        modelFiles.value = modelFiles.value.filterNot {
            it.modelId == modelId && it.fileName == fileName
        } + DownloadedModelFile(
            modelId = modelId,
            fileName = fileName,
            status = status,
            sizeBytes = sizeBytes,
            localPath = "/tmp",
            error = null
        )
    }

    fun statusOf(modelId: String, fileName: String): FileDownloadStatus? =
        modelFiles.value.firstOrNull { it.modelId == modelId && it.fileName == fileName }?.status

    override fun observeDownloadedFiles(): Flow<List<DownloadedModelFile>> = downloadedFiles
    override fun observeDownloadedModels(): Flow<List<DownloadedModel>> =
        MutableStateFlow(emptyList())

    override suspend fun deleteDownloadedModel(modelId: String) {
        deletedModels.add(modelId)
    }

    override suspend fun listModels(
        search: String?,
        filter: ModelFilter,
        limit: Int
    ): AppResult<CatalogPage> = throw NotImplementedError()
    override suspend fun getModelDetails(modelId: String): AppResult<ModelDetails> =
        throw NotImplementedError()
    override suspend fun getDownloadedModel(modelId: String): DownloadedModel? = null
    override fun observeModelFiles(modelId: String): Flow<List<DownloadedModelFile>> = modelFiles
    override fun observePausedFiles(): Flow<List<DownloadedModelFile>> = pausedFiles
    override suspend fun getModelFile(modelId: String, fileName: String): DownloadedModelFile? =
        modelFiles.value.firstOrNull { it.modelId == modelId && it.fileName == fileName }
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
        statusUpdates += "$modelId/$fileName" to status
        modelFiles.value = modelFiles.value.map {
            if (it.modelId == modelId && it.fileName == fileName) {
                it.copy(
                    status = status,
                    sizeBytes = sizeBytes ?: it.sizeBytes,
                    localPath = localPath ?: it.localPath,
                    error = error ?: it.error
                )
            } else {
                it
            }
        }
    }
    override suspend fun deleteModelFile(modelId: String, fileName: String) {}
    override suspend fun exportModelFile(
        modelId: String,
        fileName: String,
        destinationUri: Uri
    ): AppResult<Uri> = AppResult.Error(AppError.Unknown("not stubbed"))
    override suspend fun importModel(sourceUri: Uri): AppResult<DownloadedModel> =
        AppResult.Error(AppError.Unknown("not stubbed"))
}
