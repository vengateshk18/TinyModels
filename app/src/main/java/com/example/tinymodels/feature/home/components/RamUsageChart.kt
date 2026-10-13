package com.example.tinymodels.feature.home.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * An animated line chart showing RAM usage over time.
 *
 * @param samples List of available RAM bytes (most recent last).
 * @param totalRam Total RAM in bytes.
 * @param modifier Modifier for the chart container.
 * @param height Chart height (default 120.dp).
 */
@Composable
fun RamUsageChart(
    samples: List<Long>,
    totalRam: Long,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 120.dp
) {
    val density = LocalDensity.current
    val widthPx = remember { mutableStateOf(0f) }
    val heightPx = with(density) { height.roundToPx() }

    // Animate fraction from 0 to 1 over 800ms
    val fraction by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 800, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    )

    // Current usage percentage (based on latest sample)
    val currentUsagePercent = remember(samples, totalRam) {
        if (samples.isNotEmpty() && totalRam > 0) {
            val available = samples.last().coerceIn(0L, totalRam)
            ((totalRam - available).toFloat() / totalRam) * 100f
        } else 0f
    }

    // Convert samples to chart coordinates
    val points = remember(samples, totalRam, widthPx, heightPx) {
        toPoints(samples, totalRam, widthPx.value, heightPx.toFloat())
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { size ->
                widthPx.value = size.width.toFloat()
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width.toFloat()
            val h = size.height.toFloat()

            if (points.size >= 2) {
                // Build the full path
                val fullPath = buildPath(points, w, h)

                // Calculate animated path (partial path based on fraction)
                val animatedPath = Path()
                val pathMeasure = PathMeasure()
                pathMeasure.setPath(fullPath, false)
                val totalLength = pathMeasure.length
                val targetLength = totalLength * fraction

                pathMeasure.getSegment(0f, targetLength, animatedPath, true)

                // Gradient fill under the line (green -> yellow -> red based on usage)
                val fillPath = Path().apply {
                    addPath(animatedPath)
                    lineTo(points.last().x * fraction, h)
                    lineTo(points.first().x, h)
                    close()
                }

                val usageRatio = (currentUsagePercent / 100f).coerceIn(0f, 1f)
                val fillBrush = Brush.linearGradient(
                    colors = listOf(
                        Color.Green.copy(alpha = 0.3f),
                        Color.Yellow.copy(alpha = 0.3f),
                        Color.Red.copy(alpha = 0.3f)
                    ),
                    start = Offset(0f, h * (1f - usageRatio)),
                    end = Offset(0f, h)
                )

                drawPath(
                    path = fillPath,
                    brush = fillBrush
                )

                // Draw the animated line
                drawPath(
                    path = animatedPath,
                    color = when {
                        usageRatio < 0.5f -> Color.Green
                        usageRatio < 0.8f -> Color.Yellow
                        else -> Color.Red
                    },
                    style = Stroke(width = 3f)
                )
            }
        }

        // Percentage overlay (centered)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (samples.isNotEmpty() && totalRam > 0) {
                androidx.compose.material3.Text(
                    text = "%.1f%%".format(currentUsagePercent),
                    style = TextStyle(
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            currentUsagePercent < 50f -> Color.Green
                            currentUsagePercent < 80f -> Color.Yellow
                            else -> Color.Red
                        }
                    )
                )
            } else {
                androidx.compose.material3.Text(
                    text = "—",
                    style = TextStyle(
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                )
            }
        }
    }
}

/**
 * Pure function to convert available-bytes samples into chart coordinates.
 *
 * @param samples List of available RAM bytes.
 * @param totalRam Total RAM in bytes.
 * @param width Chart width in pixels.
 * @param height Chart height in pixels.
 * @return List of [Offset] points where x is evenly distributed across width,
 *         and y maps 0% available (top) to 100% available (bottom).
 */
fun toPoints(
    samples: List<Long>,
    totalRam: Long,
    width: Float,
    height: Float
): List<Offset> {
    if (samples.isEmpty() || width <= 0 || height <= 0 || totalRam <= 0) {
        return emptyList()
    }

    val count = samples.size
    val stepX = if (count > 1) width / (count - 1) else 0f

    return samples.mapIndexed { index, available ->
        // y = 0 at top (0% available / 100% used), y = height at bottom (100% available / 0% used)
        val availableRatio = (available.toFloat() / totalRam).coerceIn(0f, 1f)
        val x = index * stepX
        val y = height * availableRatio
        Offset(x, y)
    }
}

/**
 * Builds a smooth cubic Bézier path through the given points.
 */
private fun buildPath(points: List<Offset>, width: Float, height: Float): Path {
    val path = Path()
    if (points.size < 2) return path

    path.moveTo(points[0].x, points[0].y)

    for (i in 0 until points.size - 1) {
        val p0 = points[i]
        val p1 = points[i + 1]

        // Control points at 1/3 and 2/3 between points for smooth curve
        val cp1x = p0.x + (p1.x - p0.x) / 3f
        val cp1y = p0.y
        val cp2x = p0.x + 2f * (p1.x - p0.x) / 3f
        val cp2y = p1.y

        path.cubicTo(cp1x, cp1y, cp2x, cp2y, p1.x, p1.y)
    }

    return path
}