package com.example.tinymodels.feature.home

import android.app.ActivityManager
import android.content.Context
import com.example.tinymodels.core.common.AppResult
import com.example.tinymodels.domain.model.ChatSummary
import com.example.tinymodels.domain.model.DownloadedModel
import com.example.tinymodels.domain.model.DownloadedModelFile
import com.example.tinymodels.domain.model.FileDownloadStatus
import com.example.tinymodels.domain.model.ModelDetails
import com.example.tinymodels.domain.model.ModelSummary
import com.example.tinymodels.domain.repository.ChatRepository
import com.example.tinymodels.domain.repository.ModelRepository
import com.example.tinymodels.domain.repository.SettingsRepository
import com.example.tinymodels.domain.usecase.model.DownloadModelUseCase
import com.example.tinymodels.feature.home.model.HomeUiState
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

/**
 * Feature tests for [HomeViewModel]: dashboard assembly from the
 * repository layer (models, files, usage stats, last chat).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val modelRepo = FakeHomeModelRepository()
    private val chatRepo = FakeHomeChatRepository()
    private val settingsRepo = FakeHomeSettingsRepository()
    private val downloadUseCase = mockk<DownloadModelUseCase>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { context.filesDir } returns temp.root
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns
            mockk<ActivityManager>(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = HomeViewModel(context, modelRepo, chatRepo, settingsRepo, downloadUseCase)

    private suspend fun awaitDashboard(): HomeUiState.Dashboard {
        val viewModel = vm()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 10_000) {
            val state = viewModel.uiState.value
            if (state is HomeUiState.Dashboard) return state
            delay(50)
        }
        error("Timed out waiting for Dashboard, got ${viewModel.uiState.value}")
    }

    @Test
    fun `dashboard lists models newest first with sizes`() = runBlocking {
        modelRepo.models.value = listOf(
            DownloadedModel("old/m", "old", null, null, "/tmp", 100L),
            DownloadedModel("new/m", "new", null, null, "/tmp", 200L)
        )
        modelRepo.files.value = listOf(
            DownloadedModelFile(
                "old/m", "a.litertlm", FileDownloadStatus.DOWNLOADED,
                100L, "/tmp", null
            ),
            DownloadedModelFile(
                "new/m", "b.litertlm", FileDownloadStatus.DOWNLOADED,
                300L, "/tmp", null
            ),
            DownloadedModelFile(
                "new/m", "c.litertlm", FileDownloadStatus.DOWNLOADED,
                100L, "/tmp", null
            )
        )

        val dashboard = awaitDashboard()

        assertEquals(listOf("new/m", "old/m"), dashboard.models.map { it.modelId })
        assertEquals(400L, dashboard.models.first().sizeBytes)
        assertEquals(2, dashboard.models.first().fileCount)
    }

    @Test
    fun `dashboard aggregates usage stats and last chat`() = runBlocking {
        modelRepo.models.value = listOf(
            DownloadedModel("m", "a", null, null, "/tmp", 1L)
        )
        modelRepo.files.value = listOf(
            DownloadedModelFile(
                "m", "a.litertlm", FileDownloadStatus.DOWNLOADED,
                400L, "/tmp", null
            )
        )

        val dashboard = awaitDashboard()

        assertEquals(3, dashboard.usage.totalChats)
        assertEquals(50, dashboard.usage.totalMessages)
        assertEquals(1_000L, dashboard.usage.totalTokens)
        assertEquals("chat-1", dashboard.lastChat?.id)
        assertEquals(2.5f, dashboard.tokensPerSecond)
        assertEquals(400L, dashboard.storage.modelBytes)
    }

    @Test
    fun `dashboard marks last used model`() = runBlocking {
        settingsRepo.lastUsedModelId = "b/m"
        modelRepo.models.value = listOf(
            DownloadedModel("a/m", "a", null, null, "/tmp", 100L),
            DownloadedModel("b/m", "b", null, null, "/tmp", 200L)
        )
        modelRepo.lastUsedFile = DownloadedModelFile(
            "b/m", "b.litertlm", FileDownloadStatus.DOWNLOADED, 10L, "/tmp", null
        )

        val dashboard = awaitDashboard()

        assertEquals("b/m", dashboard.lastUsedModel?.modelId)
        assertEquals("b.litertlm", dashboard.lastUsedFileName)
        assertTrue(dashboard.models.single { it.modelId == "b/m" }.isLastUsed)
    }
}

/** ModelRepository fake for Home dashboard tests. */
private class FakeHomeModelRepository : ModelRepository {
    val models = MutableStateFlow<List<DownloadedModel>>(emptyList())
    val files = MutableStateFlow<List<DownloadedModelFile>>(emptyList())
    var lastUsedFile: DownloadedModelFile? = null

