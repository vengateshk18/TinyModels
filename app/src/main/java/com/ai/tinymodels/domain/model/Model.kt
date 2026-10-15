package com.ai.tinymodels.domain.model

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

    private fun String.isRuntimeFile() = isRunnableFile()
}

/** Extensions of every model file the app can download and track. */
internal val MODEL_FILE_EXTENSIONS = listOf(".litertlm", ".task", ".tflite", ".gguf")

/**
 * Display name for a model file: strips the directory path and a supported
 * model-file extension (e.g. "model_G5.litertlm" -> "model_G5").
 */
fun String.modelFileDisplayName(): String {
    val base = substringAfterLast('/')
    return MODEL_FILE_EXTENSIONS.firstOrNull { base.endsWith(it, ignoreCase = true) }
        ?.let { base.dropLast(it.length) }
        ?: base
}

/** Supported model file formats. */
enum class ModelFormat {
    LITERTLM,
    TASK,
    TFLITE,
    GGUF;

    /** Short user-facing label for this format (shown on model/file rows). */
    val displayName: String
        get() = when (this) {
            LITERTLM -> "LiteRT-LM"
            TASK -> "MediaPipe"
            TFLITE -> "TFLite"
            GGUF -> "GGUF"
        }

    companion object {
        /** Best-effort format detection from a file name. Defaults to LiteRT-LM. */
        fun fromFileName(fileName: String): ModelFormat = when {
            fileName.endsWith(".task", ignoreCase = true) -> TASK
            fileName.endsWith(".tflite", ignoreCase = true) -> TFLITE
            fileName.endsWith(".gguf", ignoreCase = true) -> GGUF
            else -> LITERTLM
        }
    }
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

    /** MediaPipe .task files (single-file bundles with tokenizer + chat template). */
    val taskFiles: List<String>
        get() = siblings.filter { it.endsWith(".task", ignoreCase = true) }

    /** TensorFlow Lite .tflite files (requires external tokenizer). */
    val tfliteFiles: List<String>
        get() = siblings.filter { it.endsWith(".tflite", ignoreCase = true) }

    /** GGUF files for llama.cpp runtime. */
    val ggufFiles: List<String>
        get() = siblings.filter { it.endsWith(".gguf", ignoreCase = true) }

    /** Only .litertlm files - the only format supported by LiteRT-LM runtime. */
    val runtimeFiles: List<String>
        get() = siblings.filter { it.endsWith(".litertlm", ignoreCase = true) }

    /** All runnable files across all supported formats. */
    val allRunnableFiles: List<String>
        get() = siblings.filter { it.isRunnableFile() }

    /** True when access requires accepting a license / login on HuggingFace. */
    val isGated: Boolean
        get() = gated != null && gated != "false" && gated.isNotBlank()
}

private fun String.isRunnableFile() =
    MODEL_FILE_EXTENSIONS.any { endsWith(it, ignoreCase = true) }

/** A model that has been downloaded onto the device (lightweight parent metadata). */
data class DownloadedModel(
    val modelId: String,
    val author: String?,
    val libraryName: String?,
    val pipelineTag: String?,
    val localPath: String,
    val downloadedAt: Long
)

/** Per-file download lifecycle status. */
enum class FileDownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}

/** A single downloadable file tracked under its parent [DownloadedModel]. */
data class DownloadedModelFile(
    val modelId: String,
    val fileName: String,
    val status: FileDownloadStatus,
    val sizeBytes: Long,
    val localPath: String?,
    val error: String?,
    /** True when this file was imported from external storage (any supported format). */
    val isImported: Boolean = false,
    /** The format of this model file. */
    val format: ModelFormat = ModelFormat.LITERTLM
)

/** Metadata about the currently loaded model + how it was loaded. */
data class LoadedModel(
    val modelId: String,
    val backendUsed: BackendPreference,
    val maxNumTokens: Int,
    /** True when the live engine exposes a vision encoder (photo input works). */
    val visionEnabled: Boolean = false
)