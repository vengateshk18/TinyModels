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
 * The app's own logo (the launcher icon artwork), rendered at [size].
 *
 * Android adaptive icons reserve padding around the artwork so it survives
 * system mask shapes; the artwork itself fills only ~68% of that canvas. The
 * padding is cropped optically here, so callers get the visible logo at
 * exactly [size] instead of a tiny mark floating in empty space.
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
                    val upscale = 1f / LOGO_CONTENT_FRACTION
                    scaleX = upscale
                    scaleY = upscale
                }
        )
    }
}

/** Fraction of the adaptive-icon canvas occupied by the visible artwork. */
private const val LOGO_CONTENT_FRACTION = 0.681f
