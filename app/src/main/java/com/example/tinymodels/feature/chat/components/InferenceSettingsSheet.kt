package com.example.tinymodels.feature.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.tinymodels.domain.model.BackendPreference
import com.example.tinymodels.domain.model.InferenceSettings
import kotlin.math.roundToInt

/**
 * Modal bottom sheet for editing the per-session inference settings:
 * backend, temperature, top-K, top-P, context window, system instruction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InferenceSettingsSheet(
    current: InferenceSettings,
    onSave: (InferenceSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var settings by remember { mutableStateOf(current) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "Inference settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Backend
            Text("Backend", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            val backends = listOf(BackendPreference.AUTO, BackendPreference.GPU, BackendPreference.CPU)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                backends.forEachIndexed { index, backend ->
                    SegmentedButton(
                        selected = settings.backend == backend,
                        onClick = { settings = settings.copy(backend = backend) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = backends.size)
                    ) { Text(backend.name) }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Temperature
            SliderRow(
                label = "Temperature",
                valueText = "%.2f".format(settings.temperature),
                value = settings.temperature.toFloat(),
                range = 0f..1.5f,
                onValueChange = { settings = settings.copy(temperature = it.toDouble()) }
            )

            // Top K
            SliderRow(
                label = "Top K",
                valueText = "${settings.topK}",
                value = settings.topK.toFloat(),
                range = 1f..100f,
                onValueChange = { settings = settings.copy(topK = it.roundToInt()) }
            )

            // Top P
            SliderRow(
                label = "Top P",
                valueText = "%.2f".format(settings.topP),
                value = settings.topP.toFloat(),
                range = 0f..1f,
                onValueChange = { settings = settings.copy(topP = it.toDouble()) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Context window
            Text("Context window (max tokens)", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            val tokenOptions = listOf(1024, 2048, 4096, 8192)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                tokenOptions.forEachIndexed { index, tokens ->
                    SegmentedButton(
                        selected = settings.maxContextTokens == tokens,
                        onClick = { settings = settings.copy(maxContextTokens = tokens) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = tokenOptions.size)
                    ) { Text("${tokens / 1024}K") }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // System instruction
            Text("System instruction", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.systemInstruction,
                onValueChange = { settings = settings.copy(systemInstruction = it) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Save button
            Button(
                onClick = { onSave(settings) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}
