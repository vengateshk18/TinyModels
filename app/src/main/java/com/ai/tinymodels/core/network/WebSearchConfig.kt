package com.ai.tinymodels.core.network

import com.ai.tinymodels.domain.model.SearchProvider
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime search settings, pushed from DataStore at app start. Kept in a
 * holder (like [HuggingFaceAuth]) so the blocking `web_search` tool thread
 * can read the current provider without suspending on DataStore.
 */
data class WebSearchConfig(
    val provider: SearchProvider = SearchProvider.KEYLESS,
    val apiKey: String = "",
    val searxngUrl: String = "",
    /** Send unreadable pages to r.jina.ai. Off by default: it shares the URL. */
    val readerFallbackEnabled: Boolean = false,
    val languageTag: String = Locale.getDefault().toLanguageTag()
) {
    /** True when this provider has everything it needs; otherwise use keyless. */
    fun providerReady(): Boolean = when (provider) {
        SearchProvider.KEYLESS -> true
        SearchProvider.BRAVE, SearchProvider.TAVILY -> apiKey.isNotBlank()
        SearchProvider.SEARXNG -> searxngUrl.trim().let {
            it.startsWith("http://") || it.startsWith("https://")
        }
    }
}

@Singleton
class WebSearchConfigHolder @Inject constructor() {
    @Volatile
    var config: WebSearchConfig = WebSearchConfig()
}
