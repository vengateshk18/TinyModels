package com.ai.tinymodels.feature.home

import android.app.ActivityManager
import android.content.Context
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.domain.model.ChatSummary
import com.ai.tinymodels.domain.model.DownloadedModel
import com.ai.tinymodels.domain.model.DownloadedModelFile
import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.model.ModelDetails
import com.ai.tinymodels.domain.model.ModelSummary
import com.ai.tinymodels.domain.repository.ChatRepository
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.repository.SettingsRepository
import com.ai.tinymodels.domain.usecase.model.DownloadModelUseCase
import com.ai.tinymodels.domain.usecase.model.DownloadPaths
import com.ai.tinymodels.feature.home.model.HomeUiState
import com.ai.tinymodels.feature.home.model.RecommendedModel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Waits until [predicate] holds for [viewModel]'s dashboard state. */
    private suspend fun awaitDashboardOf(
        viewModel: HomeViewModel,
        predicate: (HomeUiState.Dashboard) -> Boolean
    ): HomeUiState.Dashboard {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 10_000) {
            val state = viewModel.uiState.value
            if (state is HomeUiState.Dashboard && predicate(state)) return state
            delay(50)
        }
        error("Timed out waiting for dashboard to satisfy predicate, got ${viewModel.uiState.value}")
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

    /**
     * Fresh-install flow: the very first model was just downloaded and never
     * opened. The launchpad card must show THAT model ("Create a chat with
     * …") — not a generic "Your model" placeholder.
     */
    @Test
    fun `dashboard falls back to the newest downloaded model when nothing was last used`() = runBlocking {
        modelRepo.models.value = listOf(
            DownloadedModel("old/m", "old", null, null, "/tmp", 100L),
            DownloadedModel("new/m", "new", null, null, "/tmp", 200L)
        )
        modelRepo.fileByModel["new/m"] = DownloadedModelFile(
            "new/m", "new.litertlm", FileDownloadStatus.DOWNLOADED, 300L, "/tmp", null
        )

        val dashboard = awaitDashboard()

        assertEquals("new/m", dashboard.lastUsedModel?.modelId)
        assertEquals("new.litertlm", dashboard.lastUsedFileName)
        // Nothing has been chatted with yet, so no row is chip-marked — the
        // launch-card fallback is independent of the "last used" badge.
        assertTrue(dashboard.models.none { it.isLastUsed })
    }

    /**
     * Returning to Home after creating chats elsewhere must reflect the new
     * state — chat activity is a refresh trigger just like downloads are.
     */
    @Test
    fun `dashboard picks up newly created chats without touching model downloads`() = runBlocking {
        modelRepo.models.value = listOf(
            DownloadedModel("m", "m", null, null, "/tmp", 1L)
        )
        modelRepo.files.value = listOf(
            DownloadedModelFile("m", "a.litertlm", FileDownloadStatus.DOWNLOADED, 400L, "/tmp", null)
        )
        // ONE ViewModel across the whole test — the Home tab survives tab
        // switches, so a second instance would mask the staleness under test.
        val viewModel = vm()
        val dashboard1 = awaitDashboardOf(viewModel) { it.lastChat?.id == "chat-1" }
        assertEquals("chat-1", dashboard1.lastChat?.id)

        // A chat is created in the Chat tab while Home is in the background
        // (downloads did NOT change — the models flow never re-emits).
        chatRepo.summaries.value = listOf(
            ChatSummary("chat-2", "Second", "m", "hello", 456L)
        )

        val dashboard2 = awaitDashboardOf(viewModel) { it.lastChat?.id == "chat-2" }
        assertEquals("chat-2", dashboard2.lastChat?.id)
    }

    // ---------------------------------------------------------------------
    // Recommended-download pause / resume / discard (the card's controls).
    // ---------------------------------------------------------------------

    @Test
    fun `a paused recommended download is detected when home opens`() = runBlocking {
        // Whichever tier pickForDevice chooses must be among the seeded files.
        RecommendedModels.all.forEach {
            modelRepo.seedFile(it.modelId, it.fileName, FileDownloadStatus.PAUSED)
            val part = DownloadPaths.partFile(temp.root, it.modelId, it.fileName)
            part.parentFile?.mkdirs()
            part.writeBytes(ByteArray(256))
        }
        val viewModel = vm()

        awaitFirstTime(viewModel)

        assertTrue(
            "expected the card to know its download is paused",
            viewModel.recommendedPaused.value
        )
        assertEquals(256L, viewModel.recommendedPausedBytes.value)
    }

    @Test
    fun `pausing the recommended download keeps the partial and flags the card`() = runBlocking {
        val model = RecommendedModels.SMALL
        modelRepo.seedFile(model.modelId, model.fileName, FileDownloadStatus.DOWNLOADING)
        val part = DownloadPaths.partFile(temp.root, model.modelId, model.fileName)
        part.parentFile?.mkdirs()
        part.writeBytes(ByteArray(512))
        val viewModel = vm()
        awaitFirstTime(viewModel)

        viewModel.pauseRecommendedDownload(model)

        awaitStatus(model, FileDownloadStatus.PAUSED)
        assertTrue(viewModel.recommendedPaused.value)
        assertEquals(512L, viewModel.recommendedPausedBytes.value)
        coVerify { downloadUseCase.cancelFile(model.modelId, model.fileName) }
    }

    @Test
    fun `resuming clears the paused flag and re-enqueues the file`() = runBlocking {
        val model = RecommendedModels.SMALL
        modelRepo.seedFile(model.modelId, model.fileName, FileDownloadStatus.PAUSED)
        val viewModel = vm()
        awaitFirstTime(viewModel)
        viewModel.pauseRecommendedDownload(model)
        assertTrue(viewModel.recommendedPaused.value)

        viewModel.resumeRecommendedDownload(model)

        assertFalse(viewModel.recommendedPaused.value)
        awaitStatus(model, FileDownloadStatus.DOWNLOADING)
        awaitVerify {
            coVerify { downloadUseCase.executeFile(any(), eq(model.fileName), any(), replace = true) }
        }
    }

    @Test
    fun `discarding the recommended download resets the row and clears the flag`() = runBlocking {
        val model = RecommendedModels.SMALL
        modelRepo.seedFile(model.modelId, model.fileName, FileDownloadStatus.DOWNLOADING)
        val part = DownloadPaths.partFile(temp.root, model.modelId, model.fileName)
        part.parentFile?.mkdirs()
        part.writeBytes(ByteArray(128))
        val viewModel = vm()
        awaitFirstTime(viewModel)
        viewModel.pauseRecommendedDownload(model)
        awaitStatus(model, FileDownloadStatus.PAUSED)

        viewModel.discardRecommendedDownload(model)

        awaitStatus(model, FileDownloadStatus.NOT_DOWNLOADED)
        assertFalse(viewModel.recommendedPaused.value)
        assertEquals(0L, viewModel.recommendedPausedBytes.value)
        coVerify { downloadUseCase.cancelFileAndWait(model.modelId, model.fileName) }
        coVerify { downloadUseCase.discardPartial(model.modelId, model.fileName) }
    }

    // ---------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------

    /** Waits until the fresh-install (no models yet) state is showing. */
    private suspend fun awaitFirstTime(viewModel: HomeViewModel) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 10_000) {
            if (viewModel.uiState.value is HomeUiState.FirstTime) return
            delay(50)
        }
        error("Timed out waiting for FirstTime, got ${viewModel.uiState.value}")
    }

    /** Polls the fake repo until [model]'s file reaches [expected]. */
    private suspend fun awaitStatus(model: RecommendedModel, expected: FileDownloadStatus) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5_000) {
            if (modelRepo.statusOf(model.modelId, model.fileName) == expected) return
            delay(50)
        }
        error(
            "Timed out waiting for $expected, got " +
                "${modelRepo.statusOf(model.modelId, model.fileName)} " +
                "(updates: ${modelRepo.statusUpdates})"
        )
    }

    /**
     * Re-runs [block] until it stops throwing (for verifications against the
     * download's background dispatcher).
     */
    private suspend fun awaitVerify(block: () -> Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            try {
                block()
                return
            } catch (e: Throwable) {
                if (System.currentTimeMillis() - start > 5_000) throw e
                delay(50)
            }
        }
    }
}

