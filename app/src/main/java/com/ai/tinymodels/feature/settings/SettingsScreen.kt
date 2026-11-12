package com.ai.tinymodels.feature.settings

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.tinymodels.R
import com.ai.tinymodels.core.common.AppLocale
import com.ai.tinymodels.core.ui.components.bottomSheetTopSafePadding
import com.ai.tinymodels.core.ui.theme.colorSchemeFor
import com.ai.tinymodels.core.ui.theme.fontFamilyFor
import com.ai.tinymodels.domain.model.AppLanguage
import com.ai.tinymodels.domain.model.AppSettings
import com.ai.tinymodels.domain.model.AppTheme
import com.ai.tinymodels.domain.model.BackendPreference
import com.ai.tinymodels.domain.model.FontChoice
import com.ai.tinymodels.domain.model.ThemeMode
import com.ai.tinymodels.feature.widget.TinyModelsWidgetReceiver
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageModels: () -> Unit,
    onDownloadedModels: () -> Unit,
    onDeviceInfo: () -> Unit,
    onBenchmark: () -> Unit = {},
    onViewIntro: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val clearResult by viewModel.clearResult.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showClearDialog by remember { mutableStateOf(false) }

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

    LaunchedEffect(clearResult) {
        clearResult?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.consumeClearResult()
        }
    }

    val safeTopHorizontal = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

    Scaffold(
        contentWindowInsets = safeTopHorizontal,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.SemiBold) },
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppearanceSection(settings, viewModel)
            ChatInferenceSection(settings, viewModel)
            DownloadsModelsSection(
                settings = settings,
                viewModel = viewModel,
                onImportClick = { importLauncher.launch(arrayOf("*/*")) }
            )
            AccountsSection(viewModel)
            HomeScreenSection(
                onAddWidget = {
                    val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
                    val provider = ComponentName(context, TinyModelsWidgetReceiver::class.java)
                    if (appWidgetManager?.isRequestPinAppWidgetSupported == true) {
                        appWidgetManager.requestPinAppWidget(provider, null, null)
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.settings_add_widget_not_supported)
                            )
                        }
                    }
                }
            )
            ToolsAboutSection(
                onBenchmark = onBenchmark,
                onDeviceInfo = onDeviceInfo,
                onViewIntro = onViewIntro
            )
            SupportSection()
            DataSection(onClearHistory = { showClearDialog = true })
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (isImporting) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(R.string.settings_importing_model),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.settings_clear_chat_history_title)) },
            text = { Text(stringResource(R.string.settings_clear_chat_history_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearChatHistory()
                    showClearDialog = false
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

// ---- Sections ----

@Composable
private fun AppearanceSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    var showThemeSheet by remember { mutableStateOf(false) }
    var showFontSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    val storedLanguage by viewModel.language.collectAsStateWithLifecycle()
    val selectedLanguage = remember(storedLanguage, context) {
        AppLocale.currentLanguage(context, storedLanguage)
    }

    SettingsSection(
        title = stringResource(R.string.settings_section_appearance),
        icon = Icons.Filled.Palette
    ) {
        SettingsNavigationItem(
            icon = Icons.Filled.Language,
            label = stringResource(R.string.settings_language),
            subtitle = selectedLanguage.displayLabel(),
            onClick = { showLanguageSheet = true }
        )
        SettingsDivider()
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LeadingIcon(Icons.Filled.DarkMode)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(modifier = Modifier.height(10.dp))
            val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                    ) {
                        Text(stringResource(themeModeLabel(mode)))
                    }
                }
            }
        }
        SettingsDivider()
        SettingsNavigationItem(
            icon = Icons.Filled.ColorLens,
            label = stringResource(R.string.settings_color_theme),
            subtitle = stringResource(appThemeLabel(settings.appTheme)),
            onClick = { showThemeSheet = true }
        )
        SettingsDivider()
        SettingsSwitchItem(
            icon = Icons.Filled.Palette,
            label = stringResource(R.string.settings_dynamic_color),
            subtitle = stringResource(R.string.settings_dynamic_color_subtitle),
            checked = settings.useDynamicColor,
            onCheckedChange = { viewModel.setDynamicColor(it) }
        )
        SettingsDivider()
        SettingsNavigationItem(
            icon = Icons.Filled.FontDownload,
            label = stringResource(R.string.settings_font),
            subtitle = stringResource(settings.fontChoice.labelRes),
            onClick = { showFontSheet = true }
        )
        SettingsDivider()
        FontSizeSetting(settings = settings, viewModel = viewModel)
    }

    if (showThemeSheet) {
        ColorThemeBottomSheet(
            selected = settings.appTheme,
            onSelect = { viewModel.setAppTheme(it); showThemeSheet = false },
            onDismiss = { showThemeSheet = false }
        )
    }
    if (showFontSheet) {
        FontBottomSheet(
            selected = settings.fontChoice,
            onSelect = { viewModel.setFontChoice(it); showFontSheet = false },
            onDismiss = { showFontSheet = false }
        )
    }
    if (showLanguageSheet) {
        LanguageBottomSheet(
            selected = selectedLanguage,
            onSelect = { language ->
                viewModel.setLanguage(language)
                showLanguageSheet = false
                (context as? Activity)?.let { AppLocale.apply(it, language) }
            },
            onDismiss = { showLanguageSheet = false }
        )
    }
}

