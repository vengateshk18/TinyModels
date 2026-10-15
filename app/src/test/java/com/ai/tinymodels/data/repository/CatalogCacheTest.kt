package com.ai.tinymodels.data.repository

import com.ai.tinymodels.domain.model.CatalogPage
import com.ai.tinymodels.domain.model.ModelSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [CatalogCache] — the tiny in-memory page cache. */
class CatalogCacheTest {

    private fun page(id: String) = CatalogPage(
        models = listOf(
            ModelSummary(
                id = id, modelId = id, author = "a", likes = null, downloads = null,
                tags = emptyList(), libraryName = null, pipelineTag = null,
                lastModified = null, siblings = emptyList()
            )
        ),
        hasMore = false
    )

    @Test
    fun `misses when nothing was stored`() {
        assertNull(CatalogCache().get("nope"))
    }

    @Test
    fun `serves entries until the ttl expires`() {
        var now = 0L
        val cache = CatalogCache(ttlMillis = 1_000, clock = { now })

        val page = page("a/b")
        cache.put("key", page)

        assertEquals(page, cache.get("key"))

        now = 1_000 // exactly at the ttl — still fresh
        assertEquals(page, cache.get("key"))

        now = 1_001 // past the ttl — stale
        assertNull(cache.get("key"))
    }

    @Test
    fun `evicts the least recently used entry beyond capacity`() {
        val cache = CatalogCache(maxEntries = 2)

        cache.put("k1", page("m/1"))
        cache.put("k2", page("m/2"))
        cache.get("k1") // touch k1 so k2 becomes least recently used
        cache.put("k3", page("m/3"))

        assertNull(cache.get("k2"))
        assertEquals(page("m/1"), cache.get("k1"))
        assertEquals(page("m/3"), cache.get("k3"))
    }
}
