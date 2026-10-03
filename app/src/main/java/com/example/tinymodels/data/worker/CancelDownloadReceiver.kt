package com.example.tinymodels.data.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.example.tinymodels.core.common.DownloadNotifier
import java.util.UUID

/**
 * Handles the **Cancel** action on a download progress notification. Receives the
 * target WorkManager workId and cancels that job — the exact same cancel path the
 * in-app Cancel button uses, so DB status + disk cleanup behave identically.
 *
 * Registered non-exported in the manifest; the action intent is package-scoped.
 */
class CancelDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadNotifier.ACTION_CANCEL) return
        val workId = intent.getStringExtra(DownloadNotifier.EXTRA_WORK_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return
        WorkManager.getInstance(context).cancelWorkById(workId)
    }
}
