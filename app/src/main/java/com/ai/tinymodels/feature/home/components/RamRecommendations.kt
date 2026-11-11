package com.ai.tinymodels.feature.home.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.tinymodels.R

/**
 * Icon shown next to a recommendation. Kept as data (not derived from the
 * recommendation text) so translations can never break the mapping.
 */
enum class RamRecommendationIcon { INFO, WARNING, THERMAL, BATTERY }

/**
 * A single actionable recommendation: a localized string resource plus the
 * icon/severity used to render it.
 */
data class RamRecommendation(
    @StringRes val textRes: Int,
    val icon: RamRecommendationIcon = RamRecommendationIcon.INFO,
    val isWarning: Boolean = false
)

/**
 * Pure function: compute actionable recommendations from current RAM state.
 *
 * @param availableRamMb currently available RAM in MB.
 * @param totalRamMb total device RAM in MB.
 * @param thermalState Android thermal status (0 = none, 3 = severe, 4 = critical).
 * @param batteryLevel battery percent (-1 = unknown).
 * @return ordered list of recommendations, most urgent first.
 */
fun computeRecommendations(
    availableRamMb: Long,
    totalRamMb: Long,
    thermalState: Int = 0,
    batteryLevel: Int = -1
): List<RamRecommendation> {
    val freePercent = if (totalRamMb > 0) (availableRamMb.toFloat() / totalRamMb) * 100f else 0f
    val recs = mutableListOf<RamRecommendation>()

    when {
        freePercent >= 50f -> {
            recs.add(RamRecommendation(R.string.ram_rec_plenty_capacity))
            recs.add(RamRecommendation(R.string.ram_rec_plenty_context))
        }
        freePercent >= 25f -> {
            recs.add(RamRecommendation(R.string.ram_rec_moderate_capacity))
            recs.add(RamRecommendation(R.string.ram_rec_moderate_compaction))
        }
        freePercent >= 10f -> {
            recs.add(RamRecommendation(R.string.ram_rec_limited_capacity))
            recs.add(
                RamRecommendation(
                    R.string.ram_rec_limited_close_apps,
                    icon = RamRecommendationIcon.WARNING,
                    isWarning = true
                )
            )
        }
        else -> {
            recs.add(
                RamRecommendation(
                    R.string.ram_rec_critical_close_apps,
                    icon = RamRecommendationIcon.WARNING,
                    isWarning = true
                )
            )
            recs.add(RamRecommendation(R.string.ram_rec_critical_no_models))
        }
    }

    if (thermalState >= 3) {
        recs.add(
            RamRecommendation(
                R.string.ram_rec_thermal_throttling,
                icon = RamRecommendationIcon.THERMAL,
                isWarning = true
            )
        )
    }

    if (batteryLevel in 0..15) {
        recs.add(
            RamRecommendation(
                R.string.ram_rec_battery_low,
                icon = RamRecommendationIcon.BATTERY,
                isWarning = true
            )
        )
    }

    return recs
}

/**
 * Renders RAM recommendations as an actionable card with icon-coded entries.
 */
@Composable
fun RamRecommendations(
    availableRamMb: Long,
    totalRamMb: Long,
    thermalState: Int = 0,
    batteryLevel: Int = -1,
    modifier: Modifier = Modifier
) {
    val recs = computeRecommendations(availableRamMb, totalRamMb, thermalState, batteryLevel)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.ram_recommendations_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            recs.forEach { rec ->
                val icon: ImageVector = when (rec.icon) {
                    RamRecommendationIcon.INFO -> Icons.Filled.CheckCircle
                    RamRecommendationIcon.WARNING -> Icons.Filled.Warning
                    RamRecommendationIcon.THERMAL -> Icons.Filled.Whatshot
                    RamRecommendationIcon.BATTERY -> Icons.Filled.BatteryAlert
                }
                val tint = if (rec.isWarning) Color(0xFFF57C00)
                else MaterialTheme.colorScheme.primary

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(rec.textRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
