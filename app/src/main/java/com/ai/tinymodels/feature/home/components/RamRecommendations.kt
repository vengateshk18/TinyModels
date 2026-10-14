package com.ai.tinymodels.feature.home.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Pure function: compute actionable recommendations from current RAM state.
 *
 * @param availableRamMb currently available RAM in MB.
 * @param totalRamMb total device RAM in MB.
 * @param thermalState Android thermal status (0 = none, 3 = severe, 4 = critical).
 * @param batteryLevel battery percent (-1 = unknown).
 * @return ordered list of recommendation strings, most urgent first.
 */
fun computeRecommendations(
    availableRamMb: Long,
    totalRamMb: Long,
    thermalState: Int = 0,
    batteryLevel: Int = -1
): List<String> {
    val freePercent = if (totalRamMb > 0) (availableRamMb.toFloat() / totalRamMb) * 100f else 0f
    val recs = mutableListOf<String>()

    when {
        freePercent >= 50f -> {
            recs.add("You can load 3B–7B models comfortably")
            recs.add("Great for long conversations with large context windows")
        }
        freePercent >= 25f -> {
            recs.add("Moderate space — prefer 1B–3B models")
            recs.add("Long chats may need compaction sooner")
        }
        freePercent >= 10f -> {
            recs.add("Limited — only tiny models (<1B) will fit")
            recs.add("Close other apps to free more RAM")
        }
        else -> {
            recs.add("Critical — close other apps before loading any model")
            recs.add("No models can load right now")
        }
    }

    if (thermalState >= 3) {
        recs.add("Device is thermal throttling — performance will be reduced")
    }

    if (batteryLevel in 0..15) {
        recs.add("Battery low — keep charger nearby for large model loads")
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
                "Recommendations",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            recs.forEach { rec ->
                val isWarning = rec.contains("Close", ignoreCase = true) ||
                    rec.contains("Critical", ignoreCase = true) ||
                    rec.contains("throttling", ignoreCase = true) ||
                    rec.contains("Battery low", ignoreCase = true)
                val icon = when {
                    rec.startsWith("Critical") || rec.contains("close other apps", ignoreCase = true)
                        -> Icons.Filled.Warning
                    rec.contains("throttling", ignoreCase = true) -> Icons.Filled.Whatshot
                    rec.contains("Battery low", ignoreCase = true) -> Icons.Filled.BatteryAlert
                    else -> Icons.Filled.CheckCircle
                }
                val tint = if (isWarning) Color(0xFFF57C00)
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
                        text = rec,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
