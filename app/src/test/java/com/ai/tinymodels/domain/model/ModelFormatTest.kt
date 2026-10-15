package com.ai.tinymodels.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for multi-format detection helpers ([ModelFormat] + file-name utils). */
class ModelFormatTest {

    @Test
    fun `fromFileName detects supported extensions case-insensitively`() {
        assertEquals(ModelFormat.LITERTLM, ModelFormat.fromFileName("model_G5.litertlm"))
        assertEquals(ModelFormat.TASK, ModelFormat.fromFileName("gemma3-1b.task"))
        assertEquals(ModelFormat.TFLITE, ModelFormat.fromFileName("model.tflite"))
        assertEquals(ModelFormat.GGUF, ModelFormat.fromFileName("Qwen3-Q4_K_M.GGUF"))
    }

    @Test
    fun `fromFileName defaults to litertlm for unknown names`() {
        assertEquals(ModelFormat.LITERTLM, ModelFormat.fromFileName("README.md"))
    }

    @Test
    fun `modelFileDisplayName strips path and extension`() {
        assertEquals("model_G5", "/data/models/model_G5.litertlm".modelFileDisplayName())
        assertEquals("Qwen3-Q4_K_M", "Qwen3-Q4_K_M.gguf".modelFileDisplayName())
    }

    @Test
    fun `modelFileDisplayName keeps names without a supported extension`() {
        assertEquals("notes.txt", "notes.txt".modelFileDisplayName())
    }

    @Test
    fun `allRunnableFiles spans every supported format`() {
        val details = detailsWith(
            listOf("README.md", "weights.bin", "a.litertlm", "b.task", "c.tflite", "d.gguf")
        )

        assertEquals(
            listOf("a.litertlm", "b.task", "c.tflite", "d.gguf"),
            details.allRunnableFiles
        )
        assertEquals(listOf("a.litertlm"), details.liteRtFiles)
        assertEquals(listOf("b.task"), details.taskFiles)
        assertEquals(listOf("c.tflite"), details.tfliteFiles)
        assertEquals(listOf("d.gguf"), details.ggufFiles)
    }

    @Test
    fun `summary reports runtime files across formats`() {
        val summary = ModelSummary(
            id = "x/y", modelId = "x/y", author = "x", likes = null, downloads = null,
            tags = emptyList(), libraryName = null, pipelineTag = null, lastModified = null,
            siblings = listOf("README.md", "m.gguf", "m2.task")
        )

        assertTrue(summary.hasRuntimeFiles)
        assertEquals(2, summary.runtimeFileCount)
    }

    @Test
    fun `every format exposes its file extension`() {
        assertEquals(".litertlm", ModelFormat.LITERTLM.extension)
        assertEquals(".task", ModelFormat.TASK.extension)
        assertEquals(".tflite", ModelFormat.TFLITE.extension)
        assertEquals(".gguf", ModelFormat.GGUF.extension)
    }

    @Test
    fun `summary hasFormat matches sibling extensions case-insensitively`() {
        val summary = ModelSummary(
            id = "x/y", modelId = "x/y", author = "x", likes = null, downloads = null,
            tags = emptyList(), libraryName = null, pipelineTag = null, lastModified = null,
            siblings = listOf("README.md", "weights.Q4_K_M.GGUF")
        )

        assertTrue(summary.hasFormat(ModelFormat.GGUF))
        assertFalse(summary.hasFormat(ModelFormat.LITERTLM))
        assertFalse(summary.hasFormat(ModelFormat.TASK))
        assertFalse(summary.hasFormat(ModelFormat.TFLITE))
    }

    @Test
    fun `summary without runtime files is not runnable`() {
        val summary = ModelSummary(
            id = "x/y", modelId = "x/y", author = "x", likes = null, downloads = null,
            tags = emptyList(), libraryName = null, pipelineTag = null, lastModified = null,
            siblings = listOf("README.md", "config.json")
        )

        assertFalse(summary.hasRuntimeFiles)
        assertEquals(0, summary.runtimeFileCount)
    }

    private fun detailsWith(siblings: List<String>) = ModelDetails(
        id = "x/y",
        author = "x",
        pipelineTag = "text-generation",
        libraryName = null,
        tags = emptyList(),
        downloads = null,
        likes = null,
        lastModified = null,
        createdAt = null,
        siblings = siblings,
        usedStorage = null,
        sha = null,
        gated = "false",
        disabled = false,
        widgetPrompts = emptyList(),
        baseModel = null
    )
}
