package com.ai.tinymodels.feature.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.tinymodels.R
import com.ai.tinymodels.core.common.TinyConstants
import com.ai.tinymodels.core.network.cloud.CloudModel
import com.ai.tinymodels.core.ui.components.bottomSheetTopSafePadding
import com.ai.tinymodels.domain.model.DownloadedModel
import com.ai.tinymodels.feature.chat.model.ModelChipState

/** Compact pill in the top bar showing the active model + loading state. */
@Composable
fun ModelChip(
    state: ModelChipState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (state) {
                is ModelChipState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.chat_model_loading), style = MaterialTheme.typography.labelMedium)
                }
                is ModelChipState.Ready -> {
                    Icon(
                        if (state.isCloud) Icons.Filled.Cloud else Icons.Filled.Memory,
                        contentDescription = if (state.isCloud) {
                            stringResource(R.string.chat_cloud_badge_content_description)
                        } else {
                            null
                        },
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        // Prefer the specific loaded file (extension stripped) — it is
                        // the most precise identifier of what's in memory.
                        text = state.fileName
                            ?.substringAfterLast('/')
                            ?.removeSuffix(TinyConstants.MODEL_FILE_EXTENSION)
                            ?: state.modelId.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                is ModelChipState.Error -> {
                    Text(stringResource(R.string.chat_model_error), style = MaterialTheme.typography.labelMedium)
                }
                ModelChipState.NotSelected -> {
                    Text(stringResource(R.string.chat_model_select), style = MaterialTheme.typography.labelMedium)
                }
            }
            // Dropdown affordance — signals the chip opens the model picker.
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(R.string.chat_model_choose_content_description),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Bottom-sheet picker listing downloaded models with their size + tag. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    models: List<DownloadedModel>,
    activeModelId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    cloudModels: List<CloudModel>,
    cloudModelsLoading: Boolean,
    cloudModelsError: String?,
    isCloudActive: Boolean,
    onRefreshCloudModels: () -> Unit,
    onSelectCloudModel: (String) -> Unit
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
                .padding(horizontal = 16.dp)
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
        ) {
            Text(
                stringResource(R.string.chat_model_choose_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            ) {
                items(models, key = { it.modelId }) { model ->
                    ModelPickerRow(
                        model = model,
                        isActive = !isCloudActive && model.modelId == activeModelId,
                        onClick = { onSelect(model.modelId) }
                    )
                    HorizontalDivider()
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
                        onClick = { onSelectCloudModel(model.id) }
                    )
                    HorizontalDivider()
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Fetch once when the sheet opens with an empty cache; retries are explicit.
    LaunchedEffect(Unit) {
        if (cloudModels.isEmpty() && !cloudModelsLoading && cloudModelsError == null) {
            onRefreshCloudModels()
        }
    }
}

@Composable
private fun CloudPickerHeader(isCloudActive: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Cloud,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (isCloudActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.chat_cloud_models_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.chat_cloud_models_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onRefresh) {
            Text(stringResource(R.string.chat_cloud_models_refresh))
        }
    }
}

@Composable
private fun CloudPickerRow(model: CloudModel, isActive: Boolean, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Cloud,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.id.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = model.ownedBy ?: model.id.substringBefore('/'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isActive) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.chat_model_active_content_description),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ModelPickerRow(
    model: DownloadedModel,
    isActive: Boolean,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.modelId,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.pipelineTag?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (isActive) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.chat_model_active_content_description),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
