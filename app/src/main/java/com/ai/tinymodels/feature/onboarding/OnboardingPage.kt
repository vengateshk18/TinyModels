package com.ai.tinymodels.feature.onboarding

import androidx.annotation.StringRes
import com.ai.tinymodels.R

/**
 * Illustration shown on an intro page. Kept as an enum so pages cannot
 * reference an art variant that has no drawing.
 */
enum class OnboardingArt { PRIVACY, DOWNLOAD, CHAT, PERSONALIZE, READY }

/** One page of the first-run intro. Copy lives in `strings_onboarding.xml`. */
data class OnboardingPage(
    val id: String,
    @param:StringRes @get:StringRes val titleRes: Int,
    @param:StringRes @get:StringRes val bodyRes: Int,
    val art: OnboardingArt
)

/**
 * The first-run tour. Copy rule: only describe features that actually ship.
 */
object OnboardingPages {
    val pages: List<OnboardingPage> = listOf(
        OnboardingPage("privacy", R.string.onboarding_privacy_title, R.string.onboarding_privacy_body, OnboardingArt.PRIVACY),
        OnboardingPage("download", R.string.onboarding_download_title, R.string.onboarding_download_body, OnboardingArt.DOWNLOAD),
        OnboardingPage("chat", R.string.onboarding_chat_title, R.string.onboarding_chat_body, OnboardingArt.CHAT),
        OnboardingPage("personalize", R.string.onboarding_personalize_title, R.string.onboarding_personalize_body, OnboardingArt.PERSONALIZE),
        OnboardingPage("ready", R.string.onboarding_ready_title, R.string.onboarding_ready_body, OnboardingArt.READY)
    )
}
