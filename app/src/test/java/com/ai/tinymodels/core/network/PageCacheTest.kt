package com.ai.tinymodels.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageCacheTest {

    @Test fun `miss before put hit after put`() {
        val cache = PageCache(maxEntries = 4, ttlMillis = 10_000, clock = { 0 })
        assertNull(cache.get("https://a.com"))
        cache.put("https://a.com", "hello")
        assertEquals("hello", cache.get("https://a.com"))
    }

    @Test fun `entry expires after ttl`() {
        var now = 0L
        val cache = PageCache(maxEntries = 4, ttlMillis = 100, clock = { now })
        cache.put("https://a.com", "hello")
        now = 101
        assertNull(cache.get("https://a.com"))
    }

    @Test fun `evicts oldest when over capacity`() {
        val cache = PageCache(maxEntries = 2, ttlMillis = 10_000, clock = { 0 })
        cache.put("https://a.com", "a")
        cache.put("https://b.com", "b")
        cache.put("https://c.com", "c")
        assertNull(cache.get("https://a.com"))
        assertEquals("b", cache.get("https://b.com"))
        assertEquals("c", cache.get("https://c.com"))
    }

    @Test fun `reading refreshes recency for eviction`() {
        val cache = PageCache(maxEntries = 2, ttlMillis = 10_000, clock = { 0 })
        cache.put("https://a.com", "a")
        cache.put("https://b.com", "b")
        assertEquals("a", cache.get("https://a.com")) // a becomes newest
        cache.put("https://c.com", "c")
        assertEquals("a", cache.get("https://a.com"))
        assertNull(cache.get("https://b.com"))
    }

    @Test fun `ignores blank text`() {
        val cache = PageCache(maxEntries = 2, ttlMillis = 10_000, clock = { 0 })
        cache.put("https://a.com", "   ")
        assertNull(cache.get("https://a.com"))
    }
}
