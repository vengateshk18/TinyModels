package com.example.tinymodels.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object OkHttpUtil {
    private val client = OkHttpClient()

    suspend fun contentLength(url: String): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).head().build()
        client.newCall(request).execute().use { response ->
            // Some hosts (Hugging Face) return a 302 with a header `x-linked-size` containing
            // the real file size. Prefer that header when available. Also fall back to
            // body.contentLength() or the content-length header.
            val linkedSize = response.header("x-linked-size")?.toLongOrNull()
            if (linkedSize != null && linkedSize > 0) return@use linkedSize

            if (!response.isSuccessful) {
                return@use response.header("content-length")?.toLongOrNull() ?: 0L
            }

            val bodyLen = response.body?.contentLength() ?: -1L
            if (bodyLen > 0) return@use bodyLen

            response.header("content-length")?.toLongOrNull() ?: 0L
        }
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .apply {
                    headers.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: ${response.message}")
                }
                response.body?.string() ?: ""
            }
        }

    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(url)
            .apply {
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${response.message}")
            }
            response.body?.string() ?: ""
        }
    }

    suspend fun download(
        url: String,
        target: java.io.File,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${response.message}")
            }
            val body = response.body ?: throw IllegalStateException("Empty response")
            // Prefer x-linked-size header when available (Hugging Face redirect exposes real size)
            var total = body.contentLength()
            val linkedSize = response.header("x-linked-size")?.toLongOrNull()
            if (linkedSize != null && linkedSize > 0) total = linkedSize

            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        output.write(buffer, 0, count)
                        downloaded += count
                        // total may be -1 or 0 when unknown; preserve that so callers can handle indeterminate
                        onProgress(downloaded, if (total > 0) total else -1L)
                    }
                }
            }
        }
    }
}