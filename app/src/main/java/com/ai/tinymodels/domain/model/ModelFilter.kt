package com.ai.tinymodels.domain.model

import androidx.annotation.StringRes
import com.ai.tinymodels.R

/**
 * Catalog chip: which slice of the model hub to browse.
 *
 * [format] filters results to repos offering that file type; null keeps every
 * supported format. The chip's default browse branches and the format filter
 * both live in the model repository. Format names are untranslated technical
 * tokens.
 */
enum class ModelFilter(@StringRes val labelRes: Int, val format: ModelFormat?) {
    ALL(R.string.model_filter_all, null),
    LITERTLM(R.string.model_format_litertlm, ModelFormat.LITERTLM),
    GGUF(R.string.model_format_gguf, ModelFormat.GGUF),
    MEDIAPIPE(R.string.model_format_mediapipe, ModelFormat.TASK),
    TFLITE(R.string.model_format_tflite, ModelFormat.TFLITE)
}
