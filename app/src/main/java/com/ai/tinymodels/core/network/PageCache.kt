package com.ai.tinymodels.core.network

import java.util.LinkedHashMap

/**
 * Small in-memory page-text cache. Search and `open_page` share it so the
 * same URL is not fetched twice in one session. Thread-safe: search races
 * engines concurrently and the tool runs on a blocking executor.
 */
class PageCache(
    private val maxEntries: Int = 24,
    private val ttlMillis: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val text: String, val storedAt: Long)

    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(url: String): String? {
        val entry = map[url] ?: return null
        if (clock() - entry.storedAt > ttlMillis) {
            map.remove(url)
            return null
        }
        return entry.text
    }

    @Synchronized
    fun put(url: String, text: String) {
        if (text.isBlank()) return
        map[url] = Entry(text = text, storedAt = clock())
    }

    @Synchronized
    fun clear() = map.clear()
}
