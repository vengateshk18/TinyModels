package com.ai.tinymodels.core.play

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo

interface AppUpdateChecker {
    /** Returns Play update info when a newer version is available, else null. */
    suspend fun getAvailableUpdate(): AppUpdateInfo?

    /** Starts the Play flexible update flow. Returns true if flow launched. */
    fun startFlexibleUpdate(
        activity: Activity,
        appUpdateInfo: AppUpdateInfo,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ): Boolean
}
