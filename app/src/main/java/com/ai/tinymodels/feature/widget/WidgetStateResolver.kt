package com.ai.tinymodels.feature.widget

enum class WidgetDisplayState {
    GET_STARTED,
    READY,
    CONTINUE
}

data class WidgetUiState(
    val displayState: WidgetDisplayState,
    val downloadedModelCount: Int = 0,
    val lastChatTitle: String? = null
)

object WidgetStateResolver {
    fun resolve(
        downloadedModelCount: Int,
        lastChatTitle: String?
    ): WidgetUiState {
        return when {
            downloadedModelCount <= 0 -> WidgetUiState(WidgetDisplayState.GET_STARTED)
            !lastChatTitle.isNullOrBlank() -> WidgetUiState(
                displayState = WidgetDisplayState.CONTINUE,
                downloadedModelCount = downloadedModelCount,
                lastChatTitle = lastChatTitle
            )
            else -> WidgetUiState(
                displayState = WidgetDisplayState.READY,
                downloadedModelCount = downloadedModelCount
            )
        }
    }
}
