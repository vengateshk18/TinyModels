package com.example.tinymodels.core.database.entities

import androidx.room.Entity

/**
 * Per-file download tracking row. Parent = [DownloadedModelEntity] (via `modelId`).
 *
 * Created pre-emptively (status = `NOT_DOWNLOADED`) when the user opens a model
 * detail page, then updated as a single-file download progresses.
 */
@Entity(
    tableName = "model_files",
    primaryKeys = ["modelId", "fileName"]
)
data class ModelFileEntity(
    val modelId: String,          // FK -> downloaded_models.modelId
    val fileName: String,         // e.g. "model_G5.litertlm"
    val status: String,           // NOT_DOWNLOADED | DOWNLOADING | DOWNLOADED | FAILED
    val sizeBytes: Long,          // per-file size (from HEAD or actual)
    val localPath: String?,       // directory path, set when DOWNLOADED
    val error: String?            // set when FAILED
)
