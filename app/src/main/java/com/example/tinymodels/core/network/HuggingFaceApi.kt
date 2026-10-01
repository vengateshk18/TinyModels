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
    private val client: OkHttpClient
) {
    companion object {
        private const val API_BASE = "https://huggingface.co/api/models"
        private const val HUB_BASE = "https://huggingface.co"
    }

    /** GET the LiteRT-community model list, sorted by downloads. */
    fun listModels(): String {
        val url = "$API_BASE?author=litert-community&limit=100&direction=-1&sort=downloads&full=true"
        return get(url)
    }

    /** GET full details for a single model. */
    fun getModelDetails(modelId: String): String = get("$API_BASE/$modelId")

    /** Resolve URL for a downloadable model file. */
    fun fileUrl(modelId: String, fileName: String): String =
        "$HUB_BASE/$modelId/resolve/main/$fileName?download=true"

    /** HEAD request to learn a file's size (handles HF redirect x-linked-size). */
    fun contentLength(url: String): Long {
        val request = Request.Builder().url(url).head().build()
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
