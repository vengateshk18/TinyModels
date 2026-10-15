package com.ai.tinymodels.feature.models

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
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
import com.ai.tinymodels.domain.repository.SettingsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Feature tests for [DownloadedFileDetailViewModel]: loading, chat gating,
 * per-file export reporting and delete/parent cleanup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadedFileDetailViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var repo: FakeFileDetailRepository
    private val modelManager = mockk<ModelManager>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = FakeFileDetailRepository()
        every { modelManager.loadedModelId } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun row(fileName: String, status: FileDownloadStatus = FileDownloadStatus.DOWNLOADED) =
        DownloadedModelFile(
            modelId = "m",
            fileName = fileName,
            status = status,
            sizeBytes = 10L,
            localPath = temp.root.absolutePath,
            error = null
        )

    private fun vm(fileName: String) = DownloadedFileDetailViewModel(
        SavedStateHandle(mapOf("modelId" to "m", "fileName" to fileName)),
        repo,
        settingsRepository,
        modelManager
    )

    private suspend fun awaitLoaded(viewModel: DownloadedFileDetailViewModel) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5_000) {
            if (!viewModel.uiState.value.isLoading) return
            delay(50)
        }
        error("Timed out waiting for file load")
    }

    private suspend fun awaitMessage(viewModel: DownloadedFileDetailViewModel): String {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5_000) {
            viewModel.uiState.value.exportMessage?.let { return it }
            delay(50)
        }
        error("Timed out waiting for export message")
    }

    @Test
    fun `loads file info`() = runBlocking {
        temp.newFile("f.litertlm")
        repo.files["f.litertlm"] = row("f.litertlm")
        val viewModel = vm("f.litertlm")

        awaitLoaded(viewModel)
        assertEquals("f.litertlm", viewModel.uiState.value.fileInfo?.fileName)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `missing db row reports error`() = runBlocking {
        val viewModel = vm("nope.litertlm")

        awaitLoaded(viewModel)
        assertEquals("File not found", viewModel.uiState.value.error)
    }

    @Test
    fun `start chat navigates when file exists`() = runBlocking {
        temp.newFile("f.litertlm")
        repo.files["f.litertlm"] = row("f.litertlm")
        val viewModel = vm("f.litertlm")
        awaitLoaded(viewModel)

        var navigated: String? = null
        viewModel.onStartChat { navigated = it }
        assertEquals("m", navigated)
    }

    @Test
    fun `start chat blocked when file gone from disk`() = runBlocking {
        repo.files["gone.litertlm"] = row("gone.litertlm")
        val viewModel = vm("gone.litertlm")
        awaitLoaded(viewModel)

        var navigated: String? = null
        viewModel.onStartChat { navigated = it }
        assertNull(navigated)
        assertTrue(
            viewModel.uiState.value.loadModelError?.contains("not found") == true
        )
    }

    @Test
    fun `export success reports file name`() = runBlocking {
        temp.newFile("f.litertlm")
        repo.files["f.litertlm"] = row("f.litertlm")
        repo.exportResult = AppResult.Success(mockk<Uri>())
        val viewModel = vm("f.litertlm")
        awaitLoaded(viewModel)

        viewModel.exportFile(mockk<Uri>())
        assertEquals("Exported f.litertlm", awaitMessage(viewModel))

        viewModel.consumeExportMessage()
        assertNull(viewModel.uiState.value.exportMessage)
    }

    @Test
    fun `export failure reports error`() = runBlocking {
        temp.newFile("f.litertlm")
        repo.files["f.litertlm"] = row("f.litertlm")
        repo.exportResult = AppResult.Error(AppError.Unknown("denied"))
        val viewModel = vm("f.litertlm")
        awaitLoaded(viewModel)

        viewModel.exportFile(mockk<Uri>())
        assertTrue(awaitMessage(viewModel).startsWith("Export failed:"))
    }

    @Test
    fun `delete removes file from disk and db`() = runBlocking {
        temp.newFile("f.litertlm")
        repo.files["f.litertlm"] = row("f.litertlm")
        repo.remainingFiles = emptyList()
        val viewModel = vm("f.litertlm")
        awaitLoaded(viewModel)

        var done = false
        viewModel.onDelete { done = true }

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5_000) {
            if (done) break
            delay(50)
        }
        assertTrue(done)
        assertTrue(repo.deletedFiles.contains("f.litertlm"))
        assertTrue(!File(temp.root, "f.litertlm").exists())
    }

    @Test
    fun `delete removes parent when no downloaded files remain`() = runBlocking {
        temp.newFile("f.litertlm")
        repo.files["f.litertlm"] = row("f.litertlm")
        repo.remainingFiles = emptyList()
        val viewModel = vm("f.litertlm")
        awaitLoaded(viewModel)

        var done = false
        viewModel.onDelete { done = true }
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5_000) {
            if (done) break
            delay(50)
        }
        assertTrue(repo.deletedParents.contains("m"))
    }

    @Test
    fun `delete keeps parent when other downloaded files remain`() = runBlocking {
        temp.newFile("f.litertlm")
        repo.files["f.litertlm"] = row("f.litertlm")
        repo.remainingFiles = listOf(row("other.litertlm"))
        val viewModel = vm("f.litertlm")
        awaitLoaded(viewModel)

        var done = false
        viewModel.onDelete { done = true }
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5_000) {
            if (done) break
            delay(50)
        }
        assertTrue(repo.deletedParents.isEmpty())
        coVerify(exactly = 0) { modelManager.unloadModel() }
    }
}

/** In-memory ModelRepository fake for the file-detail screen. */
private class FakeFileDetailRepository : ModelRepository {
    val files = mutableMapOf<String, DownloadedModelFile>()
    val deletedFiles = mutableListOf<String>()
    val deletedParents = mutableListOf<String>()
    var remainingFiles: List<DownloadedModelFile> = emptyList()
    var exportResult: AppResult<Uri> = AppResult.Error(AppError.Unknown("not stubbed"))

    override suspend fun getModelFile(modelId: String, fileName: String): DownloadedModelFile? =
        files[fileName]

    override suspend fun deleteModelFile(modelId: String, fileName: String) {
        deletedFiles.add(fileName)
        files.remove(fileName)
    }

    override fun observeModelFiles(modelId: String): Flow<List<DownloadedModelFile>> =
        MutableStateFlow(remainingFiles)

    override suspend fun deleteDownloadedModel(modelId: String) {
        deletedParents.add(modelId)
    }

    override suspend fun exportModelFile(
        modelId: String,
        fileName: String,
        destinationUri: Uri
    ): AppResult<Uri> = exportResult

    override fun observeDownloadedModels(): Flow<List<DownloadedModel>> =
        MutableStateFlow(emptyList())
    override fun observeDownloadedFiles(): Flow<List<DownloadedModelFile>> =
        MutableStateFlow(emptyList())
    override suspend fun listModels(
        search: String?,
        filter: ModelFilter,
        limit: Int
    ): AppResult<CatalogPage> = throw NotImplementedError()
    override suspend fun getModelDetails(modelId: String): AppResult<ModelDetails> =
        throw NotImplementedError()
    override suspend fun getDownloadedModel(modelId: String): DownloadedModel? = null
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
    override suspend fun importModel(sourceUri: Uri): AppResult<DownloadedModel> =
        AppResult.Error(AppError.Unknown("not stubbed"))
}
