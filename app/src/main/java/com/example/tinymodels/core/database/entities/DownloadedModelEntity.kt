package com.example.tinymodels.core.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Lightweight model-level metadata. Per-file download state lives in
 * [ModelFileEntity] (child rows keyed by `modelId` + `fileName`).
 *
 * Populated pre-emptively (on first pre-register when the detail page loads).
 */
@Entity(tableName = "downloaded_models")
data class DownloadedModelEntity(
    @PrimaryKey val modelId: String,
    val author: String?,
    val libraryName: String?,
    val pipelineTag: String?,
    val localPath: String,        // directory (set on first pre-register)
    val downloadedAt: Long        // set on first pre-register
)

