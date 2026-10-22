package com.ai.tinymodels.feature.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.tinymodels.core.common.AppLocale
import com.ai.tinymodels.R
import com.ai.tinymodels.core.common.TinyConstants
import com.ai.tinymodels.domain.model.AppLanguage
import com.ai.tinymodels.domain.model.AppSettings
import com.ai.tinymodels.domain.model.BackendPreference
import com.ai.tinymodels.domain.model.FontChoice
import com.ai.tinymodels.domain.model.ThemeMode
import com.ai.tinymodels.core.ui.components.bottomSheetTopSafePadding
import com.ai.tinymodels.core.ui.theme.fontFamilyFor
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

    // Model import launcher (SAF file picker).
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.importModel(it)
        }
    }

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
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
            DownloadsSection(settings, viewModel)
            SectionDivider()
            ChatSection(settings, viewModel)
            SectionDivider()
            HuggingFaceSection(viewModel)
            SectionDivider()
            ModelManagementSection(
                onImportClick = { importLauncher.launch(arrayOf(TinyConstants.MIME_TYPE_ANY)) }
            )
            SectionDivider()
            AboutSection(
                onBenchmark = onBenchmark,
                onDeviceInfo = onDeviceInfo,
                onClearHistory = { showClearDialog = true }
            )
            SectionDivider()
            SupportSection()
            // 24dp breathing room after the last item so content is never
            // clipped at the scroll end by the navigation bar.
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.settings_clear_chat_history_title)) },
            text = { Text(stringResource(R.string.settings_clear_chat_history_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.clearChatHistory()
                    showClearDialog = false
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun AppearanceSection(settings: AppSettings, viewModel: SettingsViewModel) {
    SectionHeader(stringResource(R.string.settings_section_appearance))
    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(8.dp))
    val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = settings.themeMode == mode,
                onClick = { viewModel.setThemeMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
            ) {
                Text(
                    when (mode) {
                        ThemeMode.SYSTEM -> stringResource(R.string.theme_mode_system)
                        ThemeMode.LIGHT -> stringResource(R.string.theme_mode_light)
                        ThemeMode.DARK -> stringResource(R.string.theme_mode_dark)
                    }
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    SwitchRow(
        label = stringResource(R.string.settings_dynamic_color),
        subtitle = stringResource(R.string.settings_dynamic_color_subtitle),
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
    Spacer(modifier = Modifier.height(12.dp))
    var showLanguageSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val storedLanguage by viewModel.language.collectAsStateWithLifecycle()
    val selectedLanguage = remember(storedLanguage, context) {
        AppLocale.currentLanguage(context, storedLanguage)
    }
    NavigationRow(
        label = stringResource(R.string.settings_language),
        subtitle = stringResource(selectedLanguage.labelRes),
        onClick = { showLanguageSheet = true }
    )
    if (showLanguageSheet) {
        LanguageBottomSheet(
            selected = selectedLanguage,
            onSelect = { language ->
                viewModel.setLanguage(language)
                showLanguageSheet = false
                // Applies the locale and recreates the activity so every
                // string (and the system bars) pick up the new language.
                (context as? Activity)?.let { AppLocale.apply(it, language) }
            },
            onDismiss = { showLanguageSheet = false }
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    SliderRow(
        label = stringResource(R.string.settings_font_size),
        valueText = "%.2f×".format(settings.fontScale),
        value = settings.fontScale,
        valueRange = 0.85f..1.30f,
        onValueChange = { viewModel.setFontScale(it) }
    )
}

@Composable
private fun InferenceSection(settings: AppSettings, viewModel: SettingsViewModel) {
    SectionHeader(stringResource(R.string.settings_section_inference))
    Text(stringResource(R.string.settings_default_backend), style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(8.dp))
    val backends = listOf(BackendPreference.AUTO, BackendPreference.GPU, BackendPreference.CPU)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        backends.forEachIndexed { index, backend ->
            SegmentedButton(
                selected = settings.defaultBackend == backend,
                onClick = { viewModel.setDefaultBackend(backend) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = backends.size)
            ) {
                Text(
                    when (backend) {
                        BackendPreference.AUTO -> stringResource(R.string.backend_auto)
                        BackendPreference.GPU -> stringResource(R.string.backend_gpu)
                        BackendPreference.CPU -> stringResource(R.string.backend_cpu)
                    }
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    SwitchRow(
        label = stringResource(R.string.settings_web_search),
        subtitle = stringResource(R.string.settings_web_search_subtitle),
        checked = settings.webSearchEnabled,
        onCheckedChange = { viewModel.setWebSearchEnabled(it) }
    )
    Text(
        stringResource(R.string.settings_web_search_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun DownloadsSection(settings: AppSettings, viewModel: SettingsViewModel) {
    SectionHeader(stringResource(R.string.settings_section_downloads))
    SwitchRow(
        label = stringResource(R.string.settings_wifi_only_downloads),
        subtitle = stringResource(R.string.settings_wifi_only_downloads_subtitle),
        checked = settings.wifiOnlyDownloads,
        onCheckedChange = { viewModel.setWifiOnlyDownloads(it) }
    )
    Text(
        stringResource(R.string.settings_downloads_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ChatSection(settings: AppSettings, viewModel: SettingsViewModel) {
    SectionHeader(stringResource(R.string.settings_section_chat))
    SwitchRow(
        label = stringResource(R.string.settings_auto_load_last_model),
        subtitle = stringResource(R.string.settings_auto_load_last_model_subtitle),
        checked = settings.autoLoadLastModel,
        onCheckedChange = { viewModel.setAutoLoadLastModel(it) }
    )
    Spacer(modifier = Modifier.height(12.dp))
    SwitchRow(
        label = stringResource(R.string.settings_keep_screen_on),
        subtitle = stringResource(R.string.settings_keep_screen_on_subtitle),
        checked = settings.keepScreenOn,
        onCheckedChange = { viewModel.setKeepScreenOn(it) }
    )
    Spacer(modifier = Modifier.height(12.dp))
    SwitchRow(
        label = stringResource(R.string.settings_show_token_counts),
        subtitle = stringResource(R.string.settings_show_token_counts_subtitle),
        checked = settings.showTokenCounts,
        onCheckedChange = { viewModel.setShowTokenCounts(it) }
    )
}

@Composable
private fun HuggingFaceSection(viewModel: SettingsViewModel) {
    val token by viewModel.huggingFaceToken.collectAsStateWithLifecycle()
    var showTokenDialog by remember { mutableStateOf(false) }

    SectionHeader(stringResource(R.string.settings_section_hugging_face))
    NavigationRow(
        label = stringResource(R.string.settings_access_token),
        subtitle = if (token.isNullOrBlank()) {
            stringResource(R.string.settings_token_not_set)
        } else {
            stringResource(R.string.settings_token_set, token!!.take(6), token!!.takeLast(4))
        },
        onClick = { showTokenDialog = true }
    )
    Text(
        stringResource(R.string.settings_token_help),
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
        title = { Text(stringResource(R.string.settings_token_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_token_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.settings_token_placeholder)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onSave(text) },
                enabled = text.isNotBlank()
            ) { Text(stringResource(R.string.settings_token_save)) }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    androidx.compose.material3.TextButton(onClick = onRemove) { Text(stringResource(R.string.settings_token_remove)) }
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    )
}

/**
 * Support section — GitHub star link and bug-report email.
 */
@Composable
private fun SupportSection() {
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.settings_section_support))
    NavigationRow(
        label = stringResource(R.string.settings_star_on_github),
        subtitle = stringResource(R.string.settings_star_on_github_subtitle),
        onClick = { openUrl(context, TinyConstants.SUPPORT_GITHUB_URL) }
    )
    NavigationRow(
        label = stringResource(R.string.settings_report_bug),
        subtitle = stringResource(R.string.settings_report_bug_subtitle),
        onClick = { reportBug(context) }
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun reportBug(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Intent.ACTION_SENDTO,
                Uri.parse(TinyConstants.SUPPORT_EMAIL_ADDRESS)
            ).apply {
                putExtra(Intent.EXTRA_SUBJECT, TinyConstants.SUPPORT_EMAIL_SUBJECT)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun AboutSection(
    onBenchmark: () -> Unit,
    onDeviceInfo: () -> Unit,
    onClearHistory: () -> Unit
) {
    SectionHeader(stringResource(R.string.settings_section_about))
    NavigationRow(
        label = stringResource(R.string.settings_benchmark),
        subtitle = stringResource(R.string.settings_benchmark_subtitle),
        onClick = onBenchmark
    )
    NavigationRow(
        label = stringResource(R.string.settings_device_information),
        subtitle = stringResource(R.string.settings_device_information_subtitle),
        onClick = onDeviceInfo
    )
    NavigationRow(
        label = stringResource(R.string.settings_clear_chat_history),
        subtitle = stringResource(R.string.settings_clear_chat_history_subtitle),
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
            Text(stringResource(R.string.settings_font), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(selected.labelRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (selected == FontChoice.SYSTEM) FontFamily.Default
                    else fontFamilyFor(selected)
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.settings_change_font_content_description),
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
                stringResource(R.string.settings_font_sheet_title),
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
                        text = stringResource(choice.labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = if (choice == FontChoice.SYSTEM) FontFamily.Default
                            else fontFamilyFor(choice),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (choice == FontChoice.SYSTEM) {
                            stringResource(R.string.settings_font_default)
                        } else {
                            stringResource(R.string.settings_font_google)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Test tag for the language sheet's content column, so the instrumented inset
 * test can measure the content's laid-out rect against the window insets.
 */
internal const val LANGUAGE_SHEET_CONTENT_TAG = "language_sheet_content"

/**
 * Bottom sheet listing the languages the app is translated into.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageBottomSheet(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Suppress the sheet's built-in inset handling so we control it precisely.
        contentWindowInsets = { WindowInsets(0) },
        // Keep the sheet surface below the status bar when it grows tall
        // (short screens in landscape).
        modifier = Modifier
            .fillMaxWidth()
            .bottomSheetTopSafePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(LANGUAGE_SHEET_CONTENT_TAG)
                .padding(horizontal = 24.dp)
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .padding(bottom = 16.dp)
        ) {
            Text(
                stringResource(R.string.settings_language_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            AppLanguage.entries.forEach { language ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(language) }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = language == selected,
                        onClick = { onSelect(language) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(language.labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Model Management section — import only. Per-file export lives on the
 * downloaded file's detail screen.
 */
@Composable
private fun ModelManagementSection(
    onImportClick: () -> Unit
) {
    SectionHeader(stringResource(R.string.settings_section_model_management))

    // Import Model
    NavigationRow(
        label = stringResource(R.string.settings_import_model),
        subtitle = stringResource(R.string.settings_import_model_subtitle),
        onClick = onImportClick
    )

    Text(
        stringResource(R.string.settings_import_model_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}