/** ModelRepository fake for Home dashboard tests. */
private class FakeHomeModelRepository : ModelRepository {
    val models = MutableStateFlow<List<DownloadedModel>>(emptyList())
    val files = MutableStateFlow<List<DownloadedModelFile>>(emptyList())
    var lastUsedFile: DownloadedModelFile? = null

    /** Per-model files — [lastUsedFile] stays as the legacy catch-all hook. */
    val fileByModel = mutableMapOf<String, DownloadedModelFile>()

    /** Per-file rows the pause/resume/discard flows read and write. */
    val allFiles = MutableStateFlow<List<DownloadedModelFile>>(emptyList())

    /** Every status write, as "modelId/fileName" to the new status. */
    val statusUpdates = mutableListOf<Pair<String, FileDownloadStatus>>()

    /** Inserts (or replaces) a file row so tests can seed a known state. */
    fun seedFile(modelId: String, fileName: String, status: FileDownloadStatus) {
        allFiles.value = allFiles.value.filterNot {
            it.modelId == modelId && it.fileName == fileName
        } + DownloadedModelFile(modelId, fileName, status, 100L, "/tmp", null)
    }

    fun statusOf(modelId: String, fileName: String): FileDownloadStatus? =
        allFiles.value.firstOrNull { it.modelId == modelId && it.fileName == fileName }?.status

    override fun observeDownloadedModels(): Flow<List<DownloadedModel>> = models
    override fun observeDownloadedFiles(): Flow<List<DownloadedModelFile>> = files
    override suspend fun getDownloadedModel(modelId: String): DownloadedModel? =
        models.value.singleOrNull { it.modelId == modelId }
    override suspend fun getDownloadedFileForModel(modelId: String): DownloadedModelFile? =
        fileByModel[modelId] ?: lastUsedFile

