package com.example.tinymodels.feature.navigation

import android.net.Uri

/** Navigation routes for the single-activity app. */
object Routes {
    // Bottom-nav tab destinations
    const val HOME = "home"
    const val CHAT = "chat"
    const val MODELS = "models"
    const val SETTINGS = "settings"

    // Pushed (full-screen) routes
    const val MODEL_DETAILS = "model_details/{modelId}"
    const val CHAT_ROOM = "chat_room/{chatId}"
    const val DOWNLOADED_MODELS = "downloaded_models"

    /** modelIds contain '/', so they must be URL-encoded for the path segment. */
    fun modelDetails(modelId: String) = "model_details/${Uri.encode(modelId)}"

    /** chatRoom route for a specific chat session. */
    fun chatRoom(chatId: String) = "chat_room/${Uri.encode(chatId)}"
}
