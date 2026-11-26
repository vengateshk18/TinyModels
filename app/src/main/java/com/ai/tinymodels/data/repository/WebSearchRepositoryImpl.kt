package com.ai.tinymodels.data.repository

import com.ai.tinymodels.core.network.WebSearchClient
import com.ai.tinymodels.domain.model.PageContent
import com.ai.tinymodels.domain.model.WebSearchResults
import com.ai.tinymodels.domain.repository.WebSearchRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Thin [WebSearchRepository] implementation over the keyless HTML fallback chain. */
@Singleton
class WebSearchRepositoryImpl @Inject constructor(
    private val client: WebSearchClient
) : WebSearchRepository {

    override suspend fun search(
        query: String,
        maxResults: Int,
        pageBudgetChars: Int,
        pagesToRead: Int
    ): WebSearchResults =
        client.search(query, maxResults, readTopResult = pagesToRead > 0, pageBudgetChars = pageBudgetChars, pagesToRead = pagesToRead)

    override suspend fun openPage(url: String, query: String?, maxChars: Int): PageContent? =
        client.openPage(url, query, maxChars)
}