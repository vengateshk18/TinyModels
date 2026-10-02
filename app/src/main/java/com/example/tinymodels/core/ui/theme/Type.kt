package com.example.tinymodels.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
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
 *
 * [fontScale] multiplies every [fontSize] — e.g. 1.0 = default, 1.2 = 20% larger.
 */
fun buildTypography(choice: FontChoice, fontScale: Float = 1.0f): Typography {
    val family = fontFamilyFor(choice)
    val baseline = Typography()
    val s = { size: androidx.compose.ui.unit.TextUnit -> (size.value * fontScale).sp }
    return Typography(
        displayLarge = baseline.displayLarge.copy(fontFamily = family, fontSize = s(baseline.displayLarge.fontSize)),
        displayMedium = baseline.displayMedium.copy(fontFamily = family, fontSize = s(baseline.displayMedium.fontSize)),
        displaySmall = baseline.displaySmall.copy(fontFamily = family, fontSize = s(baseline.displaySmall.fontSize)),
        headlineLarge = baseline.headlineLarge.copy(fontFamily = family, fontSize = s(baseline.headlineLarge.fontSize)),
        headlineMedium = baseline.headlineMedium.copy(fontFamily = family, fontSize = s(baseline.headlineMedium.fontSize)),
        headlineSmall = baseline.headlineSmall.copy(fontFamily = family, fontSize = s(baseline.headlineSmall.fontSize)),
        titleLarge = baseline.titleLarge.copy(fontFamily = family, fontSize = s(baseline.titleLarge.fontSize)),
        titleMedium = baseline.titleMedium.copy(fontFamily = family, fontSize = s(baseline.titleMedium.fontSize)),
        titleSmall = baseline.titleSmall.copy(fontFamily = family, fontSize = s(baseline.titleSmall.fontSize)),
        bodyLarge = baseline.bodyLarge.copy(fontFamily = family, fontSize = s(baseline.bodyLarge.fontSize)),
        bodyMedium = baseline.bodyMedium.copy(fontFamily = family, fontSize = s(baseline.bodyMedium.fontSize)),
        bodySmall = baseline.bodySmall.copy(fontFamily = family, fontSize = s(baseline.bodySmall.fontSize)),
        labelLarge = baseline.labelLarge.copy(fontFamily = family, fontSize = s(baseline.labelLarge.fontSize)),
        labelMedium = baseline.labelMedium.copy(fontFamily = family, fontSize = s(baseline.labelMedium.fontSize)),
        labelSmall = baseline.labelSmall.copy(fontFamily = family, fontSize = s(baseline.labelSmall.fontSize)),
    )
}

/** Default typography (System font) — used as a fallback before settings load. */
val AppTypography = buildTypography(FontChoice.SYSTEM)