    override fun observeDownloadedModels(): Flow<List<DownloadedModel>> = models
    override fun observeDownloadedFiles(): Flow<List<DownloadedModelFile>> = files
    override suspend fun getDownloadedModel(modelId: String): DownloadedModel? =
        models.value.singleOrNull { it.modelId == modelId }
    override suspend fun getDownloadedFileForModel(modelId: String): DownloadedModelFile? =
        lastUsedFile

    override suspend fun listModels(): AppResult<List<ModelSummary>> = throw NotImplementedError()
    override suspend fun listModels(search: String?, pipelineTag: String?): AppResult<List<ModelSummary>> =
        throw NotImplementedError()
    override suspend fun getModelDetails(modelId: String): AppResult<ModelDetails> =
        throw NotImplementedError()
    override suspend fun deleteDownloadedModel(modelId: String) {}
    override fun observeModelFiles(modelId: String): Flow<List<DownloadedModelFile>> =
        MutableStateFlow(emptyList())
    override suspend fun getModelFile(modelId: String, fileName: String): DownloadedModelFile? = null
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
        destinationUri: android.net.Uri
    ): com.example.tinymodels.core.common.AppResult<android.net.Uri> =
        throw NotImplementedError()
    override suspend fun importModel(sourceUri: android.net.Uri): com.example.tinymodels.core.common.AppResult<DownloadedModel> =
        throw NotImplementedError()
}

/** ChatRepository fake with fixed usage stats. */
private class FakeHomeChatRepository : ChatRepository {
    override fun observeChatSummaries(): Flow<List<ChatSummary>> = flowOf(
        listOf(ChatSummary("chat-1", "Hello", "m", "hi", 123L))
    )

    override suspend fun totalChats(): Int = 3
    override suspend fun totalMessages(): Int = 50
    override suspend fun totalTokensGenerated(): Long = 1_000L
    override suspend fun modelsTried(): Int = 2
    override suspend fun chatsSince(sinceMs: Long): Int = 1
    override suspend fun totalCompactions(): Int = 0
    override suspend fun webSearchChats(): Int = 0
    override suspend fun favoriteModelId(): String? = null
    override suspend fun getLastSessionTokensPerSecond(): Float? = 2.5f

    override fun observeMessages(chatId: String) = MutableStateFlow(emptyList<com.example.tinymodels.domain.model.ChatMessage>())
    override suspend fun getMessages(chatId: String) = emptyList<com.example.tinymodels.domain.model.ChatMessage>()
    override suspend fun getChat(chatId: String) = null
    override suspend fun createChat(
        modelId: String,
        title: String,
        defaultBackend: com.example.tinymodels.domain.model.BackendPreference,
        webSearchEnabled: Boolean
    ): com.example.tinymodels.domain.model.Chat = throw NotImplementedError()
    override suspend fun renameChat(chatId: String, title: String) {}
    override suspend fun updateInferenceSettings(
        chatId: String,
        settings: com.example.tinymodels.domain.model.InferenceSettings
    ) {}
    override suspend fun saveCompaction(chatId: String, summary: String?, cutoffCreatedAt: Long?) {}
    override suspend fun saveMessage(message: com.example.tinymodels.domain.model.ChatMessage) {}
    override suspend fun updateWebSearchEnabled(chatId: String, enabled: Boolean) {}
    override suspend fun deleteMessage(messageId: String) {}
    override suspend fun deleteChat(chatId: String) {}
    override suspend fun clearAllChats() {}
    override suspend fun messageCount(chatId: String): Int = 0
}

/** SettingsRepository fake with a configurable last-used model. */
private class FakeHomeSettingsRepository : com.example.tinymodels.domain.repository.SettingsRepository {
    var lastUsedModelId: String? = null

    override suspend fun getLastUsedModelId(): String? = lastUsedModelId

    override val settings: Flow<com.example.tinymodels.domain.model.AppSettings> =
        MutableStateFlow(com.example.tinymodels.domain.model.AppSettings())
    override val huggingFaceToken: Flow<String?> = MutableStateFlow(null)
    override suspend fun getSettings() = com.example.tinymodels.domain.model.AppSettings()
    override suspend fun getHuggingFaceToken(): String? = null
    override suspend fun setHuggingFaceToken(token: String?) {}
    override suspend fun setThemeMode(mode: com.example.tinymodels.domain.model.ThemeMode) {}
    override suspend fun setDynamicColor(enabled: Boolean) {}
    override suspend fun setFontChoice(choice: com.example.tinymodels.domain.model.FontChoice) {}
    override suspend fun setFontScale(scale: Float) {}
    override suspend fun setDefaultBackend(backend: com.example.tinymodels.domain.model.BackendPreference) {}
    override suspend fun setWebSearchEnabled(enabled: Boolean) {}
    override suspend fun setWifiOnlyDownloads(enabled: Boolean) {}
    override suspend fun setAutoLoadLastModel(enabled: Boolean) {}
    override suspend fun setKeepScreenOn(enabled: Boolean) {}
    override suspend fun setShowTokenCounts(enabled: Boolean) {}
    override suspend fun setLastUsedModelId(modelId: String) {}
}
