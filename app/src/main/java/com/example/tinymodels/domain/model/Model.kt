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
    val lastModified: String?,
    val siblings: List<String> = emptyList()
) {
    /** True if any sibling file is a runnable runtime format. */
    val hasRuntimeFiles: Boolean
        get() = siblings.any { it.isRuntimeFile() }

    /** Count of runnable files in siblings. */
    val runtimeFileCount: Int
        get() = siblings.count { it.isRuntimeFile() }

    private fun String.isRuntimeFile() =
        endsWith(".litertlm", ignoreCase = true) ||
        endsWith(".task", ignoreCase = true) ||
        endsWith(".tflite", ignoreCase = true)
}

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
    val siblings: List<String>,
    /** Total repo size in bytes (HF `usedStorage`) — shown as the download size. */
    val usedStorage: Long?,
    val sha: String?,
    /** HF gating ("auto"/"manual"/false). Non-null & not "false" means access is gated. */
    val gated: String?,
    val disabled: Boolean,
    /** Sample prompts from the model card (HF `widgetData[].text`). */
    val widgetPrompts: List<String>,
    /** Upstream base model (from cardData.base_model). */
    val baseModel: String?
) {
    /** Files that can be run by the LiteRT-LM runtime. */
    val liteRtFiles: List<String>
        get() = siblings.filter { it.endsWith(".litertlm", ignoreCase = true) }

    /** All runnable runtime files (.litertlm + .task + .tflite). */
    val runtimeFiles: List<String>
        get() = siblings.filter {
            it.endsWith(".litertlm", ignoreCase = true) ||
            it.endsWith(".task", ignoreCase = true) ||
            it.endsWith(".tflite", ignoreCase = true)
        }

    /** True when access requires accepting a license / login on HuggingFace. */
    val isGated: Boolean
        get() = gated != null && gated != "false" && gated.isNotBlank()
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