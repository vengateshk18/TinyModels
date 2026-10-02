package com.example.tinymodels.domain.model

/** Pipeline-tag filter for the model browse screen. */
enum class ModelFilter(val label: String, val pipelineTag: String?) {
    ALL("All", null),
    TEXT_GENERATION("Text Generation", "text-generation"),
    IMAGE_GENERATION("Image Generation", "text-to-image")
}
