package com.example.tinymodels.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin OkHttp client wrapper for the HuggingFace model catalog.
 * All calls are blocking; callers wrap them in a background dispatcher.
 */
@Singleton
class HuggingFaceApi @Inject constructor(
    private val client: OkHttpClient,
    private val auth: HuggingFaceAuth
) {
    companion object {
        private const val API_BASE = "https://huggingface.co/api/models"
        private const val HUB_BASE = "https://huggingface.co"
    }

    /** GET the model list. Overload supporting arbitrary search + pipeline filtering. */
    fun listModels(
        search: String? = null,
        pipelineTag: String? = null,
        author: String? = null,
        limit: Int = 100
    ): String {
        val params = mutableListOf(
            "limit=$limit",
            "direction=-1",
            "sort=downloads",
            "full=true"
        )
        when {
            !search.isNullOrBlank() -> params.add("search=${java.net.URLEncoder.encode(search, "UTF-8")}")
            author != null -> params.add("author=${java.net.URLEncoder.encode(author, "UTF-8")}")
            else -> params.add("author=litert-community")
        }
        pipelineTag?.takeIf { it.isNotBlank() }?.let {
            params.add("filter=${java.net.URLEncoder.encode(it, "UTF-8")}")
        }
        return get("$API_BASE?${params.joinToString("&")}")
    }

    /** GET full details for a single model. */
    fun getModelDetails(modelId: String): String = get("$API_BASE/$modelId")

    /** Resolve URL for a downloadable model file. */
    fun fileUrl(modelId: String, fileName: String): String =
        "$HUB_BASE/$modelId/resolve/main/$fileName?download=true"

    /** HEAD request to learn a file's size (handles HF redirect x-linked-size). */
    fun contentLength(url: String): Long {
        val request = auth.authorize(Request.Builder().url(url).head()).build()
        client.newCall(request).execute().use { response ->
            response.header("x-linked-size")?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
            if (!response.isSuccessful) {
                return response.header("content-length")?.toLongOrNull() ?: 0L
            }
            val bodyLen = response.body?.contentLength() ?: -1L
            if (bodyLen > 0) return bodyLen
            return response.header("content-length")?.toLongOrNull() ?: 0L
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP ${response.code}: ${response.message}")
            }
            return response.body?.string().orEmpty()
        }
    }
}
