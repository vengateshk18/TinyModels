package com.example.tinymodels

import com.example.tinymodels.core.network.dto.ModelDtoParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies parseDetails against a real HF model-details payload. */
class ModelDtoParserTest {

    private val sampleJson = """
    {
      "id": "litert-community/functiongemma-270m-ft-mobile-actions",
      "pipeline_tag": "text-generation",
      "library_name": "litert-lm",
      "tags": ["litert-lm", "gemma3", "text-generation", "license:gemma"],
      "downloads": 1151,
      "likes": 247,
      "modelId": "litert-community/functiongemma-270m-ft-mobile-actions",
      "author": "litert-community",
      "sha": "f752a74080682b379823794defdbbdf8c2663609",
      "lastModified": "2026-08-31T13:49:04.000Z",
      "gated": "auto",
      "disabled": false,
      "widgetData": [
        {"text": "Hi, what can you help me with?"},
        {"text": "What is 84 * 3 / 2?"},
        {"text": "Tell me an interesting fact about the universe!"},
        {"text": "Explain quantum computing in simple terms."}
      ],
      "cardData": {"base_model": "google/functiongemma-270m-it", "license": "gemma"},
      "siblings": [
        {"rfilename": ".gitattributes"},
        {"rfilename": "README.md"},
        {"rfilename": "functiongemma-270m-ft-mobile-actions_Google_Tensor_G5.litertlm"},
        {"rfilename": "mobile_actions_q8_ekv1024.litertlm"}
      ],
      "createdAt": "2026-02-25T23:13:08.000Z",
      "usedStorage": 1432447106
    }
    """.trimIndent()

    @Test
    fun parsesAllRichFields() {
        val d = ModelDtoParser.parseDetails(sampleJson)

        assertEquals("litert-community/functiongemma-270m-ft-mobile-actions", d.id)
        assertEquals("litert-community", d.author)
        assertEquals("text-generation", d.pipelineTag)
        assertEquals("litert-lm", d.libraryName)
        assertEquals(1151L, d.downloads)
        assertEquals(247, d.likes)
        assertEquals("f752a74080682b379823794defdbbdf8c2663609", d.sha)
        assertEquals("2026-08-31T13:49:04.000Z", d.lastModified)
        assertEquals("2026-02-25T23:13:08.000Z", d.createdAt)
        assertEquals("google/functiongemma-270m-it", d.baseModel)
        assertEquals(1432447106L, d.usedStorage)
        assertEquals(false, d.disabled)
    }

    @Test
    fun gatedModel_isGatedTrue() {
        val d = ModelDtoParser.parseDetails(sampleJson)
        assertEquals("auto", d.gated)
        assertTrue(d.isGated)
    }

    @Test
    fun extractsLiteRtFilesAndPrompts() {
        val d = ModelDtoParser.parseDetails(sampleJson)
        assertEquals(2, d.liteRtFiles.size)
        assertTrue(d.liteRtFiles.all { it.endsWith(".litertlm") })
        assertEquals(4, d.widgetPrompts.size)
        assertEquals("Hi, what can you help me with?", d.widgetPrompts.first())
    }
}
