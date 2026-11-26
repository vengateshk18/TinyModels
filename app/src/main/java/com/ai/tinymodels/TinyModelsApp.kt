package com.ai.tinymodels

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration as WorkConfiguration
import com.ai.tinymodels.core.common.DownloadNotifier
import com.ai.tinymodels.core.inference.ModelManager
import com.ai.tinymodels.core.network.HuggingFaceAuth
import com.ai.tinymodels.core.network.WebSearchConfig
import com.ai.tinymodels.core.network.WebSearchConfigHolder
import com.ai.tinymodels.domain.repository.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Application entry point. Hilt root + process-wide concerns.
 *
 * Registers [ComponentCallbacks2] so the [ModelManager] can proactively release the
 * (large) LLM engine under memory pressure, and provides a [HiltWorkerFactory] so
 * WorkManager can build @HiltWorker download jobs with injected dependencies.
 */
@HiltAndroidApp
class TinyModelsApp : Application(), WorkConfiguration.Provider {

    @Inject
    lateinit var modelManager: ModelManager

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var huggingFaceAuth: HuggingFaceAuth

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var webSearchConfigHolder: WebSearchConfigHolder

    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {
            modelManager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }

        override fun onTrimMemory(level: Int) {
            modelManager.onTrimMemory(level)
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerComponentCallbacks(memoryCallbacks)
        // Create the download notification channel up-front so it exists before
        // any ModelDownloadWorker runs (channel creation is idempotent).
        DownloadNotifier.createChannel(this)

        // Keep the in-memory HF token in sync with the stored setting so
        // gated-model downloads (Gemma etc.) carry the Bearer header.
        applicationScope.launch {
            settingsRepository.huggingFaceToken.collect { huggingFaceAuth.update(it) }
        }

        // Keep the blocking web_search/open_page path in sync with the
        // persisted provider, key and reader-fallback choice.
        applicationScope.launch {
            settingsRepository.settings.collect { settings ->
                webSearchConfigHolder.config = WebSearchConfig(
                    provider = settings.searchProvider,
                    apiKey = settings.searchApiKey,
                    searxngUrl = settings.searxngUrl,
                    readerFallbackEnabled = settings.webReaderFallback,
                    languageTag = Locale.getDefault().toLanguageTag()
                )
            }
        }
    }

    private val applicationScope = CoroutineScope(Dispatchers.Default)
}
