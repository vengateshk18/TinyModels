package com.ai.tinymodels.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.database.DownloadedModelDao
import com.ai.tinymodels.core.database.ModelFileDao
import com.ai.tinymodels.core.database.entities.DownloadedModelEntity
import com.ai.tinymodels.core.database.entities.ModelFileEntity
import com.ai.tinymodels.core.network.HuggingFaceApi
import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.model.ModelFormat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

/**
 * Validates the SAF import behaviour of [ModelRepositoryImpl.importModel]:
 * unsupported extensions are rejected, supported formats (`.litertlm`,
 * `.task`, `.tflite`, `.gguf`) are copied in with the right format, and
 * already-registered files are not duplicated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelRepositoryImplImportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val downloadedModelDao = mockk<DownloadedModelDao>(relaxed = true)
    private val modelFileDao = mockk<ModelFileDao>(relaxed = true)
    private val api = mockk<HuggingFaceApi>(relaxed = true)
    private val dispatchers = object : DispatcherProvider {
        private val test = UnconfinedTestDispatcher()
        override val main = test
        override val io = test
        override val default: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
    }

    private lateinit var repo: ModelRepositoryImpl

    @Before
    fun setUp() {
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns temp.root
        repo = ModelRepositoryImpl(context, downloadedModelDao, modelFileDao, api, dispatchers)
    }

    private fun sourceUriServing(fileName: String): Uri {
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.moveToFirst() } returns true
        every { cursor.getColumnIndexOrThrow(any()) } returns 0
        every { cursor.getString(0) } returns fileName
        every { resolver.query(any(), any(), any(), any(), any()) } returns cursor
        return mockk()
    }

    @Test
    fun `import rejects unsupported files`() = runTest {
        val result = repo.importModel(sourceUriServing("notes.txt"))

        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).error.message?.contains("Unsupported file type") == true)
    }

    @Test
    fun `import accepts gguf files and records their format`() = runTest {
        every { resolver.openInputStream(any()) } returns ByteArrayInputStream("gguf-bytes".toByteArray())
        coEvery { downloadedModelDao.getById(any()) } returns null
        coEvery { modelFileDao.getByModelId(any()) } returns emptyList()

        val result = repo.importModel(sourceUriServing("Qwen3-0.6B-Q4_K_M.gguf"))

        assertTrue(result is AppResult.Success)
        val slot = slot<ModelFileEntity>()
        coVerify { modelFileDao.insert(capture(slot)) }
        val saved = slot.captured
        assertEquals("Qwen3-0.6B-Q4_K_M.gguf", saved.fileName)
        assertEquals(ModelFormat.GGUF, saved.format)
        assertTrue(saved.isImported)
    }

    @Test
    fun `import rejects already-registered files`() = runTest {
        val fileName = "mymodel.litertlm"
        coEvery { downloadedModelDao.getById("mymodel") } returns DownloadedModelEntity(
            modelId = "mymodel",
            author = "imported",
            libraryName = "litertlm",
            pipelineTag = "text-generation",
            localPath = "/tmp/mymodel",
            downloadedAt = 0L
        )
        coEvery { modelFileDao.getByModelId("mymodel") } returns listOf(
            ModelFileEntity(
                modelId = "mymodel",
                fileName = fileName,
                status = FileDownloadStatus.DOWNLOADED.name,
                sizeBytes = 1L,
                localPath = "/tmp/mymodel",
                error = null,
                isImported = true
            )
        )

        val result = repo.importModel(sourceUriServing(fileName))

        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).error.message?.contains("already exists") == true)
    }
}
