package com.ai.tinymodels.feature.models.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.tinymodels.R
import com.ai.tinymodels.core.ui.Formatters
import com.ai.tinymodels.core.ui.components.EmptyState
import com.ai.tinymodels.core.ui.components.ErrorState
import com.ai.tinymodels.core.ui.components.OfflineState
import com.ai.tinymodels.core.ui.components.bottomSheetTopSafePadding
import com.ai.tinymodels.domain.model.DownloadedModelFile
import com.ai.tinymodels.domain.model.MobileFit
import com.ai.tinymodels.domain.model.ModelFeed
import com.ai.tinymodels.domain.model.ModelFilter
import com.ai.tinymodels.domain.model.ModelSummary
import com.ai.tinymodels.domain.usecase.model.ActiveDownload
import com.ai.tinymodels.feature.models.DownloadedModelsViewModel
import com.ai.tinymodels.feature.models.ModelImportProgress
import com.ai.tinymodels.feature.models.ModelListViewModel
import com.ai.tinymodels.feature.models.components.ActiveDownloadsSection
import java.util.UUID

/**
 * Models tab (bottom-nav). Two sub-tabs: Browse (Hugging Face catalog) and
 * Downloaded (on-device models). No back button — it's a root tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsTabScreen(
    onModelClick: (String) -> Unit,
    onFileClick: (modelId: String, fileName: String) -> Unit = { _, _ -> },
    listViewModel: ModelListViewModel = hiltViewModel(),
    downloadedViewModel: DownloadedModelsViewModel = hiltViewModel()
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val uiState by listViewModel.uiState.collectAsStateWithLifecycle()
    val importToast by listViewModel.importToast.collectAsStateWithLifecycle()
    val downloadedModels by downloadedViewModel.models.collectAsStateWithLifecycle()
    val downloadedFiles by downloadedViewModel.downloadedFiles.collectAsStateWithLifecycle()
    val totalSizeBytes by downloadedViewModel.totalSizeBytes.collectAsStateWithLifecycle()
    val activeDownloads by downloadedViewModel.activeDownloads.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showFilterSheet by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Best-effort: persistable permission not always available.
        }
        listViewModel.importModel(uri)
    }

    LaunchedEffect(importToast) {
        val message = importToast ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        listViewModel.consumeImportToast()
    }

    BackHandler(enabled = uiState.isImporting) { }

    // Tab screen — outer Scaffold already handled the bottom nav-bar inset.
    // Only top + horizontal needed here.
    val safeTopHorizontal = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

    Scaffold(
        contentWindowInsets = safeTopHorizontal,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.models_title)) },
                actions = {
                    if (selectedTab == 0) {
                        val filterActive = uiState.selectedFilter != ModelFilter.ALL || uiState.selectedTag != null
                        IconButton(onClick = { showFilterSheet = true }) {
                            if (filterActive) {
                                BadgedBox(badge = { Badge() }) {
                                    Icon(
                                        painter = painterResource(R.drawable.filter_asset),
                                        contentDescription = stringResource(R.string.models_filters)
                                    )
                                }
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.filter_asset),
                                    contentDescription = stringResource(R.string.models_filters)
                                )
                            }
                        }
                        IconButton(onClick = { showSortSheet = true }) {
                            Icon(
                                painter = painterResource(R.drawable.sort_asset),
                                contentDescription = stringResource(R.string.models_filter_feed)
                            )
                        }
                    }
                    IconButton(
                        onClick = { modelPicker.launch(arrayOf("*/*")) },
                        enabled = !uiState.isImporting
                    ) {
                        Icon(
                            Icons.Filled.FileUpload,
                            contentDescription = stringResource(R.string.settings_import_model)
                        )
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
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.models_browse_tab)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.models_downloaded_tab, downloadedFiles.size)) }
                )
            }

            when (selectedTab) {
                0 -> BrowseContent(
                    uiState = uiState,
                    onSearch = listViewModel::search,
                    onLoadMore = listViewModel::loadMore,
                    onRetry = listViewModel::refresh,
                    onModelClick = onModelClick
                )
                1 -> DownloadedContent(
                    downloadedFiles = downloadedFiles,
                    totalSizeBytes = totalSizeBytes,
                    activeDownloads = activeDownloads,
                    onCancelActive = downloadedViewModel::cancelActiveDownload,
                    onFileClick = onFileClick
                )
            }
        }

        if (uiState.isImporting) {
            ImportProgressOverlay(progress = uiState.importProgress)
        }
    }

    if (showFilterSheet) {
        FilterSheet(
            initialFilter = uiState.selectedFilter,
            initialTag = uiState.selectedTag,
            tagTypes = uiState.tagTypes,
            onApply = { filter, tag ->
                listViewModel.applyBrowseOptions(uiState.selectedFeed, filter, tag)
                showFilterSheet = false
            },
            onDismiss = { showFilterSheet = false }
        )
    }

    if (showSortSheet) {
        SortSheet(
            initialFeed = uiState.selectedFeed,
            onApply = { feed ->
                listViewModel.applyBrowseOptions(feed, uiState.selectedFilter, uiState.selectedTag)
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false }
        )
    }
}

