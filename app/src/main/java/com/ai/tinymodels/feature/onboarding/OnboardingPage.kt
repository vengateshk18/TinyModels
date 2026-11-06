package com.ai.tinymodels.feature.onboarding

import androidx.annotation.StringRes
import com.ai.tinymodels.R

/**
 * Illustration shown on an intro page. Kept as an enum so pages cannot
 * reference an art variant that has no drawing.
 */
enum class OnboardingArt { PRIVACY, DOWNLOAD, CHAT, DEVICE, TUNING }

/** One page of the first-run intro. Copy lives in `strings_onboarding.xml`. */
data class OnboardingPage(
    val id: String,
    @param:StringRes @get:StringRes val titleRes: Int,
    @param:StringRes @get:StringRes val bodyRes: Int,
    val art: OnboardingArt
)

/**
 * The first-run tour. Copy rule: only describe features that actually ship —
 * the website keeps GGUF and extra languages labelled as upcoming, and the
 * intro must not oversell them either.
 */
object OnboardingPages {
    val pages: List<OnboardingPage> = listOf(
        OnboardingPage("privacy", R.string.onboarding_privacy_title, R.string.onboarding_privacy_body, OnboardingArt.PRIVACY),
        OnboardingPage("download", R.string.onboarding_download_title, R.string.onboarding_download_body, OnboardingArt.DOWNLOAD),
        OnboardingPage("chat", R.string.onboarding_chat_title, R.string.onboarding_chat_body, OnboardingArt.CHAT),
        OnboardingPage("device", R.string.onboarding_device_title, R.string.onboarding_device_body, OnboardingArt.DEVICE),
        OnboardingPage("tuning", R.string.onboarding_tuning_title, R.string.onboarding_tuning_body, OnboardingArt.TUNING)
    )
}
