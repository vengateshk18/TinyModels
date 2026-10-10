package com.example.tinymodels.di

import android.content.Context
import androidx.room.Room
import com.example.tinymodels.core.database.ChatDao
import com.example.tinymodels.core.database.DownloadedModelDao
import com.example.tinymodels.core.database.Migrations
import com.example.tinymodels.core.database.ModelFileDao
import com.example.tinymodels.core.database.TinyModelsDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TinyModelsDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            TinyModelsDatabase::class.java,
            TinyModelsDatabase.NAME
        )
            // The app is pre-release; recreate on migration rather than ship
            // migration SQL for the v1 -> v2 chat-persistence addition.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .addMigrations(Migrations.MIGRATION_4_5, Migrations.MIGRATION_5_6, Migrations.MIGRATION_6_7)
            .build()

    @Provides
    fun provideDownloadedModelDao(db: TinyModelsDatabase): DownloadedModelDao =
        db.downloadedModelDao()

    @Provides
    fun provideModelFileDao(db: TinyModelsDatabase): ModelFileDao = db.modelFileDao()

    @Provides
    fun provideChatDao(db: TinyModelsDatabase): ChatDao = db.chatDao()
}
