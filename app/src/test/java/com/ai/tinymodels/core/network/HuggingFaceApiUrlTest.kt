package com.ai.tinymodels.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URL-building tests for [HuggingFaceApi.buildModelListUrl] — the catalog
 * must never silently scope itself to a single author again.
 */
class HuggingFaceApiUrlTest {

    @Test
    fun `default browse has no author scoping`() {
        val url = HuggingFaceApi.buildModelListUrl(
            search = null, pipelineTag = null, author = null, limit = 100
        )

        assertTrue(url.startsWith("https://huggingface.co/api/models?"))
        assertTrue(url.contains("limit=100"))
        assertTrue(url.contains("full=true"))
        assertTrue(url.contains("sort=downloads"))
        assertTrue(url.contains("direction=-1"))
        assertFalse(url.contains("author="))
        assertFalse(url.contains("search="))
    }

    @Test
    fun `search is URL-encoded`() {
        val url = HuggingFaceApi.buildModelListUrl(
            search = "qwen 2.5", pipelineTag = null, author = null, limit = 50
        )

        assertTrue(url.contains("search=qwen+2.5"))
        assertTrue(url.contains("limit=50"))
    }

    @Test
    fun `author and pipeline filter are added when given`() {
        val url = HuggingFaceApi.buildModelListUrl(
            search = null, pipelineTag = "text-generation", author = "litert-community", limit = 60
        )

        assertTrue(url.contains("author=litert-community"))
        assertTrue(url.contains("filter=text-generation"))
        assertFalse(url.contains("search="))
    }

    @Test
    fun `blank values are treated as absent`() {
        val url = HuggingFaceApi.buildModelListUrl(
            search = "", pipelineTag = " ", author = "", limit = 60
        )

        assertFalse(url.contains("search="))
        assertFalse(url.contains("filter="))
        assertFalse(url.contains("author="))
    }
}
