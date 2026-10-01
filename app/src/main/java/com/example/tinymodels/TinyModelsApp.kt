package com.example.tinymodels

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration as WorkConfiguration
import com.example.tinymodels.core.inference.ModelManager
import dagger.hilt.android.HiltAndroidApp
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
    }
}
