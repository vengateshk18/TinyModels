package com.ai.tinymodels.feature.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object WidgetUpdater {
    fun requestUpdate(context: Context) {
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { TinyModelsWidget().updateAll(context.applicationContext) }
        }
    }
}
