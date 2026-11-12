package com.ai.tinymodels.feature.models.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.tinymodels.R
import com.ai.tinymodels.core.ui.Formatters
import com.ai.tinymodels.core.ui.components.ErrorState
import com.ai.tinymodels.feature.models.DownloadedFileDetailViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedFileDetailScreen(
    onBack: () -> Unit,
    onStartChat: (preferredModelId: String) -> Unit,
    viewModel: DownloadedFileDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // SAF folder picker for per-file export.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.exportFile(it)
        }
    }

    LaunchedEffect(uiState.exportMessage) {
        uiState.exportMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeExportMessage()
        }
    }

    val safeDrawing = WindowInsets.systemBars.union(WindowInsets.displayCutout)

    Scaffold(
        contentWindowInsets = safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloaded_file_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                uiState.error != null ->
                    ErrorState(
                        message = uiState.error!!,
                        title = stringResource(R.string.downloaded_file_not_found_title),
                        onRetry = onBack,
                        retryLabel = stringResource(R.string.downloaded_file_go_back)
                    )
                uiState.fileInfo != null ->
                    FileDetailContent(
                        fileInfo = uiState.fileInfo!!,
                        isLoadingModel = uiState.isLoadingModel,
                        loadModelError = uiState.loadModelError,
                        isDeleting = uiState.isDeleting,
                        isExporting = uiState.isExporting,
                        onStartChat = { modelId -> onStartChat(modelId) },
                        onDelete = { showDeleteDialog = true },
                        onExport = { exportLauncher.launch(null) },
                        onClearLoadError = viewModel::clearLoadError
                    )
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.downloaded_file_delete_title)) },
            text = {
                Text(stringResource(R.string.downloaded_file_delete_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.onDelete { parentModelDeleted ->
                            if (parentModelDeleted) {
                                // Parent model was also deleted, go back to downloaded models list
                                onBack()
                                // Note: onBack() will pop this screen, and DownloadedModelsScreen
                                // will show the updated list automatically since it observes the repository
                            } else {
                                onBack()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun FileDetailContent(
    fileInfo: com.ai.tinymodels.domain.model.DownloadedModelFile,
    isLoadingModel: Boolean,
    loadModelError: String?,
    isDeleting: Boolean,
    isExporting: Boolean,
    onStartChat: (String) -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onClearLoadError: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // File info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    fileInfo.fileName.substringAfterLast("/"),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                InfoRow(Icons.Filled.InsertDriveFile, stringResource(R.string.downloaded_file_model_id), fileInfo.modelId)
                InfoRow(Icons.Filled.SdStorage, stringResource(R.string.downloaded_file_size), Formatters.formatBytes(fileInfo.sizeBytes, LocalContext.current.resources))
                fileInfo.localPath?.let {
                    InfoRow(Icons.Filled.Folder, stringResource(R.string.downloaded_file_location), it)
                }
                
                // Show downloaded date if we can get it from somewhere
                // For now, we'll skip it as it's not in the DownloadedModelFile entity
            }
        }

        // Load model error
        loadModelError?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.downloaded_file_error_failed_to_load),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    IconButton(onClick = onClearLoadError) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.common_dismiss),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // Actions
        Button(
            onClick = { onStartChat(fileInfo.modelId) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoadingModel && !isDeleting && !isExporting
        ) {
            if (isLoadingModel) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isLoadingModel) stringResource(R.string.downloaded_file_loading_model) else stringResource(R.string.downloaded_file_start_chat))
        }

        OutlinedButton(
            onClick = onExport,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoadingModel && !isDeleting && !isExporting
        ) {
            if (isExporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isExporting) stringResource(R.string.downloaded_file_exporting) else stringResource(R.string.downloaded_file_export))
        }

        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoadingModel && !isDeleting && !isExporting
        ) {
            if (isDeleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isDeleting) stringResource(R.string.downloaded_file_deleting) else stringResource(R.string.downloaded_file_delete))
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

