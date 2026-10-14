package com.ai.tinymodels.feature.home.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.tinymodels.feature.home.DeviceInfoState

/**
 * Maps Android thermal status constant to a human-readable label.
 * See PowerManager thermal status constants.
 */
fun thermalLabel(state: Int): String = when (state) {
    0 -> "Normal"
    1 -> "Light"
    2 -> "Moderate"
    3 -> "Severe"
    4 -> "Critical"
    5 -> "Emergency"
    6 -> "Shutdown"
    else -> "Unknown"
}

/**
 * Maps Android BatteryManager status constant to a human-readable label.
 */
fun batteryStatusLabel(status: Int): String = when (status) {
    1 -> "Unknown"
    2 -> "Charging"
    3 -> "Discharging"
    4 -> "Not charging"
    5 -> "Full"
    else -> "Unknown"
}

/**
 * Clamps battery level to [0, 100]; returns -1 for unknown (-1 input).
 */
fun batteryLevelClamped(level: Int): Int = when {
    level == -1 -> -1
    level < 0 -> 0
    level > 100 -> 100
    else -> level
}

/**
 * Expanded device insights: thermal, battery, GPU, storage, CPU.
 * Renders as a series of info cards.
 */
@Composable
fun DeviceInsights(
    state: DeviceInfoState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(16.dp))
        SectionTitle("Device Insights")

        // Thermal + Battery row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InsightChip(
                icon = Icons.Filled.Thermostat,
                label = "Thermal",
                value = thermalLabel(state.thermalState),
                sub = "State ${state.thermalState}",
                tint = when {
                    state.thermalState >= 4 -> Color(0xFFD32F2F)
                    state.thermalState >= 3 -> Color(0xFFF57C00)
                    state.thermalState >= 2 -> Color(0xFFFBC02D)
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.weight(1f)
            )
            InsightChip(
                icon = if (state.batteryLevel in 0..20) Icons.Filled.Battery0Bar else Icons.Filled.BatteryFull,
                label = "Battery",
                value = if (state.batteryLevel >= 0) "${state.batteryLevel}%" else "Unknown",
                sub = batteryStatusLabel(state.batteryStatus),
                tint = if (state.batteryLevel in 0..15) Color(0xFFD32F2F)
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // GPU card
        if (state.gpuRenderer.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.DeveloperBoard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("GPU Renderer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.gpuRenderer.take(60), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Storage bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Storage", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                val total = state.freeStorageMb + state.usedStorageMb
                val usedPercent = if (total > 0) state.usedStorageMb.toFloat() / total else 0f
                StorageBar(usedPercent)
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Used ${com.ai.tinymodels.core.ui.Formatters.formatMbAsGb(state.usedStorageMb)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Free ${com.ai.tinymodels.core.ui.Formatters.formatMbAsGb(state.freeStorageMb)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // CPU details
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Processor", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                InfoLine("Cores", "${state.cpuCores}")
                InfoLine("Architecture", state.arch)
                InfoLine("64-bit", if (state.arch.contains("arm64") || state.arch.contains("x86_64")) "Yes" else "No")
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun InsightChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    sub: String? = null,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = tint)
                if (sub != null) {
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StorageBar(usedPercent: Float) {
    val clamped = usedPercent.coerceIn(0f, 1f)
    val barColor = when {
        clamped > 0.9f -> androidx.compose.ui.graphics.Color(0xFFD32F2F)
        clamped > 0.75f -> androidx.compose.ui.graphics.Color(0xFFF57C00)
        else -> MaterialTheme.colorScheme.primary
    }
    Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(color = androidx.compose.ui.graphics.Color(0xFFE0E0E0), size = androidx.compose.ui.geometry.Size(w, h))
        drawRoundRect(color = barColor, size = androidx.compose.ui.geometry.Size(w * clamped, h))
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
