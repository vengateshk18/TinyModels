package com.ai.tinymodels.core.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ai.tinymodels.R

/**
 * The app's own logo, presented as a **rounded card** inside a [size] box.
 *
 * The launcher artwork itself is a sharp-edged square plate — shown raw it
 * reads as a clipped image. Three things fix that:
 *  - the artwork is clipped by a rounded card shape ([LOGO_CORNER_FRACTION]),
 *  - the card carries a soft elevation so it sits *on* the surface like a card,
 *  - the artwork is inset to [LOGO_VISIBLE_FRACTION] so the robot has padding
 *    inside the card instead of touching its edges.
 *
 * The card is filled white because the logo plate is white; painting the same
 * colour behind it means the plate's own square edge can never show through.
 */
@Composable
fun TinyModelsLogo(
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val shape = RoundedCornerShape(size * LOGO_CORNER_FRACTION)
    Box(
        modifier = modifier
            .size(size)
            .shadow(
                elevation = (size * LOGO_ELEVATION_FRACTION).coerceAtMost(10.dp),
                shape = shape,
                clip = true
            )
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val upscale = logoUpscale()
                    scaleX = upscale
                    scaleY = upscale
                }
        )
    }
}

/** Fraction of the adaptive-icon canvas occupied by the visible artwork. */
internal const val LOGO_CANVAS_ART_FRACTION = 0.681f

/**
 * Fraction of the logo box the visible artwork should cover. The remaining
 * ~16% (8% per side) is padding inside the card, so the robot never touches
 * the rounded edge.
 */
internal const val LOGO_VISIBLE_FRACTION = 0.84f

/**
 * Corner radius of the card as a fraction of its size (≈ a squircle app icon).
 * Must stay well above zero — the source artwork's plate has square corners,
 * so this radius is the only thing that softens them.
 */
internal const val LOGO_CORNER_FRACTION = 0.24f

/** Card elevation as a fraction of its size, capped so tiny headers stay flat. */
internal const val LOGO_ELEVATION_FRACTION = 0.05f

/**
 * Scale factor that maps the canvas artwork fraction onto
 * [LOGO_VISIBLE_FRACTION]: after scaling, the artwork covers
 * `LOGO_CANVAS_ART_FRACTION * upscale` of the box.
 */
internal fun logoUpscale(): Float = LOGO_VISIBLE_FRACTION / LOGO_CANVAS_ART_FRACTION
