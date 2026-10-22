package com.ai.tinymodels.feature.settings

import android.net.Uri
import com.ai.tinymodels.R
import com.ai.tinymodels.core.common.AppError
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.common.AppLocaleStore
import com.ai.tinymodels.core.common.fakeStringContext
import com.ai.tinymodels.domain.model.AppSettings
import com.ai.tinymodels.domain.model.BackendPreference
import com.ai.tinymodels.domain.model.DownloadedModel
import com.ai.tinymodels.domain.model.DownloadedModelFile
import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.model.FontChoice
import com.ai.tinymodels.domain.model.ModelDetails
import com.ai.tinymodels.domain.model.ModelSummary
import com.ai.tinymodels.domain.model.ThemeMode
import com.ai.tinymodels.domain.repository.ChatRepository
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.repository.SettingsRepository
import io.mockk.coVerify
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
import org.junit.Test

/**
 * Feature tests for [SettingsViewModel]: import reporting, HF token
 * management and chat-history clearing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val settingsRepo = FakeSettingsRepository()
    private val chatRepo = mockk<ChatRepository>(relaxed = true)
    private val modelRepo = FakeImportModelRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val context = fakeStringContext()

    /** Real store over a mocked Context — the language preference has no bearing here. */
    private val appLocaleStore = AppLocaleStore(context)

    private fun vm() = SettingsViewModel(context, appLocaleStore, settingsRepo, chatRepo, modelRepo)

    private suspend fun awaitResult(viewModel: SettingsViewModel): String {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5_000) {
            viewModel.clearResult.value?.let { return it }
            delay(50)
        }
        error("Timed out waiting for clearResult")
    }

    @Test
    fun `import success reports model id`() = runBlocking {
        modelRepo.importResult = AppResult.Success(
            DownloadedModel("a/b", null, null, null, "/tmp", 0L)
        )
        val viewModel = vm()

        viewModel.importModel(mockk<Uri>())
        assertEquals(
            "${R.string.settings_model_imported}(a/b)",
            awaitResult(viewModel)
        )
    }

    @Test
    fun `import failure reports error`() = runBlocking {
        modelRepo.importResult = AppResult.Error(AppError.Unknown("bad file"))
        val viewModel = vm()

        viewModel.importModel(mockk<Uri>())
        assertTrue(awaitResult(viewModel).startsWith(R.string.settings_import_failed.toString()))
    }

    @Test
    fun `consumeClearResult clears message`() = runBlocking {
        modelRepo.importResult = AppResult.Error(AppError.Unknown("bad file"))
        val viewModel = vm()

        viewModel.importModel(mockk<Uri>())
        awaitResult(viewModel)
        viewModel.consumeClearResult()
        assertNull(viewModel.clearResult.value)
    }

    @Test
    fun `saving token reports saved and persists`() = runBlocking {
        val viewModel = vm()

        viewModel.setHuggingFaceToken("hf_test123")
        assertEquals(R.string.settings_token_saved.toString(), awaitResult(viewModel))
        assertEquals("hf_test123", settingsRepo.savedToken)
    }

    @Test
    fun `clearing token reports removed`() = runBlocking {
        val viewModel = vm()

        viewModel.setHuggingFaceToken(null)
        assertEquals(R.string.settings_token_removed.toString(), awaitResult(viewModel))
        assertNull(settingsRepo.savedToken)
    }

    @Test
    fun `clear chat history delegates and reports`() = runBlocking {
        val viewModel = vm()

        viewModel.clearChatHistory()
        assertEquals(R.string.settings_chat_history_cleared.toString(), awaitResult(viewModel))
        coVerify { chatRepo.clearAllChats() }
    }
}

/** SettingsRepository fake with in-memory settings + token. */
private class FakeSettingsRepository : SettingsRepository {
    val settingsFlow = MutableStateFlow(AppSettings())
    val tokenFlow = MutableStateFlow<String?>(null)
    var savedToken: String? = null

    override val settings: Flow<AppSettings> = settingsFlow
    override val huggingFaceToken: Flow<String?> = tokenFlow

    override suspend fun getSettings(): AppSettings = settingsFlow.value
    override suspend fun getHuggingFaceToken(): String? = savedToken
    override suspend fun setHuggingFaceToken(token: String?) {
        savedToken = token
        tokenFlow.value = token
    }

    override suspend fun setThemeMode(mode: ThemeMode) {}
    override suspend fun setDynamicColor(enabled: Boolean) {}
    override suspend fun setFontChoice(choice: FontChoice) {}
    override suspend fun setFontScale(scale: Float) {}
    override suspend fun setDefaultBackend(backend: BackendPreference) {}
    override suspend fun setWebSearchEnabled(enabled: Boolean) {}
    override suspend fun setWifiOnlyDownloads(enabled: Boolean) {}
    override suspend fun setAutoLoadLastModel(enabled: Boolean) {}
    override suspend fun setKeepScreenOn(enabled: Boolean) {}
    override suspend fun setShowTokenCounts(enabled: Boolean) {}
    override suspend fun getLastUsedModelId(): String? = null
    override suspend fun setLastUsedModelId(modelId: String) {}
}

/** ModelRepository fake with a configurable import result. */
private class FakeImportModelRepository : ModelRepository {
    var importResult: AppResult<DownloadedModel> =
        AppResult.Error(AppError.Unknown("not stubbed"))

    override suspend fun importModel(sourceUri: Uri): AppResult<DownloadedModel> = importResult

    override fun observeDownloadedModels(): Flow<List<DownloadedModel>> =
        MutableStateFlow(emptyList())

    override suspend fun listModels(): AppResult<List<ModelSummary>> = throw NotImplementedError()
    override suspend fun listModels(search: String?, pipelineTag: String?): AppResult<List<ModelSummary>> =
        throw NotImplementedError()
    override suspend fun getModelDetails(modelId: String): AppResult<ModelDetails> =
        throw NotImplementedError()
    override suspend fun getDownloadedModel(modelId: String): DownloadedModel? = null
    override suspend fun deleteDownloadedModel(modelId: String) {}
    override fun observeModelFiles(modelId: String): Flow<List<DownloadedModelFile>> =
        MutableStateFlow(emptyList())
    override fun observeDownloadedFiles(): Flow<List<DownloadedModelFile>> =
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
}
