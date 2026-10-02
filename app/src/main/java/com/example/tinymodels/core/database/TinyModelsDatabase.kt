package com.example.tinymodels.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.tinymodels.core.database.entities.ChatEntity
import com.example.tinymodels.core.database.entities.DownloadedModelEntity
import com.example.tinymodels.core.database.entities.MessageEntity

/**
 * Single source of truth for local persistence.
 *
 * Version 2 adds chat persistence ([ChatEntity] + [MessageEntity]) on top of the
 * original downloaded-models table. The legacy v1 `models/local/TinyModelsDatabase`
 * is superseded by this class and removed during the revamp.
 */
@Database(
    entities = [
        DownloadedModelEntity::class,
        ChatEntity::class,
        MessageEntity::class
    ],
        version = 4,
    exportSchema = false
)
abstract class TinyModelsDatabase : RoomDatabase() {
    abstract fun downloadedModelDao(): DownloadedModelDao
    abstract fun chatDao(): ChatDao

    companion object {
        const val NAME = "tiny_models.db"
    }
}
