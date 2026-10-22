package com.ai.tinymodels.data.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.ai.tinymodels.core.common.TinyConstants
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
        if (intent.action != TinyConstants.NOTIFICATION_ACTION_CANCEL_DOWNLOAD) return
        val workId = intent.getStringExtra(TinyConstants.NOTIFICATION_EXTRA_WORK_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return
        WorkManager.getInstance(context).cancelWorkById(workId)
    }
}
