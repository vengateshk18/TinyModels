package com.example.tinymodels.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.tinymodels.core.database.entities.ChatEntity
import com.example.tinymodels.core.database.entities.DownloadedModelEntity
import com.example.tinymodels.core.database.entities.MessageEntity
import com.example.tinymodels.core.database.entities.ModelFileEntity

/**
 * Single source of truth for local persistence.
 *
 * Version 2 adds chat persistence ([ChatEntity] + [MessageEntity]) on top of the
 * original downloaded-models table. Version 5 adds the `model_files` subtable for
 * per-file download tracking ([ModelFileEntity]).
 */
@Database(
    entities = [
        DownloadedModelEntity::class,
        ModelFileEntity::class,
        ChatEntity::class,
        MessageEntity::class
    ],
        version = 5,
    exportSchema = false
)
abstract class TinyModelsDatabase : RoomDatabase() {
    abstract fun downloadedModelDao(): DownloadedModelDao
    abstract fun modelFileDao(): ModelFileDao
    abstract fun chatDao(): ChatDao

    companion object {
        const val NAME = "tiny_models.db"
    }
}
