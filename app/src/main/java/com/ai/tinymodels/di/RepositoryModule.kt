package com.ai.tinymodels.di

import com.ai.tinymodels.core.common.SnackbarManager
import com.ai.tinymodels.data.repository.ChatRepositoryImpl
import com.ai.tinymodels.data.repository.ModelRepositoryImpl
import com.ai.tinymodels.data.repository.SettingsRepositoryImpl
import com.ai.tinymodels.data.repository.WebSearchRepositoryImpl
import com.ai.tinymodels.domain.repository.ChatRepository
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.repository.SettingsRepository
import com.ai.tinymodels.domain.repository.WebSearchRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    @Singleton
    abstract fun bindModelRepository(impl: ModelRepositoryImpl): ModelRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindWebSearchRepository(impl: WebSearchRepositoryImpl): WebSearchRepository

    companion object {
        @Provides
        @Singleton
        fun provideSnackbarManager(): SnackbarManager = SnackbarManager()
    }
}
