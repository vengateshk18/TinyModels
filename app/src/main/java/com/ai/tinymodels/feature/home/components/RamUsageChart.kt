package com.ai.tinymodels.feature.home.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ai.tinymodels.core.ui.Formatters

/**
 * Live RAM monitor card: header with a status pill, an animated area chart
 * with gridlines, and a used/free footer.
 *
 * All colors come from [MaterialTheme.colorScheme] (status levels map to
 * primary / tertiary / error roles) so the card stays readable in both light
 * and dark themes — no hardcoded neon hues.
 *
 * @param samples List of available RAM bytes (most recent last).
 * @param totalRam Total RAM in bytes.
 * @param modifier Modifier for the card.
 * @param height Chart area height (default 140.dp).
 */
@Composable
fun RamUsageChart(
    samples: List<Long>,
    totalRam: Long,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val widthPx = remember { mutableStateOf(0f) }
    val heightPx = remember(height, density) { with(density) { height.roundToPx() } }

    // Draw-in animation on first composition.
    val fraction by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "ramChartDraw"
    )

    val usage = remember(samples, totalRam) { usageStats(samples, totalRam) }

    // Status level drives pill + line colors through theme roles.
    val level = when {
        usage == null -> 0
        usage.usedPercent >= 90f -> 2
        usage.usedPercent >= 75f -> 1
        else -> 0
    }
    val statusColor = when (level) {
        2 -> scheme.error
        1 -> scheme.tertiary
        else -> scheme.primary
    }
    val pillContainer = when (level) {
        2 -> scheme.errorContainer
        1 -> scheme.tertiaryContainer
        else -> scheme.secondaryContainer
    }
    val pillContent = when (level) {
        2 -> scheme.onErrorContainer
        1 -> scheme.onTertiaryContainer
        else -> scheme.onSecondaryContainer
    }

    // Convert samples to chart coordinates.
    val points = remember(samples, totalRam, widthPx, heightPx) {
        toPoints(samples, totalRam, widthPx.value, heightPx.toFloat())
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: title + status pill.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Live RAM usage",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = pillContainer,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(color = statusColor, shape = RoundedCornerShape(999.dp))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (usage != null) "%.2f%% used".format(usage.usedPercent) else "—",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = pillContent
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Chart area.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .onSizeChanged { size -> widthPx.value = size.width.toFloat() }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val gridColor = scheme.onSurfaceVariant.copy(alpha = 0.25f)

                    // Dashed gridlines at 25 / 50 / 75 % height.
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    for (f in listOf(0.25f, 0.5f, 0.75f)) {
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, h * f),
                            end = Offset(w, h * f),
                            strokeWidth = 1f,
                            pathEffect = dash
                        )
                    }

                    if (points.size >= 2) {
                        val fullPath = buildPath(points)
                        val animatedPath = Path()
                        val measure = PathMeasure()
                        measure.setPath(fullPath, false)
                        measure.getSegment(0f, measure.length * fraction, animatedPath, true)

                        // Area fill under the animated line.
                        val fillPath = Path().apply {
                            addPath(animatedPath)
                            lineTo(points.last().x, h)
                            lineTo(points.first().x, h)
                            close()
                        }
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    statusColor.copy(alpha = 0.35f),
                                    statusColor.copy(alpha = 0.04f)
                                )
                            )
                        )
                        // The line itself.
                        drawPath(
                            path = animatedPath,
                            color = statusColor,
                            style = Stroke(width = 3.dp.toPx())
                        )
                        // Latest-sample dot.
                        drawCircle(
                            color = statusColor,
                            radius = 5.dp.toPx(),
                            center = points.last()
                        )
                        drawCircle(
                            color = scheme.surfaceContainer,
                            radius = 2.2.dp.toPx(),
                            center = points.last()
                        )
                    }
                }

                if (points.size < 2) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Collecting samples…",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Footer: used / free / total.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Used ${usage?.usedLabel ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                Text(
                    "Free ${usage?.freeLabel ?: "—"} of ${usage?.totalLabel ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Null when there is nothing to show yet. */
private data class UsageLabels(
    val usedPercent: Float,
    val usedLabel: String,
    val freeLabel: String,
    val totalLabel: String
)

private fun usageStats(samples: List<Long>, totalRam: Long): UsageLabels? {
    if (samples.isEmpty() || totalRam <= 0) return null
    val available = samples.last().coerceIn(0L, totalRam)
    val used = totalRam - available
    return UsageLabels(
        usedPercent = (used.toFloat() / totalRam) * 100f,
        usedLabel = Formatters.formatBytes(used),
        freeLabel = Formatters.formatBytes(available),
        totalLabel = Formatters.formatBytes(totalRam)
    )
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
        // Clamp to [0, 1] — real data should never hit this, but defensive
        val availableRatio = (available.toFloat() / totalRam).coerceIn(0f, 1f)
        val x = index * stepX
        val y = height * availableRatio
        Offset(x, y)
    }
}

/**
 * Builds a smooth cubic Bézier path through the given points.
 */
private fun buildPath(points: List<Offset>): Path {
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
