package com.example.tinymodels.core.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_models")
data class DownloadedModelEntity(
    @PrimaryKey val modelId: String,
    val author: String?,
    val libraryName: String?,
    val pipelineTag: String?,
    val localPath: String,
    val files: String, // newline-separated file names
    val sizeBytes: Long,
    val downloadedAt: Long
)
