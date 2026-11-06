package com.ai.tinymodels.core.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.ai.tinymodels.R

/**
 * The app's own logo (the launcher icon artwork), rendered inside a [size] box.
 *
 * Two geometry facts shape the rendering:
 *  - Android adaptive icons reserve padding around the artwork so it survives
 *    system mask shapes; the artwork fills only ~68% of that canvas.
 *  - The logo's tile edge should never sit flush against the box edge — a tile
 *    pressed against the bounds reads as clipped and harsh.
 *
 * So the canvas is upscaled only until the artwork fills [LOGO_VISIBLE_FRACTION]
 * of the box, leaving a deliberate margin on every side.
 */
@Composable
fun TinyModelsLogo(
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RectangleShape),
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
 * ~16% (8% per side) is breathing room, so the tile's rounded edge is fully
 * visible instead of being cropped by the box bounds.
 */
internal const val LOGO_VISIBLE_FRACTION = 0.84f

/**
 * Scale factor that maps the canvas artwork fraction onto
 * [LOGO_VISIBLE_FRACTION]: after scaling, the artwork covers
 * `LOGO_CANVAS_ART_FRACTION * upscale` of the box.
 */
internal fun logoUpscale(): Float = LOGO_VISIBLE_FRACTION / LOGO_CANVAS_ART_FRACTION
