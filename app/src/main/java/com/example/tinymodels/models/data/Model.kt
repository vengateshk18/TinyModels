package com.example.tinymodels.models.data

data class Model(
    val id: String,
    val modelId: String,
    val author: String,
    val likes: Int?,
    val downloads: Long?,
    val tags: List<String>?,
    val libraryName: String?,
    val pipeLineTag: String?,
    val _id: String?,
    val gated: Boolean?,
    val lastModified: String?,
    val private: Boolean?,
    val sha: String?,
    val createdAt: String?,
    val siblings: List<String>?
)
