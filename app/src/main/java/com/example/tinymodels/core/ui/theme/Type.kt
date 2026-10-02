package com.example.tinymodels.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.example.tinymodels.R
import com.example.tinymodels.domain.model.FontChoice

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

/** Build a [FontFamily] for the given [FontChoice]. SYSTEM returns [FontFamily.Default]. */
fun fontFamilyFor(choice: FontChoice): FontFamily {
    val name = choice.googleFontName ?: return FontFamily.Default
    return FontFamily(
        Font(
            googleFont = GoogleFont(name),
            fontProvider = provider,
        )
    )
}

/**
 * Build a [Typography] using the font family for [choice]. Display styles get the
 * chosen font; body/label styles also get it (most Material Theme Builder exports
 * use a single family for everything).
 */
fun buildTypography(choice: FontChoice): Typography {
    val family = fontFamilyFor(choice)
    val baseline = Typography()
    return Typography(
        displayLarge = baseline.displayLarge.copy(fontFamily = family),
        displayMedium = baseline.displayMedium.copy(fontFamily = family),
        displaySmall = baseline.displaySmall.copy(fontFamily = family),
        headlineLarge = baseline.headlineLarge.copy(fontFamily = family),
        headlineMedium = baseline.headlineMedium.copy(fontFamily = family),
        headlineSmall = baseline.headlineSmall.copy(fontFamily = family),
        titleLarge = baseline.titleLarge.copy(fontFamily = family),
        titleMedium = baseline.titleMedium.copy(fontFamily = family),
        titleSmall = baseline.titleSmall.copy(fontFamily = family),
        bodyLarge = baseline.bodyLarge.copy(fontFamily = family),
        bodyMedium = baseline.bodyMedium.copy(fontFamily = family),
        bodySmall = baseline.bodySmall.copy(fontFamily = family),
        labelLarge = baseline.labelLarge.copy(fontFamily = family),
        labelMedium = baseline.labelMedium.copy(fontFamily = family),
        labelSmall = baseline.labelSmall.copy(fontFamily = family),
    )
}

/** Default typography (System font) — used as a fallback before settings load. */
val AppTypography = buildTypography(FontChoice.SYSTEM)
