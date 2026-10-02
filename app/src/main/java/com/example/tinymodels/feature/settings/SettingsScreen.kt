package com.example.tinymodels.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tinymodels.domain.model.AppSettings
import com.example.tinymodels.domain.model.BackendPreference
import com.example.tinymodels.domain.model.FontChoice
import com.example.tinymodels.domain.model.ThemeMode
import com.example.tinymodels.core.ui.theme.fontFamilyFor
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageModels: () -> Unit,
    onDownloadedModels: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            AppearanceSection(settings, viewModel)
            SectionDivider()
            InferenceSection(settings, viewModel)
            SectionDivider()
            ManagementSection(onManageModels, onDownloadedModels)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AppearanceSection(settings: AppSettings, viewModel: SettingsViewModel) {
    SectionHeader("Appearance")
    Text("Theme", style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(8.dp))
    val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = settings.themeMode == mode,
                onClick = { viewModel.setThemeMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
            ) {
                Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
            }
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    SwitchRow(
        label = "Dynamic color",
        subtitle = "Use wallpaper-based colors (Android 12+)",
        checked = settings.useDynamicColor,
        onCheckedChange = { viewModel.setDynamicColor(it) }
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text("Font", style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(8.dp))
    FontPicker(
        selected = settings.fontChoice,
        onSelect = { viewModel.setFontChoice(it) }
    )
}

@Composable
private fun InferenceSection(settings: AppSettings, viewModel: SettingsViewModel) {
    SectionHeader("Inference")

    Text("Backend", style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(8.dp))
    val backends = listOf(BackendPreference.AUTO, BackendPreference.GPU, BackendPreference.CPU)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        backends.forEachIndexed { index, backend ->
            SegmentedButton(
                selected = settings.backend == backend,
                onClick = { viewModel.setBackend(backend) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = backends.size)
            ) { Text(backend.name) }
        }
    }

    SliderRow(
        label = "Temperature",
        valueText = "%.2f".format(settings.sampler.temperature),
        value = settings.sampler.temperature.toFloat(),
        valueRange = 0f..1.5f,
        onValueChange = { viewModel.setTemperature(it.toDouble()) }
    )
    SliderRow(
        label = "Top K",
        valueText = "${settings.sampler.topK}",
        value = settings.sampler.topK.toFloat(),
        valueRange = 1f..100f,
        onValueChange = { viewModel.setTopK(it.roundToInt()) }
    )
    SliderRow(
        label = "Top P",
        valueText = "%.2f".format(settings.sampler.topP),
        value = settings.sampler.topP.toFloat(),
        valueRange = 0f..1f,
        onValueChange = { viewModel.setTopP(it.toDouble()) }
    )

    Spacer(modifier = Modifier.height(8.dp))
    Text("Context window (max tokens)", style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(8.dp))
    val tokenOptions = listOf(1024, 2048, 4096, 8192)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        tokenOptions.forEachIndexed { index, tokens ->
            SegmentedButton(
                selected = settings.maxContextTokens == tokens,
                onClick = { viewModel.setMaxContextTokens(tokens) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = tokenOptions.size)
            ) { Text("${tokens / 1024}K") }
        }
    }
}

@Composable
private fun ManagementSection(onManageModels: () -> Unit, onDownloadedModels: () -> Unit) {
    SectionHeader("Models")
    NavigationRow(label = "Browse models", onClick = onManageModels)
    NavigationRow(label = "Downloaded models", onClick = onDownloadedModels)
}

// ---- Reusable rows ----

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
private fun SwitchRow(label: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

@Composable
private fun NavigationRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FontPicker(
    selected: FontChoice,
    onSelect: (FontChoice) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FontChoice.entries.forEach { choice ->
            val isSelected = choice == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(choice) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = { onSelect(choice) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = choice.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = if (choice == FontChoice.SYSTEM) FontFamily.Default
                        else fontFamilyFor(choice),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (choice == FontChoice.SYSTEM) "Default" else "Google Font",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
