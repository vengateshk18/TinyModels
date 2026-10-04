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
    const val DOWNLOADED_FILE_DETAIL = "downloaded_file_detail/{modelId}/{fileName}"
    const val CHAT_ROOM = "chat_room/{chatId}"
    const val CHAT_ROOM_WITH_PREF = "chat_room/{chatId}?preferredModelId={preferredModelId}"
    const val DOWNLOADED_MODELS = "downloaded_models"
    const val DEVICE_INFO = "device_info"
    const val BENCHMARK = "benchmark"

    /** modelIds contain '/', so they must be URL-encoded for the path segment. */
    fun modelDetails(modelId: String) = "model_details/${Uri.encode(modelId)}"

    /** Downloaded file detail route. */
    fun downloadedFileDetail(modelId: String, fileName: String) =
        "downloaded_file_detail/${Uri.encode(modelId)}/${Uri.encode(fileName)}"

    /** chatRoom route for a specific chat session. */
    fun chatRoom(chatId: String) = "chat_room/${Uri.encode(chatId)}"

    /** chatRoom route with a preferred model pre-selected (from model detail). */
    fun chatRoomWithPreference(preferredModelId: String) =
        "chat_room/new?preferredModelId=${Uri.encode(preferredModelId)}"
}
