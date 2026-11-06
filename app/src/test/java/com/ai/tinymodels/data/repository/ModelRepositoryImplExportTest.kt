package com.ai.tinymodels.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.ai.tinymodels.R
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.database.DownloadedModelDao
import com.ai.tinymodels.core.database.ModelFileDao
import com.ai.tinymodels.core.network.HuggingFaceApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Repository tests for [ModelRepositoryImpl.exportModelFile]: single-file
 * SAF export (success bytes round-trip) and the missing-file guard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelRepositoryImplExportTest {

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
        mockkStatic(DocumentsContract::class)
        every { context.filesDir } returns temp.root
        every { context.contentResolver } returns resolver
        every { context.getString(any()) } answers { firstArg<Int>().toString() }
        every { context.getString(any(), *anyVararg()) } answers {
            firstArg<Int>().toString() + "(" + invocation.args.drop(1)
                .flatMap { arg -> if (arg is Array<*>) arg.toList() else listOf(arg) }
                .joinToString(",") + ")"
        }
        repo = ModelRepositoryImpl(context, downloadedModelDao, modelFileDao, api, dispatchers)
    }

    @After
    fun tearDown() {
        unmockkStatic(DocumentsContract::class)
    }

    private fun seedModelFile(modelId: String, fileName: String, bytes: ByteArray): File {
        val dir = File(temp.root, "models/${modelId.replace("/", "_")}")
        dir.mkdirs()
        return File(dir, fileName).apply { writeBytes(bytes) }
    }

    @Test
    fun `export copies exact bytes to destination`() = runTest {
        val payload = "fake-litertlm-payload".toByteArray()
        seedModelFile("a/b", "model.litertlm", payload)

        val treeUri = mockk<Uri>()
        val parentUri = mockk<Uri>()
        val destUri = mockk<Uri>()
        val sink = ByteArrayOutputStream()
        every { DocumentsContract.getTreeDocumentId(treeUri) } returns "treeDoc"
        every { DocumentsContract.buildDocumentUriUsingTree(treeUri, "treeDoc") } returns parentUri
        every {
            DocumentsContract.createDocument(resolver, parentUri, "application/octet-stream", "model.litertlm")
        } returns destUri
        every { resolver.openOutputStream(destUri) } returns sink

        val result = repo.exportModelFile("a/b", "model.litertlm", treeUri)

        assertTrue(result is AppResult.Success)
        assertArrayEquals(payload, sink.toByteArray())
    }

    @Test
    fun `export fails when file missing on disk`() = runTest {
        val result = repo.exportModelFile("a/b", "absent.litertlm", mockk())

        assertTrue(result is AppResult.Error)
        assertTrue(
            (result as AppResult.Error).error.message
                ?.contains(R.string.model_import_file_not_found.toString()) == true
        )
    }
}
