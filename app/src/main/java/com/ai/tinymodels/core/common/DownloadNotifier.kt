package com.ai.tinymodels.core.common

import android.Manifest
import android.annotation.SuppressLint
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
import com.ai.tinymodels.R
import com.ai.tinymodels.core.ui.Formatters
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

    /** Creates the (low-importance, silent) download channel. Idempotent no-op if present. */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    TinyConstants.NOTIFICATION_CHANNEL_DOWNLOADS,
                    context.getString(R.string.notification_channel_downloads),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    /** Stable notification id per work, so concurrent downloads don't clobber each other. */
    fun notificationIdFor(workId: UUID): Int = TinyConstants.NOTIFICATION_ID_DOWNLOADS + (workId.hashCode() and 0x0FFF)

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
        return NotificationCompat.Builder(context, TinyConstants.NOTIFICATION_CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("${Formatters.formatBytes(downloaded, context.resources)} / ${Formatters.formatBytes(total, context.resources)}")
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(android.R.string.cancel),
                cancelIntent(context, workId)
            )
            .build()
    }

    /**
     * Post a progress update for [workId] (permission-guarded).
     *
     * Lint-safe: [canNotify] checks POST_NOTIFICATIONS up front and
     * [safeNotify] swallows a mid-flight [SecurityException]; the suppression
     * covers lint's inability to see the guard through the helper.
     */
    @SuppressLint("MissingPermission")
    fun notifyProgress(context: Context, workId: UUID, title: String, downloaded: Long, total: Long) {
        if (!canNotify(context)) return
        safeNotify(context) {
            it.notify(notificationIdFor(workId), progressNotification(context, workId, title, downloaded, total))
        }
    }

    /** Post a "Download complete" notification and dismiss the progress one. */
    @SuppressLint("MissingPermission")
    fun notifyComplete(context: Context, workId: UUID, title: String) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, TinyConstants.NOTIFICATION_CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_download_complete))
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        safeNotify(context) {
            it.cancel(notificationIdFor(workId))
            it.notify(terminalIdFor(workId), notification)
        }
    }

    /** Post a "Download failed" notification and dismiss the progress one. */
    @SuppressLint("MissingPermission")
    fun notifyFailed(context: Context, workId: UUID, title: String, error: String?) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, TinyConstants.NOTIFICATION_CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_download_failed))
            .setContentText(
                if (error.isNullOrBlank()) title
                else context.getString(R.string.notification_failed_with_reason, title, error)
            )
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        safeNotify(context) {
            it.cancel(notificationIdFor(workId))
            it.notify(terminalIdFor(workId), notification)
        }
    }

    /**
     * Runs [block] against [NotificationManagerCompat], swallowing a
     * [SecurityException] if the notification permission was revoked between
     * the [canNotify] check and the post (explicit handling, as lint requires
     * for `MissingPermission`).
     */
    private inline fun safeNotify(context: Context, block: (NotificationManagerCompat) -> Unit) {
        try {
            block(NotificationManagerCompat.from(context))
        } catch (_: SecurityException) {
            // Permission revoked mid-flight — downloads continue silently.
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
        val intent = Intent(TinyConstants.NOTIFICATION_ACTION_CANCEL_DOWNLOAD)
            .setPackage(context.packageName)
            .putExtra(TinyConstants.NOTIFICATION_EXTRA_WORK_ID, workId.toString())
        return PendingIntent.getBroadcast(
            context, workId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