@Composable
private fun ImportProgressOverlay(progress: ModelImportProgress?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.settings_importing_model),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                val percent = progress?.percent
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    val copied = progress?.bytesCopied ?: 0L
                    if (copied > 0L) {
                        Text(
                            text = Formatter.formatShortFileSize(LocalContext.current, copied),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ---- Browse content ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseContent(
    uiState: ModelListViewModel.UiState,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onModelClick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.models_search_placeholder)) },
            singleLine = true,
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.models_search_content_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = MaterialTheme.shapes.large
        )

        when {
            uiState.isLoading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            uiState.isOfflineError ->
                OfflineState(onRetry = onRetry)
            uiState.error != null ->
                ErrorState(
                    message = uiState.errorMessageRes?.let { stringResource(it) }
                        ?: stringResource(R.string.models_error_failed_to_load),
                    onRetry = onRetry
                )
            uiState.models.isEmpty() ->
                EmptyState(
                    title = stringResource(R.string.models_empty_title),
                    message = stringResource(R.string.models_empty_message)
                )
            else ->
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = 40.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.models, key = { it.modelId }) { model ->
                        ModelCard(model = model, onClick = { onModelClick(model.modelId) })
                    }
                    if (uiState.hasMore || uiState.isLoadingMore) {
                        item(key = "load-more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (uiState.isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    TextButton(onClick = onLoadMore) {
                                        Text(stringResource(R.string.models_load_more))
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    initialFilter: ModelFilter,
    initialTag: String?,
    tagTypes: Map<String, List<String>>,
    onApply: (ModelFilter, String?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draftFilter by remember(initialFilter, initialTag) { mutableStateOf(initialFilter) }
    var draftTag by remember(initialFilter, initialTag) { mutableStateOf(initialTag) }
    val allTags = remember(tagTypes) {
        buildList {
            tagTypes["library"]?.let { addAll(it) }
            tagTypes["pipeline_tag"]?.let { addAll(it) }
            tagTypes["license"]?.let { addAll(it.map { tag -> if (tag.startsWith("license:")) tag else "license:$tag" }) }
            tagTypes["language"]?.take(10)?.let { addAll(it) }
            tagTypes["other"]?.take(10)?.let { addAll(it) }
        }.distinct().take(40)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.bottomSheetTopSafePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .padding(bottom = 8.dp)
        ) {
            Text(stringResource(R.string.models_filters_title), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.models_filter_format), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModelFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = draftFilter == filter,
                            onClick = { draftFilter = filter },
                            label = { Text(stringResource(filter.labelRes)) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.models_tag_filter_label), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draftTag == null,
                        onClick = { draftTag = null },
                        label = { Text(stringResource(R.string.models_tag_filter_any)) }
                    )
                    allTags.forEach { tag ->
                        FilterChip(
                            selected = draftTag == tag,
                            onClick = { draftTag = if (draftTag == tag) null else tag },
                            label = { Text(tag.removePrefix("license:"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        draftFilter = ModelFilter.ALL
                        draftTag = null
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.models_clear_filters))
                }
                Button(
                    onClick = { onApply(draftFilter, draftTag) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.models_apply_filters))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SortSheet(
    initialFeed: ModelFeed,
    onApply: (ModelFeed) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draftFeed by remember(initialFeed) { mutableStateOf(initialFeed) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.bottomSheetTopSafePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .padding(bottom = 8.dp)
        ) {
            Text(stringResource(R.string.models_filter_feed), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelFeed.entries.forEach { feed ->
                    FilterChip(
                        selected = draftFeed == feed,
                        onClick = { draftFeed = feed },
                        label = { Text(stringResource(feed.labelRes)) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { draftFeed = ModelFeed.POPULAR },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.models_clear_filters))
                }
                Button(
                    onClick = { onApply(draftFeed) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.models_apply_filters))
                }
            }
        }
    }
}

@Composable
private fun ModelCard(model: ModelSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = model.modelId,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (model.author.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        model.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatBadge(Icons.Filled.TrendingUp, Formatters.formatCount(model.downloads ?: 0))
                StatBadge(Icons.Filled.Favorite, Formatters.formatCount((model.likes ?: 0).toLong()))
                if (model.runtimeFileCount > 0) {
                    StatBadge(
                        Icons.Filled.Download,
                        pluralStringResource(
                            R.plurals.models_file_count,
                            model.runtimeFileCount,
                            model.runtimeFileCount
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            model.lastModified?.takeIf { it.isNotBlank() }?.let { date ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CalendarToday,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        stringResource(
                            R.string.models_updated_on,
                            Formatters.formatDate(date, LocalContext.current.resources)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pipeline tag — always shown; defaults to text-generation when
                // the API omits it (common for LiteRT model repos).
                PipelineChip(model.pipelineTag?.takeIf { it.isNotBlank() } ?: stringResource(R.string.models_pipeline_default))
                if (MobileFit.isMobileFriendly(model)) {
                    MobileReadyChip()
                }
                model.libraryName?.takeIf { it.isNotBlank() }?.let { lib ->
                    SmallChip(lib)
                }
                model.lastModified?.takeIf { it.isNotBlank() }?.let { date ->
                    Text(
                        Formatters.formatDate(date, LocalContext.current.resources),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatBadge(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PipelineChip(label: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SmallChip(label: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** Badge for models whose size suggests they run comfortably on a phone. */
@Composable
private fun MobileReadyChip() {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            "Mobile-ready",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

// ---- Downloaded content: flat list of individual files ----

@Composable
private fun DownloadedContent(
    downloadedFiles: List<DownloadedModelFile>,
    totalSizeBytes: Long,
    activeDownloads: List<ActiveDownload>,
    onCancelActive: (UUID) -> Unit,
    onFileClick: (modelId: String, fileName: String) -> Unit
) {
    if (downloadedFiles.isEmpty() && activeDownloads.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.downloaded_models_empty_title), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.downloaded_models_empty_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 40.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (activeDownloads.isNotEmpty()) {
                item {
                    ActiveDownloadsSection(
                        active = activeDownloads,
                        onCancel = onCancelActive
                    )
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                pluralStringResource(
                                    R.plurals.models_file_count,
                                    downloadedFiles.size,
                                    downloadedFiles.size
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                Formatters.formatBytes(totalSizeBytes, LocalContext.current.resources),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            items(downloadedFiles, key = { "${it.modelId}/${it.fileName}" }) { file ->
                DownloadedFileCard(
                    file = file,
                    onClick = { onFileClick(file.modelId, file.fileName) }
                )
            }
        }
    }
}

@Composable
private fun DownloadedFileCard(
    file: DownloadedModelFile,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.fileName.substringAfterLast("/"),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    file.modelId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    Formatters.formatBytes(file.sizeBytes, LocalContext.current.resources),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
