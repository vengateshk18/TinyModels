package com.example.tinymodels.data.repository

import com.example.tinymodels.core.network.WebSearchClient
import com.example.tinymodels.domain.model.WebSearchResults
import com.example.tinymodels.domain.repository.WebSearchRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Thin [WebSearchRepository] implementation over the keyless HTML fallback chain. */
@Singleton
class WebSearchRepositoryImpl @Inject constructor(
    private val client: WebSearchClient
) : WebSearchRepository {

    override suspend fun search(query: String, maxResults: Int): WebSearchResults =
        client.search(query, maxResults)
}