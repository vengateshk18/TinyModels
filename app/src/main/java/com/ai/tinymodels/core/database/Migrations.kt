package com.ai.tinymodels.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migrations for [TinyModelsDatabase].
 *
 * [MIGRATION_4_5] introduces the `model_files` subtable for per-file download
 * tracking. Each existing `downloaded_models` row's newline-joined `files`
 * column is split into individual `model_files` rows (status = `DOWNLOADED`),
 * then the now-stale `files` and `sizeBytes` columns are dropped from
 * `downloaded_models` (SQLite has no ALTER TABLE DROP COLUMN on older versions,
 * so the create-new-copy-rename pattern is used).
 */
object Migrations {

    /**
     * v5 → v6: adds context-compaction columns to `chats` —
     * `compactedSummary` (digest of summarized-away older turns) and
     * `compactedUpToCreatedAt` (cutoff; messages before it are covered by
     * the digest and excluded from the rebuilt session history).
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN compactedSummary TEXT")
            db.execSQL("ALTER TABLE chats ADD COLUMN compactedUpToCreatedAt INTEGER")
        }
    }

    /**
     * v6 → v7: adds `compactionCount` to `chats` — how many times the
     * conversation has been compacted (shown as a metric in the UI).
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN compactionCount INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v7 → v8: adds `webSearchEnabled` to `chats` — the per-chat opt-in for
     * the model's web_search tool (the global DataStore setting gates
     * availability; this column is the per-conversation state).
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE chats ADD COLUMN webSearchEnabled INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * v8 → v9: adds `imagePath` to `messages` — absolute path of a photo
     * attached to a user message for multimodal models (null = text-only).
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN imagePath TEXT")
        }
    }

    /**
     * v9 → v10: adds `isImported` to `model_files` — tracks whether a file
     * was imported from an external .litertlm file vs downloaded via the app.
     * Defaults to FALSE for existing files (downloaded via app).
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE model_files ADD COLUMN isImported INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v10 → v11: adds `isPinned` to `chats` so the history list can group
     * pinned conversations at the top. Existing chats default to unpinned.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chats ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v11 → v12: adds `format` to `model_files` for multi-format support
     * (LITERTLM, TASK, TFLITE, GGUF).
     *
     * Two release lines shipped their own v11 (one pinned chats, one added
     * the format column), so this migration adds each column only if it is
     * missing — it must not crash a user upgrading from either.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            if (!hasColumn(db, "model_files", "format")) {
                db.execSQL("ALTER TABLE model_files ADD COLUMN format TEXT NOT NULL DEFAULT 'LITERTLM'")
            }
            if (!hasColumn(db, "chats", "isPinned")) {
                db.execSQL("ALTER TABLE chats ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
            val cursor = db.query("PRAGMA table_info($table)")
            return cursor.use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == column) return true
                }
                false
            }
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. Create the new model_files table.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `model_files` (
                    `modelId` TEXT NOT NULL,
                    `fileName` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `sizeBytes` INTEGER NOT NULL,
                    `localPath` TEXT,
                    `error` TEXT,
                    PRIMARY KEY(`modelId`, `fileName`)
                )
                """.trimIndent()
            )

            // 2. Split each downloaded_models row's `files` column into
            //    model_files rows with status = DOWNLOADED.
            // Note: We use char(10) for newline as direct escape doesn't work in multiline strings
            db.execSQL(
                """
                INSERT INTO model_files (modelId, fileName, status, sizeBytes, localPath, error)
                SELECT dm.modelId, f.name, 'DOWNLOADED', 0, dm.localPath, NULL
                FROM downloaded_models dm
                JOIN (SELECT modelId, line AS name FROM (
                        SELECT modelId,
                               json_each.value AS line
                        FROM downloaded_models,
                             json_each('["' || REPLACE(files, char(10), '","') || '"]')
                    )
                ) f ON f.modelId = dm.modelId
                WHERE f.name IS NOT NULL AND f.name != ''
                """.trimIndent()
            )

            // 3. Recreate downloaded_models without files + sizeBytes.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `downloaded_models_new` (
                    `modelId` TEXT NOT NULL,
                    `author` TEXT,
                    `libraryName` TEXT,
                    `pipelineTag` TEXT,
                    `localPath` TEXT NOT NULL,
                    `downloadedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`modelId`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO downloaded_models_new (modelId, author, libraryName, pipelineTag, localPath, downloadedAt)
                SELECT modelId, author, libraryName, pipelineTag, localPath, downloadedAt
                FROM downloaded_models
                """.trimIndent()
            )
            db.execSQL("DROP TABLE downloaded_models")
            db.execSQL("ALTER TABLE downloaded_models_new RENAME TO downloaded_models")
        }
    }
}
