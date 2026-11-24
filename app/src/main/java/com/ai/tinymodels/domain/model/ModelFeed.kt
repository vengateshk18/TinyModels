package com.ai.tinymodels.domain.model

import androidx.annotation.StringRes
import com.ai.tinymodels.R

/** Catalog ordering/feed for browsing Hub models. */
enum class ModelFeed(
    @StringRes val labelRes: Int,
    val sort: String
) {
    POPULAR(R.string.models_feed_popular, "downloads"),
    TRENDING(R.string.models_feed_trending, "trendingScore"),
    NEW(R.string.models_feed_new, "createdAt"),
    UPDATED(R.string.models_feed_updated, "lastModified")
}
