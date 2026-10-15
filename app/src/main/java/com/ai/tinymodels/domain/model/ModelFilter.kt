package com.ai.tinymodels.domain.model

/**
 * Catalog chip: which slice of the mobile model hub to browse.
 *
 * [format] filters results to repos offering that file type; null keeps every
 * supported format. The chip's default browse branches and the format filter
 * both live in the model repository.
 */
enum class ModelFilter(val label: String, val format: ModelFormat?) {
    ALL("All", null),
    LITERTLM("LiteRT-LM", ModelFormat.LITERTLM),
    GGUF("GGUF", ModelFormat.GGUF),
    MEDIAPIPE("MediaPipe", ModelFormat.TASK),
    TFLITE("TFLite", ModelFormat.TFLITE)
}
