package com.ai.tinymodels.core.play

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.play.core.review.ReviewManagerFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

@Singleton
class InAppReviewManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Records one successful chat completion and returns true when review should be requested. */
    fun recordSuccessfulChatAndShouldPrompt(): Boolean {
        val completions = prefs.getInt(KEY_COMPLETIONS, 0) + 1
        val promptedAt = prefs.getLong(KEY_PROMPTED_AT, 0L)
        prefs.edit().putInt(KEY_COMPLETIONS, completions).apply()

        val now = System.currentTimeMillis()
        val cooldownPassed = promptedAt == 0L || (now - promptedAt) >= COOLDOWN_MS
        return completions >= MIN_COMPLETIONS && completions % PROMPT_EVERY == 0 && cooldownPassed
    }

    fun markPrompted() {
        prefs.edit().putLong(KEY_PROMPTED_AT, System.currentTimeMillis()).apply()
    }

    suspend fun launchReview(activity: Activity) {
        try {
            val manager = ReviewManagerFactory.create(activity)
            val reviewInfo = manager.requestReviewFlow().await()
            manager.launchReviewFlow(activity, reviewInfo).await()
            markPrompted()
        } catch (_: Exception) {
            openPlayStoreListing(activity)
        }
    }

    fun openPlayStoreListing(activity: Activity) {
        val uri = Uri.parse("https://play.google.com/store/apps/details?id=${activity.packageName}")
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            // ignore
        }
    }

    private companion object {
        const val PREFS = "in_app_review"
        const val KEY_COMPLETIONS = "successful_chat_completions"
        const val KEY_PROMPTED_AT = "prompted_at_ms"
        const val MIN_COMPLETIONS = 3
        const val PROMPT_EVERY = 8
        const val COOLDOWN_MS = 90L * 24 * 60 * 60 * 1000
    }
}
