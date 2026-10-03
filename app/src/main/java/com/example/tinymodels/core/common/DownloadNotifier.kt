package com.example.tinymodels.core.common

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.tinymodels.R
import com.example.tinymodels.core.ui.Formatters
import java.util.UUID

/**
 * Single source of truth for model-download system notifications.
 *
 * Builds the foreground **progress** notification (with a Cancel action) shown
 * while a `ModelDownloadWorker` runs, plus the terminal **complete** / **failed**
 * notifications posted when it finishes. Reuses the app's existing low-importance
 * download channel and launcher-icon resources; byte sizes are formatted with the
 * shared [Formatters] helper, so no new theme/font tokens are introduced.
 *
 * All posting is guarded by the `POST_NOTIFICATIONS` runtime permission on API 33+,
 * so a denial degrades gracefully to "no notification" (today's behavior).
 */
object DownloadNotifier {

    /** Matches the channel already created by `ModelDownloadWorker`. */
    const val CHANNEL_ID = "model_downloads"

    /** Fallback id used by the worker's foreground-service requirement. */
    const val NOTIFICATION_ID = 1001

    /** Extra carrying the WorkManager workId for the Cancel action. */
    const val EXTRA_WORK_ID = "com.example.tinymodels.extra.DOWNLOAD_WORK_ID"

    /** Action broadcast to `CancelDownloadReceiver` when the user taps Cancel. */
    const val ACTION_CANCEL = "com.example.tinymodels.action.CANCEL_DOWNLOAD"

    /** Creates the (low-importance, silent) download channel. Idempotent no-op if present. */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    /** Stable notification id per work, so concurrent downloads don't clobber each other. */
    fun notificationIdFor(workId: UUID): Int = NOTIFICATION_ID + (workId.hashCode() and 0x0FFF)

    /** Distinct id for the terminal (complete/failed) notification of a given work. */
    private fun terminalIdFor(workId: UUID): Int = notificationIdFor(workId) + 0x1000

    /** Determinate, ongoing progress notification with a Cancel action + tap-to-open. */
    fun progressNotification(
        context: Context,
        workId: UUID,
        title: String,
        downloaded: Long,
        total: Long
    ): Notification {
        val percent = if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 100) else 0
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("${Formatters.formatBytes(downloaded)} / ${Formatters.formatBytes(total)}")
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(android.R.string.cancel), cancelIntent(context, workId))
            .build()
    }

    /** Post a progress update for [workId] (permission-guarded). */
    fun notifyProgress(context: Context, workId: UUID, title: String, downloaded: Long, total: Long) {
        if (!canNotify(context)) return
        NotificationManagerCompat.from(context)
            .notify(notificationIdFor(workId), progressNotification(context, workId, title, downloaded, total))
    }

    /** Post a "Download complete" notification and dismiss the progress one. */
    fun notifyComplete(context: Context, workId: UUID, title: String) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Download complete")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        NotificationManagerCompat.from(context).apply {
            cancel(notificationIdFor(workId))
            notify(terminalIdFor(workId), notification)
        }
    }

    /** Post a "Download failed" notification and dismiss the progress one. */
    fun notifyFailed(context: Context, workId: UUID, title: String, error: String?) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Download failed")
            .setContentText(if (error.isNullOrBlank()) title else "$title — $error")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        NotificationManagerCompat.from(context).apply {
            cancel(notificationIdFor(workId))
            notify(terminalIdFor(workId), notification)
        }
    }

    /** Dismiss the progress notification for [workId] (used on cancel, no terminal post). */
    fun cancelProgress(context: Context, workId: UUID) {
        NotificationManagerCompat.from(context).cancel(notificationIdFor(workId))
    }

    /** True when we're allowed to post notifications (always true below API 33). */
    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Tap-to-open intent that brings `MainActivity` to the front. */
    private fun openAppIntent(context: Context): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: Intent()
        return PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Broadcast intent for the Cancel action, carrying the workId. */
    private fun cancelIntent(context: Context, workId: UUID): PendingIntent {
        val intent = Intent(ACTION_CANCEL)
            .setPackage(context.packageName)
            .putExtra(EXTRA_WORK_ID, workId.toString())
        return PendingIntent.getBroadcast(
            context, workId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
