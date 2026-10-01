package com.example.titymodels.models.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [DownloadedModelEntity::class], version = 1, exportSchema = false)
abstract class TityModelsDatabase : RoomDatabase() {
    abstract fun downloadedModelDao(): DownloadedModelDao
}
