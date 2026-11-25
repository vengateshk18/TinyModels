package com.ai.tinymodels.domain.model

/** A curated Hugging Face collection shown in Browse for easy discovery. */
data class ModelCollection(
    val slug: String,
    val title: String,
    val description: String,
    val owner: String,
    val avatarUrl: String? = null,
    val itemCount: Int = 0,
    val modelIds: List<String> = emptyList()
)

/** Collection metadata plus its resolved, browsable model items. */
data class ModelCollectionDetail(
    val collection: ModelCollection,
    val models: List<ModelSummary>
)

