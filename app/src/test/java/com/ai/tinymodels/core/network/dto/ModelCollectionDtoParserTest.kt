package com.ai.tinymodels.core.network.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCollectionDtoParserTest {

    @Test
    fun `parseCollection extracts metadata and model ids`() {
        val json = """
        {
          "slug": "google/gemma-4-qat-mobile-6a22f0b8e6a88f6f7d2442b4",
          "title": "Gemma 4 QAT Mobile",
          "description": "Mobile-optimized quantized Gemma 4 models.",
          "owner": {"name":"google","avatarUrl":"https://cdn.example/avatar.png"},
          "items": [
            {"type":"model","id":"google/gemma-4-E2B-it-qat-mobile-transformers","position":0},
            {"type":"dataset","id":"google/some-dataset","position":1},
            {"type":"model","id":"google/gemma-4-E4B-it-qat-mobile-transformers","position":2}
          ]
        }
        """.trimIndent()

        val collection = ModelCollectionDtoParser.parseCollection(json)

        assertEquals("google/gemma-4-qat-mobile-6a22f0b8e6a88f6f7d2442b4", collection.slug)
        assertEquals("Gemma 4 QAT Mobile", collection.title)
        assertEquals("Mobile-optimized quantized Gemma 4 models.", collection.description)
        assertEquals("google", collection.owner)
        assertEquals("https://cdn.example/avatar.png", collection.avatarUrl)
        assertEquals(2, collection.modelIds.size)
        assertEquals("google/gemma-4-E2B-it-qat-mobile-transformers", collection.modelIds[0])
        assertEquals("google/gemma-4-E4B-it-qat-mobile-transformers", collection.modelIds[1])
    }

    @Test
    fun `parseCollection handles missing optional fields`() {
        val json = """{"slug":"o/c-1","title":"C","items":[]}"""
        val collection = ModelCollectionDtoParser.parseCollection(json)
        assertEquals("o/c-1", collection.slug)
        assertEquals("C", collection.title)
        assertEquals("", collection.description)
        assertEquals("o", collection.owner)
        assertTrue(collection.modelIds.isEmpty())
    }
}
