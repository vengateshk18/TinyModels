package com.ai.tinymodels.di

import com.ai.tinymodels.core.common.DefaultDispatcherProvider
import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.common.SnackbarManager
import com.ai.tinymodels.domain.usecase.model.DownloadManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(
        impl: DefaultDispatcherProvider
    ): DispatcherProvider

}