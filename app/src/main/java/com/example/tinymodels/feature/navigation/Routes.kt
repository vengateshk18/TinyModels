package com.example.tinymodels.feature.navigation

import android.net.Uri

/** Navigation routes for the single-activity app. */
object Routes {
    const val CHAT = "chat"
    const val MODELS = "models"
    const val MODEL_DETAILS = "model_details/{modelId}"
    const val DOWNLOADED_MODELS = "downloaded_models"
    const val SETTINGS = "settings"

    /** modelIds contain '/', so they must be URL-encoded for the path segment. */
    fun modelDetails(modelId: String) = "model_details/${Uri.encode(modelId)}"
}
