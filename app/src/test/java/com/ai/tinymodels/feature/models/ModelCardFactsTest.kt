package com.ai.tinymodels.feature.models

import com.ai.tinymodels.domain.model.ModelFormat
import com.ai.tinymodels.domain.model.ModelSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the browse-card facts: owner fallback, primary format,
 * parameter size, quantisation, context length and the vision flag.
 */
class ModelCardFactsTest {

    private fun model(
        id: String = "LiquidAI/LFM3-1.7B-GGUF",
        author: String = "LiquidAI",
        files: List<String> = listOf("LFM3-1.7B-Q4_K_M.gguf"),
        tags: List<String> = emptyList(),
        contextLength: Int? = null
    ) = ModelSummary(
        id = id,
        modelId = id,
        author = author,
        likes = 10,
        downloads = 100,
        tags = tags,
        libraryName = null,
        pipelineTag = "text-generation",
        lastModified = null,
        siblings = files,
        contextLength = contextLength
    )

    // ---- author ----

    @Test
    fun `author comes from the API when present`() {
        assertEquals("LiquidAI", ModelCardFacts.of(model(author = "LiquidAI")).author)
    }

    @Test
    fun `author falls back to the repo owner when the API omits it`() {
        assertEquals("google", ModelCardFacts.of(model(id = "google/gemma-3-270m-it", author = "")).author)
    }

    @Test
    fun `author is null when there is no owner segment`() {
        assertNull(ModelCardFacts.of(model(id = "gemma-3-270m-it", author = "")).author)
    }

    // ---- format ----

    @Test
    fun `format is GGUF for a repo with gguf files`() {
        assertEquals(ModelFormat.GGUF, ModelCardFacts.of(model()).format)
    }

    @Test
    fun `format is LiteRT-LM for a repo with litertlm files`() {
        assertEquals(
            ModelFormat.LITERTLM,
            ModelCardFacts.of(model(id = "litert-community/gemma3-1b", files = listOf("gemma3-1b.litertlm"))).format
        )
    }

    @Test
    fun `format is the most common runnable format in a mixed repo`() {
        val facts = ModelCardFacts.of(
            model(
                files = listOf(
                    "a.litertlm",
                    "b.gguf",
                    "c.gguf",
                    "README.md"
                )
            )
        )
        assertEquals(ModelFormat.GGUF, facts.format)
    }

    @Test
    fun `format is null when the repo has no runnable files`() {
        assertNull(ModelCardFacts.of(model(files = listOf("README.md", "model.safetensors"))).format)
    }

    // ---- parameters ----

    @Test
    fun `parameters are parsed from the repo id`() {
        assertEquals(1.7, ModelCardFacts.of(model()).parameterBillions!!, 0.001)
    }

    @Test
    fun `parameters render as billions or millions`() {
        assertEquals("1.7B", ModelCardFacts.formatParameters(1.7))
        assertEquals("7B", ModelCardFacts.formatParameters(7.0))
        assertEquals("270M", ModelCardFacts.formatParameters(0.27))
        assertNull(ModelCardFacts.formatParameters(null))
    }

    // ---- quantisation ----

    @Test
    fun `quantisation is read from the file name`() {
        assertEquals("Q4_K_M", ModelCardFacts.of(model()).quantization)
    }

    @Test
    fun `quantisation prefers a mobile-friendly quant over a large one`() {
        val facts = ModelCardFacts.of(
            model(files = listOf("m-Q8_0.gguf", "m-Q4_K_M.gguf"))
        )
        assertEquals("Q4_K_M", facts.quantization)
    }

    @Test
    fun `quantisation falls back to tags when file names lack one`() {
        val facts = ModelCardFacts.of(
            model(files = listOf("model.gguf"), tags = listOf("gguf", "q8_0"))
        )
        assertEquals("Q8_0", facts.quantization)
    }

    @Test
    fun `quantisation normalises float variants`() {
        assertEquals("FP16", ModelCardFacts.of(model(files = listOf("m-F16.gguf"))).quantization)
        assertEquals("BF16", ModelCardFacts.of(model(files = listOf("m-BF16.gguf"))).quantization)
    }

    @Test
    fun `quantisation ignores vision projectors`() {
        val facts = ModelCardFacts.of(
            model(files = listOf("model.gguf", "mmproj-model-Q8_0.gguf"), tags = listOf("q5_k_m"))
        )
        assertEquals("Q5_K_M", facts.quantization)
    }

    @Test
    fun `quantisation is null when nothing indicates it`() {
        assertNull(ModelCardFacts.of(model(files = listOf("model.gguf"))).quantization)
    }

    @Test
    fun `quantisation falls back to the repo id`() {
        val facts = ModelCardFacts.of(
            model(id = "TheBloke/Story-Llama-7B-Q4_K_M-GGUF", files = listOf("model.gguf"))
        )
        assertEquals("Q4_K_M", facts.quantization)
    }

    // ---- context window ----

    @Test
    fun `context window is passed through when the API supplies it`() {
        assertEquals(262144, ModelCardFacts.of(model(contextLength = 262144)).contextLength)
    }

    @Test
    fun `context renders compactly in K and M`() {
        assertEquals("512", ModelCardFacts.formatContext(512))
        assertEquals("2K", ModelCardFacts.formatContext(2048))
        assertEquals("32K", ModelCardFacts.formatContext(32768))
        assertEquals("256K", ModelCardFacts.formatContext(262144))
        assertEquals("1M", ModelCardFacts.formatContext(1048576))
        assertNull(ModelCardFacts.formatContext(null))
    }

    // ---- vision ----

    @Test
    fun `vision is true when a projector ships with the model`() {
        assertTrue(
            ModelCardFacts.of(model(files = listOf("model.gguf", "mmproj-model.gguf"))).hasVision
        )
    }

    @Test
    fun `vision is false for text-only repos`() {
        assertFalse(ModelCardFacts.of(model()).hasVision)
    }
}
