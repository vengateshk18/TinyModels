package com.example.tinymodels.feature.models.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tinymodels.core.ui.Formatters
import com.example.tinymodels.core.ui.components.EmptyState
import com.example.tinymodels.core.ui.components.ErrorState
import com.example.tinymodels.core.ui.components.OfflineState
import com.example.tinymodels.domain.model.DownloadedModelFile
import com.example.tinymodels.domain.model.ModelFilter
import com.example.tinymodels.domain.model.ModelSummary
import com.example.tinymodels.feature.models.DownloadedModelsViewModel
import com.example.tinymodels.feature.models.ModelListViewModel

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
    val downloadedModels by downloadedViewModel.models.collectAsStateWithLifecycle()
    val downloadedFiles by downloadedViewModel.downloadedFiles.collectAsStateWithLifecycle()

    // Tab screen — outer Scaffold already handled the bottom nav-bar inset.
    // Only top + horizontal needed here.
    val safeTopHorizontal = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

    Scaffold(
        contentWindowInsets = safeTopHorizontal,
        topBar = {
            TopAppBar(
                title = { Text("Models") },
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
                    text = { Text("Browse") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Downloaded (${downloadedFiles.size})") }
                )
            }

            when (selectedTab) {
                0 -> BrowseContent(
                    uiState = uiState,
                    onSearch = listViewModel::search,
                    onFilterChange = listViewModel::setFilter,
                    onRetry = listViewModel::refresh,
                    onModelClick = onModelClick
                )
                1 -> DownloadedContent(
                    downloadedFiles = downloadedFiles,
                    totalSizeBytes = downloadedViewModel.totalSizeBytes.value,
                    onFileClick = onFileClick
                )
            }
        }
    }
}


// ---- Browse content with search + filter chips ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowseContent(
    uiState: ModelListViewModel.UiState,
    onSearch: (String) -> Unit,
    onFilterChange: (ModelFilter) -> Unit,
    onRetry: () -> Unit,
    onModelClick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search models\u2026") },
            singleLine = true,
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = MaterialTheme.shapes.large
        )
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModelFilter.entries.forEach { filter ->
                FilterChip(
                    selected = uiState.selectedFilter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(filter.label) }
                )
            }
        }
        when {
            uiState.isLoading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            uiState.isOfflineError ->
                OfflineState(onRetry = onRetry)
            uiState.error != null ->
                ErrorState(
                    message = uiState.errorMessage ?: "Failed to load models",
                    onRetry = onRetry
                )
            uiState.models.isEmpty() ->
                EmptyState(
                    title = "No models found",
                    message = "Try a different search or filter."
                )
            else ->
                LazyColumn(
                    // top/horizontal: 16dp; bottom: 16dp list gap + 24dp breathing
                    // room above the nav bar so the last card is never clipped.
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = 40.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.models, key = { it.modelId }) { model ->
                        ModelCard(model = model, onClick = { onModelClick(model.modelId) })
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
                        "${model.runtimeFileCount} file${if (model.runtimeFileCount > 1) "s" else ""}"
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pipeline tag — always shown; defaults to text-generation when
                // the API omits it (common for LiteRT model repos).
                PipelineChip(model.pipelineTag?.takeIf { it.isNotBlank() } ?: "text-generation")
                model.libraryName?.takeIf { it.isNotBlank() }?.let { lib ->
                    SmallChip(lib)
                }
                model.lastModified?.takeIf { it.isNotBlank() }?.let { date ->
                    Text(
                        Formatters.formatDate(date),
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

// ---- Downloaded content: flat list of individual files ----

@Composable
private fun DownloadedContent(
    downloadedFiles: List<DownloadedModelFile>,
    totalSizeBytes: Long,
    onFileClick: (modelId: String, fileName: String) -> Unit
) {
    if (downloadedFiles.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No models downloaded", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Browse the catalog to download one.",
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
                                "${downloadedFiles.size} file${if (downloadedFiles.size > 1) "s" else ""}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                Formatters.formatBytes(totalSizeBytes),
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
                    Formatters.formatBytes(file.sizeBytes),
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
