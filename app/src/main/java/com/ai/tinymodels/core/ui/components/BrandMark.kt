package com.ai.tinymodels.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/**
 * The app mark drawn in code so it always matches the current theme (including
 * dynamic color) and scales crisply at any size: a rounded gradient tile with a
 * "T" monogram and a small sparkle for the "tiny model" idea.
 */
@Composable
fun TinyModelsMark(modifier: Modifier = Modifier.size(64.dp)) {
    val start = MaterialTheme.colorScheme.primary
    val end = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) { drawMark(start, end) }
}

private fun DrawScope.drawMark(start: Color, end: Color) {
    val side = size.minDimension
    val corner = side * 0.30f
    val radius = CornerRadius(corner, corner)

    // Tile with a diagonal gradient.
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(start, end),
            start = Offset.Zero,
            end = Offset(side, side)
        ),
        size = Size(side, side),
        cornerRadius = radius
    )

    // Soft top highlight so the tile reads as a physical chip.
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
            startY = 0f,
            endY = side * 0.62f
        ),
        size = Size(side, side * 0.62f),
        cornerRadius = radius
    )

    // "T" monogram.
    val white = Color.White
    val barWidth = side * 0.44f
    val barHeight = side * 0.125f
    drawRoundRect(
        color = white,
        topLeft = Offset((side - barWidth) / 2f, side * 0.30f),
        size = Size(barWidth, barHeight),
        cornerRadius = CornerRadius(barHeight / 2f, barHeight / 2f)
    )
    val stemWidth = side * 0.125f
    drawRoundRect(
        color = white,
        topLeft = Offset((side - stemWidth) / 2f, side * 0.30f),
        size = Size(stemWidth, side * 0.38f),
        cornerRadius = CornerRadius(stemWidth / 2f, stemWidth / 2f)
    )

    // Four-point sparkle, top-right.
    val cx = side * 0.775f
    val cy = side * 0.225f
    val r = side * 0.075f
    val sparkle = Path().apply {
        moveTo(cx, cy - r)
        quadraticTo(cx + r * 0.18f, cy - r * 0.18f, cx + r, cy)
        quadraticTo(cx + r * 0.18f, cy + r * 0.18f, cx, cy + r)
        quadraticTo(cx - r * 0.18f, cy + r * 0.18f, cx - r, cy)
        quadraticTo(cx - r * 0.18f, cy - r * 0.18f, cx, cy - r)
        close()
    }
    drawPath(sparkle, Color.White.copy(alpha = 0.94f))
}
