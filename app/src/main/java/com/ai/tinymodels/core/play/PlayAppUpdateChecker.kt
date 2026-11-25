package com.ai.tinymodels.core.play

import android.app.Activity
import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayAppUpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context
) : AppUpdateChecker {

    private val manager by lazy { AppUpdateManagerFactory.create(context) }

    override suspend fun getAvailableUpdate(): AppUpdateInfo? {
        return try {
            val info = manager.requestAppUpdateInfo()
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
            ) {
                info
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun startFlexibleUpdate(
        activity: Activity,
        appUpdateInfo: AppUpdateInfo,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ): Boolean {
        return try {
            manager.startUpdateFlowForResult(
                appUpdateInfo,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}
