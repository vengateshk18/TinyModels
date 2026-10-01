package com.example.tinymodels.models.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [DownloadedModelEntity::class], version = 1, exportSchema = false)
abstract class TinyModelsDatabase : RoomDatabase() {
    abstract fun downloadedModelDao(): DownloadedModelDao
}
