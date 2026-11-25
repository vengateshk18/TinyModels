package com.ai.tinymodels

import com.ai.tinymodels.core.network.dto.ModelDtoParser
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
      "trendingScore": 42,
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
      "config": {"model_type": "gemma3_text"},
      "gguf": {"total": 286720000, "architecture": "gemma3", "context_length": 8192},
      "safetensors": {"total": 270000000, "parameters": {"BF16": 270000000}},
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
        assertEquals("gemma", d.license)
        assertEquals(1432447106L, d.usedStorage)
        assertEquals(false, d.disabled)
        assertEquals(42.0, d.trendingScore ?: 0.0, 0.001)
        assertEquals(270000000L, d.parameterCount)
        assertEquals(8192, d.contextLength)
        assertEquals(286720000L, d.ggufTotalBytes)
        assertEquals("gemma3", d.ggufArchitecture)
    }

    @Test
    fun parsesSummaryTrendingAndCreatedAt() {
        val listJson = """
        [
          {
            "id": "org/model",
            "modelId": "org/model",
            "author": "org",
            "likes": 5,
            "downloads": 10,
            "trendingScore": 7.5,
            "tags": ["text-generation","license:apache-2.0"],
            "library_name": "gguf",
            "pipeline_tag": "text-generation",
            "lastModified": "2026-01-02T00:00:00.000Z",
            "createdAt": "2026-01-01T00:00:00.000Z",
            "siblings": [{"rfilename":"m-q4_k_m.gguf"}]
          }
        ]
        """.trimIndent()
        val list = ModelDtoParser.parseModelList(listJson)
        assertEquals(1, list.size)
        val s = list.first()
        assertEquals("2026-01-01T00:00:00.000Z", s.createdAt)
        assertEquals(7.5, s.trendingScore ?: 0.0, 0.001)
        assertEquals("apache-2.0", s.license)
    }

    @Test
    fun parsesSummaryContextWindowAndAuthorFromExpandedList() {
        val listJson = """
        [{
          "id": "LiquidAI/LFM3-1.7B-GGUF",
          "modelId": "LiquidAI/LFM3-1.7B-GGUF",
          "author": "LiquidAI",
          "tags": ["gguf"],
          "siblings": [{"rfilename":"LFM3-1.7B-Q4_K_M.gguf"}],
          "gguf": {"context_length": 262144}
        }]
        """.trimIndent()
        val summary = ModelDtoParser.parseModelList(listJson).first()
        assertEquals("LiquidAI", summary.author)
        assertEquals(262144, summary.contextLength)
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
