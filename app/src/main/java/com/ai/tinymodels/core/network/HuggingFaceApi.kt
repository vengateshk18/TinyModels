package com.ai.tinymodels.core.network

import com.ai.tinymodels.core.common.TinyConstants
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
        private const val API_BASE = TinyConstants.HUGGING_FACE_API_BASE_URL
        private const val HUB_BASE = TinyConstants.HUGGING_FACE_HUB_BASE_URL

        /**
         * Build the catalog URL. Pure so the query shape is unit-tested: the
         * browse list must never silently scope itself to a single author —
         * scoping is the caller's explicit choice (see `branchesFor`), and a
         * hidden default here silently hides every other publisher.
         */
        fun buildModelListUrl(
            search: String?,
            pipelineTag: String?,
            author: String?,
            limit: Int = 100,
            sort: String = "downloads",
            expand: List<String> = emptyList(),
            tagFilter: String? = null,
        ): String {
            val params = mutableListOf(
                "limit=$limit",
                "direction=-1",
                "sort=${encode(sort)}"
            )
            if (expand.isEmpty()) {
                params.add("full=true")
            } else {
                expand.forEach { params.add("expand=${encode(it)}") }
            }
            search?.takeIf { it.isNotBlank() }?.let { params.add("search=${encode(it)}") }
            author?.takeIf { it.isNotBlank() }?.let { params.add("author=${encode(it)}") }
            pipelineTag?.takeIf { it.isNotBlank() }?.let { params.add("filter=${encode(it)}") }
            tagFilter?.takeIf { it.isNotBlank() }?.let { params.add("filter=${encode(it)}") }
            return "$API_BASE?${params.joinToString("&")}"
        }

        fun buildModelDetailsUrl(modelId: String, expand: List<String> = emptyList()): String {
            val base = "$API_BASE/$modelId"
            if (expand.isEmpty()) return base
            return "$base?${expand.joinToString("&") { "expand=${encode(it)}" }}"
        }

        fun buildCollectionUrl(slug: String): String {
            val encoded = slug.trim('/').split('/').filter { it.isNotBlank() }
                .joinToString("/") { encode(it) }
            return "$HUB_BASE/api/collections/$encoded"
        }

        fun buildCollectionListUrl(owner: String? = null, limit: Int = 20): String {
            val params = mutableListOf("limit=$limit")
            owner?.takeIf { it.isNotBlank() }?.let { params.add("owner=${encode(it)}") }
            return "$HUB_BASE/api/collections?${params.joinToString("&")}"
        }

        private fun encode(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8")
    }

    /** GET the model list. Overload supporting arbitrary search + pipeline filtering. */
    fun listModels(
        search: String? = null,
        pipelineTag: String? = null,
        author: String? = null,
        limit: Int = 100,
        sort: String = "downloads",
        expand: List<String> = emptyList(),
        tagFilter: String? = null,
    ): String = get(buildModelListUrl(search, pipelineTag, author, limit, sort, expand, tagFilter))

    /** GET full details for a single model. */
    fun getModelDetails(modelId: String, expand: List<String> = emptyList()): String =
        get(buildModelDetailsUrl(modelId, expand))

    /** GET model tag taxonomy grouped by type (e.g. library, language, license, pipeline_tag). */
    fun getModelTagsByType(): String = get("$HUB_BASE/api/models-tags-by-type")

    /** GET a Hugging Face collection by slug (`owner/collection-id`). */
    fun getCollection(slug: String): String = get(buildCollectionUrl(slug))

    /** GET collections, optionally filtered by owner. */
    fun listCollections(owner: String? = null, limit: Int = 20): String =
        get(buildCollectionListUrl(owner = owner, limit = limit))

    /** GET repository tree (file/folder entries, optionally recursive) for device-fit/file inspection. */
    fun getModelTree(
        modelId: String,
        revision: String = "main",
        path: String = "",
        recursive: Boolean = true,
        expand: Boolean = false
    ): String {
        val encodedPath = path.trim('/').split('/').filter { it.isNotBlank() }
            .joinToString("/") { encode(it) }
        val suffix = if (encodedPath.isEmpty()) revision else "$revision/$encodedPath"
        val params = buildList {
            if (recursive) add("recursive=true")
            if (expand) add("expand=true")
        }
        val query = if (params.isEmpty()) "" else "?${params.joinToString("&")}"
        return get("$HUB_BASE/api/models/$modelId/tree/$suffix$query")
    }

    /** Resolve URL for a downloadable model file. */
    fun fileUrl(modelId: String, fileName: String): String =
        "$HUB_BASE/$modelId/resolve/main/$fileName?download=true"

    /** HEAD request to learn a file's size (handles HF redirect x-linked-size). */
    fun contentLength(url: String): Long {
        val request = auth.authorize(Request.Builder().url(url).head()).build()
        client.newCall(request).execute().use { response ->
            response.header(TinyConstants.HTTP_HEADER_LINKED_SIZE)?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
            if (!response.isSuccessful) {
                return response.header("content-length")?.toLongOrNull() ?: 0L
            }
            val bodyLen = response.body?.contentLength() ?: -1L
            if (bodyLen > 0) return bodyLen
            return response.header("content-length")?.toLongOrNull() ?: 0L
        }
    }

    private fun get(url: String): String {
        val request = auth.authorize(Request.Builder().url(url).get()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpException(response.code, "HTTP ${response.code}: ${response.message}")
            }
            return response.body?.string().orEmpty()
        }
    }
}
