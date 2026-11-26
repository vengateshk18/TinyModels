package com.ai.tinymodels.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/** Observable web-search progress for the chat UI. */
class SearchProgress {
    private val active = AtomicInteger(0)
    private val _query = MutableStateFlow<String?>(null)
    val query: StateFlow<String?> = _query.asStateFlow()

    suspend fun <T> track(query: String, block: suspend () -> T): T {
        active.incrementAndGet()
        _query.value = query
        return try {
            block()
        } finally {
            if (active.decrementAndGet() == 0) _query.value = null
        }
    }
}
