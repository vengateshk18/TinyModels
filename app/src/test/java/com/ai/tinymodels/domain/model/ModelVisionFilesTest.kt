package com.ai.tinymodels.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelVisionFilesTest {

    private fun details(vararg siblings: String) = ModelDetails(
        id = "acme/SAGE-A1.5",
        author = "acme",
        pipelineTag = "text-generation",
        libraryName = "llama.cpp",
        tags = emptyList(),
        downloads = 0,
        likes = 0,
        lastModified = null,
        createdAt = null,
        siblings = siblings.toList(),
        usedStorage = 0,
        sha = null,
        gated = "false",
        disabled = false,
        widgetPrompts = emptyList(),
        baseModel = null
    )

    @Test
    fun `detects mmproj and projector file names`() {
        assertTrue("SAGE_A1.5-MMPROJ.gguf".isVisionProjectorFile())
        assertTrue("mmproj-model-f16.gguf".isVisionProjectorFile())
        assertTrue("Some-Projector.gguf".isVisionProjectorFile())
        assertFalse("SAGE-A1.5-Q4_K_M.gguf".isVisionProjectorFile())
        assertFalse("model.litertlm".isVisionProjectorFile())
        // Only GGUF files can be projectors.
        assertFalse("mmproj-notes.txt".isVisionProjectorFile())
    }

    @Test
    fun `projectors are not runnable model files`() {
        val model = details(
            "main/SAGE-A1.5-Q4_K_M.gguf",
            "mmproj/SAGE_A1.5-MMPROJ.gguf",
            "README.md"
        )

        assertEquals(listOf("main/SAGE-A1.5-Q4_K_M.gguf"), model.allRunnableFiles)
        assertEquals(listOf("main/SAGE-A1.5-Q4_K_M.gguf"), model.ggufFiles)
        assertEquals(listOf("mmproj/SAGE_A1.5-MMPROJ.gguf"), model.mmprojFiles)
        assertTrue(model.hasVisionSupport)
    }

    @Test
    fun `litert models are unaffected`() {
        val model = details("model_G5.litertlm", "mmproj-f16.gguf")

        assertEquals(listOf("model_G5.litertlm"), model.allRunnableFiles)
        assertTrue(model.hasVisionSupport)
        assertTrue(model.companionFilesFor("model_G5.litertlm").isEmpty())
    }

    @Test
    fun `companion projector is fetched with the main model`() {
        val model = details(
            "SAGE-A1.5-Q4_K_M.gguf",
            "SAGE_A1.5-MMPROJ.gguf"
        )

        assertEquals(listOf("SAGE_A1.5-MMPROJ.gguf"), model.companionFilesFor("SAGE-A1.5-Q4_K_M.gguf"))
        // A projector never has itself as a companion.
        assertTrue(model.companionFilesFor("SAGE_A1.5-MMPROJ.gguf").isEmpty())
    }

    @Test
    fun `companion picks the projector closest by name`() {
        val model = details(
            "qwen2-vl-2b-q4.gguf",
            "qwen2-vl-2b-mmproj.gguf",
            "llava-7b-q4.gguf",
            "llava-7b-mmproj.gguf"
        )

        assertEquals(listOf("qwen2-vl-2b-mmproj.gguf"), model.companionFilesFor("qwen2-vl-2b-q4.gguf"))
        assertEquals(listOf("llava-7b-mmproj.gguf"), model.companionFilesFor("llava-7b-q4.gguf"))
    }

    @Test
    fun `no companion when repo has no projector`() {
        val model = details("SAGE-A1.5-Q4_K_M.gguf")

        assertFalse(model.hasVisionSupport)
        assertTrue(model.companionFilesFor("SAGE-A1.5-Q4_K_M.gguf").isEmpty())
    }

    @Test
    fun `summary runtime file count ignores projectors`() {
        val summary = ModelSummary(
            id = "acme/SAGE-A1.5",
            modelId = "SAGE-A1.5",
            author = "acme",
            likes = 0,
            downloads = 0,
            tags = emptyList(),
            libraryName = "llama.cpp",
            pipelineTag = "text-generation",
            lastModified = null,
            siblings = listOf("SAGE-A1.5-Q4_K_M.gguf", "SAGE_A1.5-MMPROJ.gguf")
        )

        assertEquals(1, summary.runtimeFileCount)
        assertTrue(summary.hasRuntimeFiles)

        val projectorOnly = summary.copy(siblings = listOf("SAGE_A1.5-MMPROJ.gguf"))
        assertEquals(0, projectorOnly.runtimeFileCount)
        assertFalse(projectorOnly.hasRuntimeFiles)
    }
}
