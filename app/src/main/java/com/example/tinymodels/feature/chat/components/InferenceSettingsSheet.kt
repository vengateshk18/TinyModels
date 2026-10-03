package com.example.tinymodels.feature.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.tinymodels.domain.model.BackendPreference
import com.example.tinymodels.domain.model.InferenceSettings
import kotlin.math.roundToInt

/**
 * Modal bottom sheet that lets the user tweak per-session inference parameters.
 * Changes only take effect when the user taps Save — tapping Discard or swiping
 * down reverts to the original [current] settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InferenceSettingsSheet(
    current: InferenceSettings,
    onSave: (InferenceSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Local draft — only applied on Save.
    var temperature by remember { mutableFloatStateOf(current.temperature.toFloat()) }
    var topK by remember { mutableIntStateOf(current.topK) }
    var topP by remember { mutableFloatStateOf(current.topP.toFloat()) }
    var maxContextTokens by remember { mutableIntStateOf(current.maxContextTokens) }
    var backend by remember { mutableStateOf(current.backend) }
    var systemInstruction by remember { mutableStateOf(current.systemInstruction) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxHeight(0.92f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
        ) {
            // Header
            Text(
                text = "Session settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Changes apply to this chat only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Temperature
            SliderSetting(
                label = "Temperature",
                value = temperature,
                valueRange = 0f..2f,
                steps = 39,
                displayValue = "%.2f".format(temperature),
                onValueChange = { temperature = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Top-P
            SliderSetting(
                label = "Top-P",
                value = topP,
                valueRange = 0f..1f,
                steps = 19,
                displayValue = "%.2f".format(topP),
                onValueChange = { topP = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Top-K
            OutlinedTextField(
                value = topK.toString(),
                onValueChange = { raw ->
                    raw.toIntOrNull()?.let { v -> if (v in 1..200) topK = v }
                },
                label = { Text("Top-K", style = MaterialTheme.typography.bodyMedium) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Max context tokens
            ContextTokensDropdown(
                selected = maxContextTokens,
                onSelect = { maxContextTokens = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Backend
            BackendDropdown(
                selected = backend,
                onSelect = { backend = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // System instruction
            OutlinedTextField(
                value = systemInstruction,
                onValueChange = { systemInstruction = it },
                label = { Text("System instruction", style = MaterialTheme.typography.bodyMedium) },
                placeholder = {
                    Text(
                        "You are a helpful assistant.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Discard", style = MaterialTheme.typography.labelLarge)
                }
                Button(
                    onClick = {
                        onSave(
                            InferenceSettings(
                                backend = backend,
                                temperature = temperature.toDouble(),
                                topK = topK,
                                topP = topP.toDouble(),
                                maxContextTokens = maxContextTokens,
                                systemInstruction = systemInstruction.trim()
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Save", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    displayValue: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = displayValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextTokensDropdown(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val options = listOf(512, 1024, 2048, 4096)
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = "$selected tokens",
            onValueChange = {},
            readOnly = true,
            label = { Text("Max context tokens", style = MaterialTheme.typography.bodyMedium) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { tokens ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "$tokens tokens",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        onSelect(tokens)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackendDropdown(
    selected: BackendPreference,
    onSelect: (BackendPreference) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = BackendPreference.entries

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selected.name,
            onValueChange = {},
            readOnly = true,
            label = { Text("Backend", style = MaterialTheme.typography.bodyMedium) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { pref ->
                DropdownMenuItem(
                    text = {
                        Text(pref.name, style = MaterialTheme.typography.bodyMedium)
                    },
                    onClick = {
                        onSelect(pref)
                        expanded = false
                    }
                )
            }
        }
    }
}