@Composable
private fun ChatInferenceSection(settings: AppSettings, viewModel: SettingsViewModel) {
    SettingsSection(
        title = stringResource(R.string.settings_section_chat),
        icon = Icons.Filled.AutoAwesome
    ) {
        SettingsSwitchItem(
            icon = Icons.Filled.AutoAwesome,
            label = stringResource(R.string.settings_auto_load_last_model),
            subtitle = stringResource(R.string.settings_auto_load_last_model_subtitle),
            checked = settings.autoLoadLastModel,
            onCheckedChange = { viewModel.setAutoLoadLastModel(it) }
        )
        SettingsDivider()
        SettingsSwitchItem(
            icon = Icons.Filled.Lightbulb,
            label = stringResource(R.string.settings_keep_screen_on),
            subtitle = stringResource(R.string.settings_keep_screen_on_subtitle),
            checked = settings.keepScreenOn,
            onCheckedChange = { viewModel.setKeepScreenOn(it) }
        )
        SettingsDivider()
        SettingsSwitchItem(
            icon = Icons.Filled.Tag,
            label = stringResource(R.string.settings_show_token_counts),
            subtitle = stringResource(R.string.settings_show_token_counts_subtitle),
            checked = settings.showTokenCounts,
            onCheckedChange = { viewModel.setShowTokenCounts(it) }
        )
        SettingsDivider()
        SettingsSwitchItem(
            icon = Icons.Filled.TravelExplore,
            label = stringResource(R.string.settings_web_search),
            subtitle = stringResource(R.string.settings_web_search_subtitle),
            checked = settings.webSearchEnabled,
            onCheckedChange = { viewModel.setWebSearchEnabled(it) }
        )
        SettingsDivider()
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LeadingIcon(Icons.Filled.Memory)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.settings_default_backend), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_section_inference),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            val backends = listOf(BackendPreference.AUTO, BackendPreference.GPU, BackendPreference.CPU)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                backends.forEachIndexed { index, backend ->
                    SegmentedButton(
                        selected = settings.defaultBackend == backend,
                        onClick = { viewModel.setDefaultBackend(backend) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = backends.size)
                    ) {
                        Text(stringResource(backendLabel(backend)))
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsModelsSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onImportClick: () -> Unit
) {
    SettingsSection(
        title = stringResource(R.string.settings_section_downloads),
        icon = Icons.Filled.FileUpload
    ) {
        SettingsSwitchItem(
            icon = Icons.Filled.Wifi,
            label = stringResource(R.string.settings_wifi_only_downloads),
            subtitle = stringResource(R.string.settings_wifi_only_downloads_subtitle),
            checked = settings.wifiOnlyDownloads,
            onCheckedChange = { viewModel.setWifiOnlyDownloads(it) }
        )
        SettingsDivider()
        SettingsNavigationItem(
            icon = Icons.Filled.FileUpload,
            label = stringResource(R.string.settings_import_model),
            subtitle = stringResource(R.string.settings_import_model_subtitle),
            onClick = onImportClick
        )
    }
}

@Composable
private fun AccountsSection(viewModel: SettingsViewModel) {
    val token by viewModel.huggingFaceToken.collectAsStateWithLifecycle()
    var showTokenDialog by remember { mutableStateOf(false) }

    SettingsSection(
        title = stringResource(R.string.settings_section_hugging_face),
        icon = Icons.Filled.Key
    ) {
        SettingsNavigationItem(
            icon = Icons.Filled.Key,
            label = stringResource(R.string.settings_access_token),
            subtitle = if (token.isNullOrBlank()) {
                stringResource(R.string.settings_token_not_set)
            } else {
                stringResource(R.string.settings_token_set, token!!.take(6), token!!.takeLast(4))
            },
            onClick = { showTokenDialog = true }
        )
    }

    if (showTokenDialog) {
        TokenDialog(
            current = token,
            onSave = { viewModel.setHuggingFaceToken(it); showTokenDialog = false },
            onRemove = { viewModel.setHuggingFaceToken(null); showTokenDialog = false },
            onDismiss = { showTokenDialog = false }
        )
    }
}

@Composable
private fun HomeScreenSection(onAddWidget: () -> Unit) {
    SettingsSection(
        title = stringResource(R.string.settings_section_home_screen),
        icon = Icons.Filled.Widgets
    ) {
        SettingsNavigationItem(
            icon = Icons.Filled.Widgets,
            label = stringResource(R.string.settings_add_widget),
            subtitle = stringResource(R.string.settings_add_widget_subtitle),
            onClick = onAddWidget
        )
    }
}

@Composable
private fun ToolsAboutSection(
    onBenchmark: () -> Unit,
    onDeviceInfo: () -> Unit,
    onViewIntro: () -> Unit
) {
    SettingsSection(
        title = stringResource(R.string.settings_section_about),
        icon = Icons.Filled.PhoneAndroid
    ) {
        SettingsNavigationItem(
            icon = Icons.Filled.Speed,
            label = stringResource(R.string.settings_benchmark),
            subtitle = stringResource(R.string.settings_benchmark_subtitle),
            onClick = onBenchmark
        )
        SettingsDivider()
        SettingsNavigationItem(
            icon = Icons.Filled.PhoneAndroid,
            label = stringResource(R.string.settings_device_information),
            subtitle = stringResource(R.string.settings_device_information_subtitle),
            onClick = onDeviceInfo
        )
        SettingsDivider()
        SettingsNavigationItem(
            icon = Icons.Outlined.AutoStories,
            label = stringResource(R.string.settings_view_intro),
            subtitle = stringResource(R.string.settings_view_intro_subtitle),
            onClick = onViewIntro
        )
    }
}

@Composable
private fun SupportSection() {
    val context = LocalContext.current
    SettingsSection(
        title = stringResource(R.string.settings_section_support),
        icon = Icons.Filled.Star
    ) {
        SettingsNavigationItem(
            icon = Icons.Filled.Star,
            label = stringResource(R.string.settings_star_on_github),
            subtitle = stringResource(R.string.settings_star_on_github_subtitle),
            onClick = { openUrl(context, "https://github.com/vengateshk18/TinyModels") }
        )
        SettingsDivider()
        SettingsNavigationItem(
            icon = Icons.Filled.BugReport,
            label = stringResource(R.string.settings_report_bug),
            subtitle = stringResource(R.string.settings_report_bug_subtitle),
            onClick = { reportBug(context) }
        )
    }
}

@Composable
private fun DataSection(onClearHistory: () -> Unit) {
    SettingsSection(
        title = stringResource(R.string.settings_clear_chat_history),
        icon = Icons.Filled.DeleteSweep
    ) {
        SettingsNavigationItem(
            icon = Icons.Filled.DeleteSweep,
            label = stringResource(R.string.settings_clear_chat_history),
            subtitle = stringResource(R.string.settings_clear_chat_history_subtitle),
            onClick = onClearHistory,
            tint = MaterialTheme.colorScheme.error
        )
    }
}

// ---- Reusable building blocks ----

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 54.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
private fun LeadingIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SettingsNavigationItem(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LeadingIcon(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsSwitchItem(
    icon: ImageVector,
    label: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LeadingIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---- Font size ----

private const val FONT_SCALE_MIN = 0.85f
private const val FONT_SCALE_MAX = 1.30f
private const val FONT_SCALE_STEPS = 8

@Composable
private fun FontSizeSetting(settings: AppSettings, viewModel: SettingsViewModel) {
    val appliedScale = settings.fontScale
    var pendingScale by remember(appliedScale) { mutableStateOf(appliedScale) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LeadingIcon(Icons.Filled.FormatSize)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_font_size), style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "%.2f×".format(pendingScale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = pendingScale,
            onValueChange = { pendingScale = it },
            valueRange = FONT_SCALE_MIN..FONT_SCALE_MAX,
            steps = FONT_SCALE_STEPS,
            onValueChangeFinished = {
                if (pendingScale != appliedScale) viewModel.setFontScale(pendingScale)
            },
            modifier = Modifier.padding(start = 50.dp)
        )
        FontSizePreview(scale = pendingScale, fontChoice = settings.fontChoice)
        if (pendingScale != 1.0f) {
            TextButton(onClick = { viewModel.setFontScale(1.0f) }, modifier = Modifier.padding(start = 42.dp)) {
                Text(stringResource(R.string.settings_font_reset))
            }
        }
    }
}

@Composable
private fun FontSizePreview(scale: Float, fontChoice: FontChoice) {
    val family = remember(fontChoice) { fontFamilyFor(fontChoice) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 50.dp, top = 4.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                fontFamily = family,
                fontSize = (18f * scale).sp,
                lineHeight = (24f * scale).sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_font_preview_body),
                fontFamily = family,
                fontSize = (13f * scale).sp,
                lineHeight = (18f * scale).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---- Dialogs / Sheets / Helpers ----

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
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.settings_token_placeholder)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.settings_token_save))
            }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.settings_token_remove)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    )
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
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .padding(bottom = 16.dp)
        ) {
            Text(stringResource(R.string.settings_font_sheet_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            FontChoice.entries.forEach { choice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(choice) }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = choice == selected, onClick = { onSelect(choice) })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(choice.labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = if (choice == FontChoice.SYSTEM) FontFamily.Default else fontFamilyFor(choice),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

internal const val LANGUAGE_SHEET_CONTENT_TAG = "language_sheet_content"

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
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.fillMaxWidth().bottomSheetTopSafePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(LANGUAGE_SHEET_CONTENT_TAG)
                .padding(horizontal = 24.dp)
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .padding(bottom = 16.dp)
        ) {
            Text(stringResource(R.string.settings_language_sheet_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            AppLanguage.entries.forEach { language ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(language) }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = language == selected, onClick = { onSelect(language) })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = language.displayLabel(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorThemeBottomSheet(
    selected: AppTheme,
    onSelect: (AppTheme) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.fillMaxWidth().bottomSheetTopSafePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .padding(bottom = 16.dp)
        ) {
            Text(stringResource(R.string.settings_color_theme_sheet_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            Text(stringResource(R.string.settings_color_theme_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            val previewDark = isSystemInDarkTheme()
            AppTheme.entries.forEach { theme ->
                val scheme = colorSchemeFor(theme, darkTheme = previewDark)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(theme) }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = theme == selected, onClick = { onSelect(theme) })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(appThemeLabel(theme)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(18.dp).background(scheme.primary, CircleShape))
                        Box(modifier = Modifier.size(18.dp).background(scheme.secondary, CircleShape))
                    }
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun reportBug(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:vengatesh00014@gamil.com")).apply {
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_bug_report_subject))
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@StringRes
private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

@StringRes
private fun appThemeLabel(theme: AppTheme): Int = when (theme) {
    AppTheme.DEFAULT -> R.string.theme_default
    AppTheme.GITHUB -> R.string.theme_github
    AppTheme.VERCEL -> R.string.theme_vercel
    AppTheme.NIGHT_OWL -> R.string.theme_night_owl
    AppTheme.DRACULA -> R.string.theme_dracula
    AppTheme.NORD -> R.string.theme_nord
    AppTheme.CATPPUCCIN -> R.string.theme_catppuccin
}

@StringRes
private fun backendLabel(backend: BackendPreference): Int = when (backend) {
    BackendPreference.AUTO -> R.string.settings_backend_auto
    BackendPreference.GPU -> R.string.settings_backend_gpu
    BackendPreference.CPU -> R.string.settings_backend_cpu
}
