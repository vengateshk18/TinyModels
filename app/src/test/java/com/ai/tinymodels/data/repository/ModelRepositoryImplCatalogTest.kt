package com.ai.tinymodels.data.repository

import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.database.DownloadedModelDao
import com.ai.tinymodels.core.database.ModelFileDao
import com.ai.tinymodels.core.network.HuggingFaceApi
import com.ai.tinymodels.domain.model.ModelFilter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Tests for the mobile-focused catalog pipeline in
 * [ModelRepositoryImpl.listModels]: merged browse branches, runnable +
 * format + size filtering, friendly-first ordering, caching and paging.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelRepositoryImplCatalogTest {

    private val context = mockk<android.content.Context>(relaxed = true)
    private val downloadedModelDao = mockk<DownloadedModelDao>(relaxed = true)
    private val modelFileDao = mockk<ModelFileDao>(relaxed = true)
    private val api = mockk<HuggingFaceApi>()
    private val dispatchers = object : DispatcherProvider {
        private val test = UnconfinedTestDispatcher()
        override val main = test
        override val io = test
        override val default: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
    }

    /** Branch payloads keyed by search keyword (or author when search is null). */
    private var catalog: Map<String, List<String>> = emptyMap()
    private var failingBranches: Set<String> = emptySet()

    private fun repo() = ModelRepositoryImpl(context, downloadedModelDao, modelFileDao, api, dispatchers)

    @Before
    fun setUp() {
        every { api.listModels(any(), any(), any(), any()) } answers {
            val key = firstArg<String?>() ?: thirdArg<String?>().orEmpty()
            if (key in failingBranches) throw IOException("boom-$key")
            (catalog[key] ?: emptyList()).joinToString(",", "[", "]")
        }
    }

    private fun modelJson(
        id: String,
        downloads: Long = 1_000L,
        files: List<String> = listOf("model.litertlm")
    ): String {
        val siblings = files.joinToString(",") { """{"rfilename":"$it"}""" }
        return """{"id":"$id","modelId":"$id","author":"${id.substringBefore("/")}",""" +
            """"downloads":$downloads,"likes":1,"tags":[],"library_name":"litert-lm",""" +
            """"pipeline_tag":"text-generation","lastModified":"2026-01-01T00:00:00.000Z",""" +
            """"siblings":[$siblings]}"""
    }

    @Test
    fun `default browse merges litertlm, litert-community and gguf branches`() = runTest {
        catalog = mapOf(
            "litertlm" to listOf(modelJson("a/one", downloads = 30, files = listOf("one.litertlm"))),
            "litert-community" to listOf(
                modelJson("litert-community/two", downloads = 20),
                modelJson("a/one", downloads = 30, files = listOf("one.litertlm")) // duplicate
            ),
            "gguf" to listOf(modelJson("b/three", downloads = 10, files = listOf("three-q4_k_m.gguf")))
        )

        val page = (repo().listModels(null, ModelFilter.ALL, 100) as AppResult.Success).data

        assertEquals(
            listOf("a/one", "litert-community/two", "b/three"),
            page.models.map { it.modelId }
        )
        assertFalse(page.hasMore)
        verify(exactly = 1) { api.listModels("litertlm", "text-generation", null, 100) }
        verify(exactly = 1) { api.listModels(null, "text-generation", "litert-community", 100) }
        verify(exactly = 1) { api.listModels("gguf", "text-generation", null, 100) }
    }

    @Test
    fun `typed search replaces the browse branches`() = runTest {
        catalog = mapOf("qwen" to listOf(modelJson("q/one")))

        val page = (repo().listModels("  qwen  ", ModelFilter.ALL, 50) as AppResult.Success).data

        assertEquals(listOf("q/one"), page.models.map { it.modelId })
        verify(exactly = 1) { api.listModels("qwen", "text-generation", null, 50) }
        verify(exactly = 0) { api.listModels("gguf", any(), any(), any()) }
    }

    @Test
    fun `drops models without runnable files`() = runTest {
        catalog = mapOf(
            "qwen" to listOf(
                modelJson("q/readme-only", files = listOf("README.md", "model.safetensors")),
                modelJson("q/ok")
            )
        )

        val page = (repo().listModels("qwen", ModelFilter.ALL, 50) as AppResult.Success).data

        assertEquals(listOf("q/ok"), page.models.map { it.modelId })
    }

    @Test
    fun `drops models too large for phones`() = runTest {
        catalog = mapOf(
            "qwen" to listOf(
                modelJson("q/huge", files = listOf("huge-70b-q4_k_m.gguf")),
                modelJson("q/ok", files = listOf("ok-1.5b-q4_k_m.gguf"))
            )
        )

        val page = (repo().listModels("qwen", ModelFilter.ALL, 50) as AppResult.Success).data

        assertEquals(listOf("q/ok"), page.models.map { it.modelId })
    }

    @Test
    fun `mobile friendly models sort above unknown ones`() = runTest {
        catalog = mapOf(
            "qwen" to listOf(
                modelJson("q/mid", downloads = 5_000_000, files = listOf("mid-7b-q4_k_m.gguf")),
                modelJson("q/small", downloads = 10, files = listOf("small-0.5b-q4_k_m.gguf"))
            )
        )

        val page = (repo().listModels("qwen", ModelFilter.ALL, 50) as AppResult.Success).data

        assertEquals(listOf("q/small", "q/mid"), page.models.map { it.modelId })
    }

    @Test
    fun `friendly models keep download ordering among themselves`() = runTest {
        catalog = mapOf(
            "qwen" to listOf(
                modelJson("q/less", downloads = 10, files = listOf("a-1.5b-q4_k_m.gguf")),
                modelJson("q/more", downloads = 99, files = listOf("b-1.5b-q4_k_m.gguf"))
            )
        )

        val page = (repo().listModels("qwen", ModelFilter.ALL, 50) as AppResult.Success).data

        assertEquals(listOf("q/more", "q/less"), page.models.map { it.modelId })
    }

    @Test
    fun `format chip narrows branches and filters results`() = runTest {
        catalog = mapOf(
            "gguf" to listOf(
                modelJson("g/gguf", files = listOf("g-q4_k_m.gguf")),
                modelJson("l/litertlm", files = listOf("l.litertlm"))
            )
        )

        val page = (repo().listModels(null, ModelFilter.GGUF, 100) as AppResult.Success).data

        assertEquals(listOf("g/gguf"), page.models.map { it.modelId })
        verify(exactly = 1) { api.listModels("gguf", "text-generation", null, 100) }
        verify(exactly = 0) { api.listModels("litertlm", any(), any(), any()) }
    }

    @Test
    fun `typed search still respects the selected format chip`() = runTest {
        catalog = mapOf(
            "foo" to listOf(
                modelJson("f/gguf", files = listOf("f-q4_k_m.gguf")),
                modelJson("f/lite", files = listOf("f.litertlm"))
            )
        )

        val page = (repo().listModels("foo", ModelFilter.LITERTLM, 100) as AppResult.Success).data

        assertEquals(listOf("f/lite"), page.models.map { it.modelId })
    }

    @Test
    fun `one failing branch does not kill the catalog`() = runTest {
        catalog = mapOf("gguf" to listOf(modelJson("b/ok", files = listOf("b-q4_k_m.gguf"))))
        failingBranches = setOf("litertlm")

        val page = (repo().listModels(null, ModelFilter.ALL, 100) as AppResult.Success).data

        assertEquals(listOf("b/ok"), page.models.map { it.modelId })
    }

    @Test
    fun `all branches failing surfaces a typed error`() = runTest {
        failingBranches = setOf("litertlm", "litert-community", "gguf")

        val result = repo().listModels(null, ModelFilter.ALL, 100)

        assertTrue(result is AppResult.Error)
    }

    @Test
    fun `hasMore is true when a branch fills its limit`() = runTest {
        catalog = mapOf(
            "gguf" to (1..3).map {
                modelJson("g/m$it", downloads = it.toLong(), files = listOf("m$it-q4_k_m.gguf"))
            },
            "litertlm" to listOf(modelJson("l/one")),
            "litert-community" to listOf(modelJson("c/one"))
        )

        val page = (repo().listModels(null, ModelFilter.ALL, 3) as AppResult.Success).data

        assertTrue(page.hasMore)
        assertEquals(5, page.models.size)
    }

    @Test
    fun `identical browse is served from cache`() = runTest {
        catalog = mapOf("gguf" to listOf(modelJson("g/one", files = listOf("g-q4_k_m.gguf"))))
        val repository = repo()

        repository.listModels(null, ModelFilter.GGUF, 100)
        repository.listModels(null, ModelFilter.GGUF, 100)

        verify(exactly = 1) { api.listModels("gguf", "text-generation", null, 100) }
    }
}
