package com.example.tinymodels

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.example.tinymodels.core.inference.ModelManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Hilt root + process-wide concerns.
 *
 * Registers [ComponentCallbacks2] so the [ModelManager] can proactively
 * release the (large) LLM engine when the system is under memory pressure.
 */
@HiltAndroidApp
class TinyModelsApp : Application() {

    @Inject
    lateinit var modelManager: ModelManager

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