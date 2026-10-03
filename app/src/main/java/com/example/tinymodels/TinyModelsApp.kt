package com.example.tinymodels

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration as WorkConfiguration
import com.example.tinymodels.core.common.DownloadNotifier
import com.example.tinymodels.core.inference.ModelManager
import com.example.tinymodels.core.network.HuggingFaceAuth
import com.example.tinymodels.domain.repository.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    }

    private val applicationScope = CoroutineScope(Dispatchers.Default)
}
