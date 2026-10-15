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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun `cancel delegates to download use case`() = runBlocking {
        val viewModel = DownloadedModelsViewModel(repo, modelManager, downloadUseCase)
        val id = UUID.randomUUID()

        viewModel.cancelActiveDownload(id)
        coVerify { downloadUseCase.cancelActive(id) }
    }
}

/** In-memory ModelRepository fake for the downloaded-models screen. */
private class FakeDownloadedModelsRepository : ModelRepository {
    val downloadedFiles = MutableStateFlow<List<DownloadedModelFile>>(emptyList())
    val deletedModels = mutableListOf<String>()

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
    override fun observeModelFiles(modelId: String): Flow<List<DownloadedModelFile>> =
        MutableStateFlow(emptyList())
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
    override suspend fun exportModelFile(
        modelId: String,
        fileName: String,
        destinationUri: Uri
    ): AppResult<Uri> = AppResult.Error(AppError.Unknown("not stubbed"))
    override suspend fun importModel(sourceUri: Uri): AppResult<DownloadedModel> =
        AppResult.Error(AppError.Unknown("not stubbed"))
}
