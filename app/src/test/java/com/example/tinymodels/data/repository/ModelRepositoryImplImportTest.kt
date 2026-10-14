package com.example.tinymodels.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.example.tinymodels.core.common.AppResult
import com.example.tinymodels.core.common.DispatcherProvider
import com.example.tinymodels.core.database.DownloadedModelDao
import com.example.tinymodels.core.database.ModelFileDao
import com.example.tinymodels.core.database.entities.DownloadedModelEntity
import com.example.tinymodels.core.database.entities.ModelFileEntity
import com.example.tinymodels.core.network.HuggingFaceApi
import com.example.tinymodels.domain.model.FileDownloadStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Validates the SAF import guards in [ModelRepositoryImpl.importModel]:
 * non-`.litertlm` files are rejected and already-registered files are not
 * duplicated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelRepositoryImplImportTest {

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
    fun `import rejects non-litertlm files`() = runTest {
        val result = repo.importModel(sourceUriServing("notes.txt"))

        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).error.message?.contains("Invalid file type") == true)
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
