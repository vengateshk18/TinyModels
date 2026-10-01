package com.example.tinymodels.domain.model

/** A browsable model from the HuggingFace catalog. */
data class ModelSummary(
    val id: String,
    val modelId: String,
    val author: String,
    val likes: Int?,
    val downloads: Long?,
    val tags: List<String>,
    val libraryName: String?,
    val pipelineTag: String?,
    val lastModified: String?
)

/** Full details for a single model, including downloadable files. */
data class ModelDetails(
    val id: String,
    val author: String?,
    val pipelineTag: String?,
    val libraryName: String?,
    val tags: List<String>,
    val downloads: Long?,
    val likes: Int?,
    val lastModified: String?,
    val createdAt: String?,
    val siblings: List<String>
) {
    /** Files that can be run by the LiteRT-LM runtime. */
    val liteRtFiles: List<String>
        get() = siblings.filter { it.endsWith(".litertlm", ignoreCase = true) }
}

/** A model that has been downloaded onto the device. */
data class DownloadedModel(
    val modelId: String,
    val author: String?,
    val libraryName: String?,
    val pipelineTag: String?,
    val localPath: String,
    val files: List<String>,
    val sizeBytes: Long,
    val downloadedAt: Long
)

/** Metadata about the currently loaded model + how it was loaded. */
data class LoadedModel(
    val modelId: String,
    val backendUsed: BackendPreference,
    val maxNumTokens: Int
)