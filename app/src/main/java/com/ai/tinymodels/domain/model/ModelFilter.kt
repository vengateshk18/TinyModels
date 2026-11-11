package com.ai.tinymodels.domain.model

import androidx.annotation.StringRes
import com.ai.tinymodels.R

/** Pipeline-tag filter for the model browse screen. */
enum class ModelFilter(@StringRes val labelRes: Int, val pipelineTag: String?) {
    ALL(R.string.model_filter_all, null),
    TEXT_GENERATION(R.string.model_filter_text_generation, "text-generation"),
    IMAGE_GENERATION(R.string.model_filter_image_generation, "text-to-image")
}
