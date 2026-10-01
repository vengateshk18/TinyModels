package com.example.tinymodels.models.data

data class ModelDetails(
    val id: String,
    val author: String?,
    val pipelineTag: String?,
    val libraryName: String?,
    val tags: List<String>,
    val downloads: Long?,
    val likes: Int?,
    val sha: String?,
    val lastModified: String?,
    val gated: String?,
    val disabled: Boolean,
    val widgetPrompts: List<String>,
    val baseModel: String?,
    val createdAt: String?,
    val siblings: List<String>,
    val spaces: List<String>,
    val usedStorage: Long?
) {
    val liteRtFiles: List<String>
        get() = siblings.filter { it.endsWith(".litertlm", ignoreCase = true) }
}
