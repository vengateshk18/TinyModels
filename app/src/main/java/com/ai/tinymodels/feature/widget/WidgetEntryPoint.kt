package com.ai.tinymodels.feature.widget

import com.ai.tinymodels.core.database.ChatDao
import com.ai.tinymodels.core.database.ModelFileDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun modelFileDao(): ModelFileDao
    fun chatDao(): ChatDao
}
