package com.example.tinymodels.data.repository

import com.example.tinymodels.core.network.WebSearchClient
import com.example.tinymodels.domain.model.WebSearchResult
import com.example.tinymodels.domain.repository.WebSearchRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Thin [WebSearchRepository] implementation over the keyless DDG client. */
@Singleton
class WebSearchRepositoryImpl @Inject constructor(
    private val client: WebSearchClient
) : WebSearchRepository {

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> =
        client.search(query, maxResults)
}