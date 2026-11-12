package com.ai.tinymodels.feature.widget

import android.content.Context
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

object WidgetStateLoader {
    suspend fun load(context: Context): WidgetUiState {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java
        )
        val downloadedCount = entryPoint.modelFileDao().observeDownloaded().first().size
        val lastChatTitle = entryPoint.chatDao().observeChatSummaries().first()
            .firstOrNull()?.title?.takeIf { it.isNotBlank() }
        return WidgetStateResolver.resolve(
            downloadedModelCount = downloadedCount,
            lastChatTitle = lastChatTitle
        )
    }
}
