package com.ai.tinymodels.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ai.tinymodels.core.database.entities.ChatEntity
import com.ai.tinymodels.core.database.entities.DownloadedModelEntity
import com.ai.tinymodels.core.database.entities.MessageEntity
import com.ai.tinymodels.core.database.entities.ModelFileEntity

/**
 * Single source of truth for local persistence.
 *
 * Version 2 adds chat persistence ([ChatEntity] + [MessageEntity]) on top of the
 * original downloaded-models table. Version 5 adds the `model_files` subtable for
 * per-file download tracking ([ModelFileEntity]). Version 6 adds context-compaction
 * columns to `chats` ([ChatEntity.compactedSummary] / `compactedUpToCreatedAt`).
 * Version 9 adds the photo attachment path to `messages`
 * ([MessageEntity.imagePath]) for multimodal models.
 * Version 10 adds `isImported` to `model_files`.
 * Version 12 unifies two independently-shipped v11 schemas (isPinned chats
 * and the model-format column) and adds `format` idempotently.
 */
@Database(
    entities = [
        DownloadedModelEntity::class,
        ModelFileEntity::class,
        ChatEntity::class,
        MessageEntity::class
    ],
        version = 12,
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