    override suspend fun listModels(): AppResult<List<ModelSummary>> = throw NotImplementedError()
    override suspend fun listModels(search: String?, pipelineTag: String?): AppResult<List<ModelSummary>> =
        throw NotImplementedError()
    override suspend fun getModelDetails(modelId: String): AppResult<ModelDetails> =
        throw NotImplementedError()
    override suspend fun deleteDownloadedModel(modelId: String) {}
    override fun observeModelFiles(modelId: String): Flow<List<DownloadedModelFile>> = allFiles
    override fun observePausedFiles(): Flow<List<DownloadedModelFile>> = MutableStateFlow(emptyList())
    override suspend fun getModelFile(modelId: String, fileName: String): DownloadedModelFile? =
        allFiles.value.firstOrNull { it.modelId == modelId && it.fileName == fileName }
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
        allFiles.value = allFiles.value.map {
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
        destinationUri: android.net.Uri
    ): com.ai.tinymodels.core.common.AppResult<android.net.Uri> =
        throw NotImplementedError()
    override suspend fun importModel(sourceUri: android.net.Uri): com.ai.tinymodels.core.common.AppResult<DownloadedModel> =
        throw NotImplementedError()
}

/** ChatRepository fake with fixed usage stats and a reactive summaries flow. */
private class FakeHomeChatRepository : ChatRepository {
    val summaries = MutableStateFlow(
        listOf(ChatSummary("chat-1", "Hello", "m", "hi", 123L))
    )

    override fun observeChatSummaries(): Flow<List<ChatSummary>> = summaries

    override suspend fun totalChats(): Int = 3
    override suspend fun totalMessages(): Int = 50
    override suspend fun totalTokensGenerated(): Long = 1_000L
    override suspend fun modelsTried(): Int = 2
    override suspend fun chatsSince(sinceMs: Long): Int = 1
    override suspend fun totalCompactions(): Int = 0
    override suspend fun webSearchChats(): Int = 0
    override suspend fun favoriteModelId(): String? = null
    override suspend fun getLastSessionTokensPerSecond(): Float? = 2.5f

    override fun observeMessages(chatId: String) = MutableStateFlow(emptyList<com.ai.tinymodels.domain.model.ChatMessage>())
    override suspend fun getMessages(chatId: String) = emptyList<com.ai.tinymodels.domain.model.ChatMessage>()
    override suspend fun getChat(chatId: String) = null
    override suspend fun createChat(
        modelId: String,
        title: String,
        defaultBackend: com.ai.tinymodels.domain.model.BackendPreference,
        webSearchEnabled: Boolean
    ): com.ai.tinymodels.domain.model.Chat = throw NotImplementedError()
    override suspend fun renameChat(chatId: String, title: String) {}

    override suspend fun setChatPinned(chatId: String, pinned: Boolean) {}
    override suspend fun updateInferenceSettings(
        chatId: String,
        settings: com.ai.tinymodels.domain.model.InferenceSettings
    ) {}
    override suspend fun saveCompaction(chatId: String, summary: String?, cutoffCreatedAt: Long?) {}
    override suspend fun saveMessage(message: com.ai.tinymodels.domain.model.ChatMessage) {}
    override suspend fun updateWebSearchEnabled(chatId: String, enabled: Boolean) {}
    override suspend fun deleteMessage(messageId: String) {}
    override suspend fun deleteChat(chatId: String) {}
    override suspend fun clearAllChats() {}
    override suspend fun messageCount(chatId: String): Int = 0
}

/** SettingsRepository fake with a configurable last-used model. */
private class FakeHomeSettingsRepository : com.ai.tinymodels.domain.repository.SettingsRepository {
    var lastUsedModelId: String? = null

    override suspend fun getLastUsedModelId(): String? = lastUsedModelId

    override val settings: Flow<com.ai.tinymodels.domain.model.AppSettings> =
        MutableStateFlow(com.ai.tinymodels.domain.model.AppSettings())
    override val huggingFaceToken: Flow<String?> = MutableStateFlow(null)
    override suspend fun getSettings() = com.ai.tinymodels.domain.model.AppSettings()
    override suspend fun getHuggingFaceToken(): String? = null
    override suspend fun setHuggingFaceToken(token: String?) {}
    override suspend fun setThemeMode(mode: com.ai.tinymodels.domain.model.ThemeMode) {}
    override suspend fun setDynamicColor(enabled: Boolean) {}
    override suspend fun setFontChoice(choice: com.ai.tinymodels.domain.model.FontChoice) {}
    override suspend fun setFontScale(scale: Float) {}
    override suspend fun setDefaultBackend(backend: com.ai.tinymodels.domain.model.BackendPreference) {}
    override suspend fun setWebSearchEnabled(enabled: Boolean) {}
    override suspend fun setWifiOnlyDownloads(enabled: Boolean) {}
    override suspend fun setAutoLoadLastModel(enabled: Boolean) {}
    override suspend fun setKeepScreenOn(enabled: Boolean) {}
    override suspend fun setShowTokenCounts(enabled: Boolean) {}
    override suspend fun setOnboardingCompleted(completed: Boolean) {}
    override suspend fun setLastUsedModelId(modelId: String) {}
}
