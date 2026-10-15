package com.ai.tinymodels.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior tests for [MobileFit]: which catalog entries get the
 * "Mobile-ready" badge, which are hidden as clearly too large, and which
 * pass through unbadged.
 */
class MobileFitTest {

    private fun model(modelId: String, files: List<String>) = ModelSummary(
        id = modelId,
        modelId = modelId,
        author = modelId.substringBefore("/"),
        likes = 10,
        downloads = 1_000L,
        tags = emptyList(),
        libraryName = "gguf",
        pipelineTag = "text-generation",
        lastModified = null,
        siblings = files
    )

    // ---- friendly ----

    @Test
    fun `litertlm bundle is mobile friendly even without size hints`() {
        val m = model("org/foo", listOf("mobile_actions_q8_ekv1024.litertlm"))
        assertTrue(MobileFit.isMobileFriendly(m))
        assertFalse(MobileFit.isTooLargeForMobile(m))
    }

    @Test
    fun `mediapipe task bundle is mobile friendly`() {
        val m = model("org/gemma-3-1b-it", listOf("gemma-3-1b-it-int4.task"))
        assertTrue(MobileFit.isMobileFriendly(m))
    }

    @Test
    fun `small quantized gguf is mobile friendly`() {
        val m = model(
            "org/Qwen2.5-1.5B-Instruct-GGUF",
            listOf("qwen2.5-1.5b-instruct-q4_k_m.gguf")
        )
        assertTrue(MobileFit.isMobileFriendly(m))
    }

    @Test
    fun `sub-one-b model is mobile friendly even unquantized`() {
        val m = model("org/SmolLM2-360M-Instruct", listOf("model.safetensors"))
        assertTrue(MobileFit.isMobileFriendly(m))
    }

    @Test
    fun `three-b model needs a quant marker to be friendly`() {
        val quantized = model(
            "org/Llama-3.2-3B-Instruct-GGUF",
            listOf("llama-3.2-3b-instruct-q4_k_m.gguf")
        )
        assertTrue(MobileFit.isMobileFriendly(quantized))

        val unquantized = model("org/Llama-3.2-3B-Instruct", listOf("model-00001.safetensors"))
        assertFalse(MobileFit.isMobileFriendly(unquantized))
    }

    @Test
    fun `seven-b quantized model is allowed but not badged`() {
        val m = model("org/Qwen2.5-7B-GGUF", listOf("qwen2.5-7b-q4_k_m.gguf"))
        assertFalse(MobileFit.isMobileFriendly(m))
        assertFalse(MobileFit.isTooLargeForMobile(m))
    }

    // ---- too large ----

    @Test
    fun `13b model is too large`() {
        val m = model("org/Llama-3-13B", listOf("llama-3-13b-q4_k_m.gguf"))
        assertTrue(MobileFit.isTooLargeForMobile(m))
    }

    @Test
    fun `70b model is too large even in gguf form`() {
        val m = model("TheBloke/Llama-2-70B-GGUF", listOf("llama-2-70b.Q4_K_M.gguf"))
        assertTrue(MobileFit.isTooLargeForMobile(m))
        assertFalse(MobileFit.isMobileFriendly(m))
    }

    @Test
    fun `moe model size uses expert product`() {
        val m = model("org/Mixtral-8x7B-v0.1", listOf("mixtral-8x7b-q4_k_m.gguf"))
        assertTrue(MobileFit.isTooLargeForMobile(m))
    }

    @Test
    fun `qwen3 a3b active-param suffix is not read as total size`() {
        val m = model("org/Qwen3-30B-A3B-GGUF", listOf("x-q4_k_m.gguf"))
        assertTrue(MobileFit.isTooLargeForMobile(m))
    }

    @Test
    fun `params are read from file names when the repo name has none`() {
        val m = model("org/foo", listOf("foo-1.5b-instruct-q8_0.gguf"))
        assertTrue(MobileFit.isMobileFriendly(m))
    }

    @Test
    fun `million suffixes map to fractions of a billion`() {
        val m = model("org/foo-1500m-GGUF", listOf("foo-q4_k_m.gguf"))
        assertTrue(MobileFit.isMobileFriendly(m))
    }

    // ---- tricky parsing ----

    @Test
    fun `bit suffixes are not mistaken for billion params`() {
        val m = model("org/model-4bit", listOf("model.gguf"))
        assertFalse(MobileFit.isTooLargeForMobile(m))
        assertFalse(MobileFit.isMobileFriendly(m))
    }

    @Test
    fun `quant markers alone do not claim mobile-ready`() {
        val m = model("org/unknown-size-GGUF", listOf("unknown-size-q4_k_m.gguf"))
        assertFalse(MobileFit.isMobileFriendly(m))
        assertFalse(MobileFit.isTooLargeForMobile(m))
    }

    @Test
    fun `no size signal means no verdict`() {
        val m = model("org/mystery", listOf("model.safetensors"))
        assertFalse(MobileFit.isMobileFriendly(m))
        assertFalse(MobileFit.isTooLargeForMobile(m))
    }
}
