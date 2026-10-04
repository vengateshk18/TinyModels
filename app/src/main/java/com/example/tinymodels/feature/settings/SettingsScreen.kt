package com.example.tinymodels.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
    onDeviceInfo: () -> Unit,
    onBenchmark: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val clearResult by viewModel.clearResult.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearDialog by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(clearResult) {
        clearResult?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeClearResult()
        }
    }

    // Tab screen — outer Scaffold already consumed the bottom nav-bar inset.
    // Only Top + Horizontal needed.
    val safeTopHorizontal = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

    Scaffold(
        contentWindowInsets = safeTopHorizontal,
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
            HuggingFaceSection(viewModel)
            SectionDivider()
            AboutSection(
                onBenchmark = onBenchmark,
                onDeviceInfo = onDeviceInfo,
                onClearHistory = { showClearDialog = true }
            )
            // 24dp breathing room after the last item so content is never
            // clipped at the scroll end by the navigation bar.
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear chat history") },
            text = { Text("Delete all chat conversations? This cannot be undone.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.clearChatHistory()
                    showClearDialog = false
                }) { Text("Delete") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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
    var showFontSheet by remember { mutableStateOf(false) }
    FontRow(
        selected = settings.fontChoice,
        onClick = { showFontSheet = true }
    )
    if (showFontSheet) {
        FontBottomSheet(
            selected = settings.fontChoice,
            onSelect = {
                viewModel.setFontChoice(it)
                showFontSheet = false
            },
            onDismiss = { showFontSheet = false }
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    SliderRow(
        label = "Font size",
        valueText = "%.2f×".format(settings.fontScale),
        value = settings.fontScale,
        valueRange = 0.85f..1.30f,
        onValueChange = { viewModel.setFontScale(it) }
    )
}

@Composable
private fun InferenceSection(settings: AppSettings, viewModel: SettingsViewModel) {
    SectionHeader("Inference")
    Text("Default backend", style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(8.dp))
    val backends = listOf(BackendPreference.AUTO, BackendPreference.GPU, BackendPreference.CPU)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        backends.forEachIndexed { index, backend ->
            SegmentedButton(
                selected = settings.defaultBackend == backend,
                onClick = { viewModel.setDefaultBackend(backend) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = backends.size)
            ) {
                Text(backend.name.lowercase().replaceFirstChar { it.uppercase() })
            }
        }
    }
}

@Composable
private fun HuggingFaceSection(viewModel: SettingsViewModel) {
    val token by viewModel.huggingFaceToken.collectAsStateWithLifecycle()
    var showTokenDialog by remember { mutableStateOf(false) }

    SectionHeader("Hugging Face")
    NavigationRow(
        label = "Access token",
        subtitle = if (token.isNullOrBlank()) "Not set — optional; only gated models need one"
        else "Set — ${token!!.take(6)}••••••••${token!!.takeLast(4)}",
        onClick = { showTokenDialog = true }
    )
    Text(
        "Gated models (e.g. Gemma) need a free access token to download. " +
            "Create one at huggingface.co/settings/tokens, then accept the model's license on its page.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (showTokenDialog) {
        TokenDialog(
            current = token,
            onSave = {
                viewModel.setHuggingFaceToken(it)
                showTokenDialog = false
            },
            onRemove = {
                viewModel.setHuggingFaceToken(null)
                showTokenDialog = false
            },
            onDismiss = { showTokenDialog = false }
        )
    }
}

@Composable
private fun TokenDialog(
    current: String?,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(current) { mutableStateOf(current ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hugging Face access token") },
        text = {
            Column {
                Text(
                    "Paste a token with read access. Create one at huggingface.co/settings/tokens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("hf_xxxxxxxxxxxxxxxx") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onSave(text) },
                enabled = text.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    androidx.compose.material3.TextButton(onClick = onRemove) { Text("Remove") }
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun AboutSection(
    onBenchmark: () -> Unit,
    onDeviceInfo: () -> Unit,
    onClearHistory: () -> Unit
) {
    SectionHeader("About")
    NavigationRow(
        label = "Benchmark",
        subtitle = "Measure load time, TTFT, and tokens/sec",
        onClick = onBenchmark
    )
    NavigationRow(
        label = "Device information",
        subtitle = "See your device's AI capability",
        onClick = onDeviceInfo
    )
    NavigationRow(
        label = "Clear chat history",
        subtitle = "Delete all conversations",
        onClick = onClearHistory
    )
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
private fun NavigationRow(label: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FontRow(
    selected: FontChoice,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Font", style = MaterialTheme.typography.bodyMedium)
            Text(
                selected.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (selected == FontChoice.SYSTEM) FontFamily.Default
                    else fontFamilyFor(selected)
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Change font",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontBottomSheet(
    selected: FontChoice,
    onSelect: (FontChoice) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Suppress the sheet's built-in inset handling so we control it precisely.
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .padding(bottom = 16.dp)
        ) {
            Text(
                "Choose font",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            FontChoice.entries.forEach { choice ->
                val isSelected = choice == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(choice) }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
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
}
