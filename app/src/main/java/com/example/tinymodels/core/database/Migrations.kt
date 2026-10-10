package com.example.tinymodels.core.database

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
