package com.ai.tinymodels.data.repository

import com.ai.tinymodels.domain.model.CatalogPage

/**
 * Tiny thread-safe LRU cache for catalog pages, keyed by query+filter+limit.
 * Entries older than [ttlMillis] are treated as misses so long sessions
 * still see fresh data, while tab switches stay instant.
 */
internal class CatalogCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val page: CatalogPage, val storedAt: Long)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: String): CatalogPage? {
        val entry = entries[key] ?: return null
        if (clock() - entry.storedAt > ttlMillis) {
            entries.remove(key)
            return null
        }
        return entry.page
    }

    @Synchronized
    fun put(key: String, page: CatalogPage) {
        entries[key] = Entry(page, clock())
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1_000L
        const val DEFAULT_MAX_ENTRIES = 16
    }
}
