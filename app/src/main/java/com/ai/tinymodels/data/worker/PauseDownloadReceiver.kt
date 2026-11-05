package com.ai.tinymodels.data.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.ai.tinymodels.core.common.DownloadNotifier
import java.util.UUID

/**
 * Handles the **Pause** action on a download progress notification. Receives the
 * target WorkManager workId and cancels that job — the exact same pause path the
 * in-app Pause button uses, so DB status + partial bytes behave identically.
 *
 * Registered non-exported in the manifest; the action intent is package-scoped.
 */
class PauseDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadNotifier.ACTION_PAUSE) return
        val workId = intent.getStringExtra(DownloadNotifier.EXTRA_WORK_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return
        WorkManager.getInstance(context).cancelWorkById(workId)
    }
}
