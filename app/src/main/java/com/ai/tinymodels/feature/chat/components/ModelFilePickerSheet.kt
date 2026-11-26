package com.ai.tinymodels.feature.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.tinymodels.R
import com.ai.tinymodels.core.common.TinyConstants
import com.ai.tinymodels.core.common.TinyLog
import com.ai.tinymodels.core.network.cloud.CloudModel
import com.ai.tinymodels.core.ui.Formatters
import com.ai.tinymodels.core.ui.components.bottomSheetTopSafePadding
import com.ai.tinymodels.domain.model.DownloadedModelFile

private const val TAG = "ModelPicker"

/**
 * Bottom sheet listing every downloaded model file as a flat list of models,
 * followed by the Hugging Face cloud models that can be used without a download.
 * Each local file is a directly selectable row (name in upper case + size), with
 * the active file highlighted; cloud models select through the same sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelFilePickerSheet(
    files: List<DownloadedModelFile>,
    activeModelId: String?,
    activeFileName: String?,
    isCloudActive: Boolean,
    cloudModels: List<CloudModel>,
    cloudModelsLoading: Boolean,
    cloudModelsError: String?,
    onRefreshCloudModels: () -> Unit,
    onSelectCloudModel: (String) -> Unit,
    onSelect: (modelId: String, fileName: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // Suppress the sheet's built-in inset handling so we control it precisely.
        contentWindowInsets = { WindowInsets(0) },
        // Keep below status bar; anchor to bottom and let it grow to fit content.
        modifier = Modifier.bottomSheetTopSafePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
        ) {
            Text(
                text = stringResource(R.string.chat_model_files_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.chat_model_files_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Always a LazyColumn so the cloud section stays reachable even when
            // no local model file has been downloaded yet.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
            ) {
                if (files.isEmpty()) {
                    item(key = "files-empty") {
                        Text(
                            text = stringResource(R.string.chat_model_files_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
                        )
                    }
                } else {
                    items(files, key = { "${it.modelId}/${it.fileName}" }) { file ->
                        ModelFileRow(
                            file = file,
                            isActive = !isCloudActive &&
                                file.modelId == activeModelId &&
                                file.fileName == activeFileName,
                            onClick = { onSelect(file.modelId, file.fileName) }
                        )
                    }
                }

                item(key = "cloud-header") {
                    CloudPickerHeader(
                        isCloudActive = isCloudActive,
                        onRefresh = onRefreshCloudModels
                    )
                }
                if (cloudModelsLoading) {
                    item(key = "cloud-loading") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                stringResource(R.string.chat_cloud_models_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                cloudModelsError?.let { message ->
                    item(key = "cloud-error") {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(onClick = onRefreshCloudModels) {
                                Text(stringResource(R.string.chat_cloud_models_retry))
                            }
                        }
                    }
                }
                if (!cloudModelsLoading && cloudModelsError == null && cloudModels.isEmpty()) {
                    item(key = "cloud-empty") {
                        Text(
                            stringResource(R.string.chat_cloud_models_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
                items(cloudModels, key = { "cloud:${it.id}" }) { model ->
                    CloudPickerRow(
                        model = model,
                        isActive = isCloudActive && model.id == activeModelId,
                        onClick = {
                            TinyLog.i(TAG, "CLOUD: picker row tapped model=${model.id}")
                            onSelectCloudModel(model.id)
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // Fetch once per sheet opening; retries are explicit via the Refresh button.
    var cloudRequested by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (CloudPickerRefreshPolicy.shouldRequest(
                alreadyRequested = cloudRequested,
                models = cloudModels,
                loading = cloudModelsLoading,
                error = cloudModelsError
            )
        ) {
            cloudRequested = true
            TinyLog.i(
                TAG,
                "CLOUD: picker opened, requesting models (cached=${cloudModels.size})"
            )
            onRefreshCloudModels()
        } else {
            TinyLog.i(
                TAG,
                "CLOUD: picker opened, no fetch needed " +
                    "(cached=${cloudModels.size} loading=$cloudModelsLoading error=$cloudModelsError)"
            )
        }
    }
}

@Composable
private fun ModelFileRow(
    file: DownloadedModelFile,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Memory,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.fileName
                        .substringAfterLast('/')
                        .removeSuffix(TinyConstants.MODEL_FILE_EXTENSION)
                        .uppercase(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = Formatters.formatBytes(file.sizeBytes, LocalContext.current.resources),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isActive) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.chat_model_active_content_description),
                    tint = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
