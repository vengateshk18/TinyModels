package com.ai.tinymodels.feature.onboarding

/**
 * Illustration shown on an intro page. Kept as an enum so pages cannot
 * reference an art variant that has no drawing.
 */
enum class OnboardingArt { PRIVACY, DOWNLOAD, CHAT, DEVICE, TUNING }

/** One page of the first-run intro. */
data class OnboardingPage(
    val id: String,
    val title: String,
    val body: String,
    val art: OnboardingArt
)

/**
 * The first-run tour. Copy rule: only describe features that actually ship —
 * the website keeps GGUF and extra languages labelled as upcoming, and the
 * intro must not oversell them either.
 */
object OnboardingPages {
    val pages: List<OnboardingPage> = listOf(
        OnboardingPage(
            id = "privacy",
            title = "Private by design",
            body = "Every model runs on your phone. Your chats never leave the " +
                "device — no account, no cloud, no tracking.",
            art = OnboardingArt.PRIVACY
        ),
        OnboardingPage(
            id = "download",
            title = "Download once, chat offline",
            body = "Browse text, vision and code models, download in the " +
                "background over Wi-Fi, and chat from a plane or a dead zone.",
            art = OnboardingArt.DOWNLOAD
        ),
        OnboardingPage(
            id = "chat",
            title = "A chat that feels native",
            body = "Streaming replies, per-chat settings, photo input for " +
                "vision models, and web search as a tool you can switch off.",
            art = OnboardingArt.CHAT
        ),
        OnboardingPage(
            id = "device",
            title = "Right-sized for your device",
            body = "TinyModels reads your RAM and storage, then recommends " +
                "models that actually fit — no guesswork, no 4 GB mistakes.",
            art = OnboardingArt.DEVICE
        ),
        OnboardingPage(
            id = "tuning",
            title = "Measure, tune, make it yours",
            body = "Benchmark real speed and thermals, pick a theme and font, " +
                "and manage every downloaded model in one place.",
            art = OnboardingArt.TUNING
        )
    )
}
