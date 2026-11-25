package com.ai.tinymodels.feature.models.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.tinymodels.R
import com.ai.tinymodels.core.ui.Formatters
import com.ai.tinymodels.domain.model.ModelFormat
import com.ai.tinymodels.domain.model.ModelSummary
import com.ai.tinymodels.feature.models.ModelCardFacts
import kotlin.math.absoluteValue

/**
 * One browse row: identity (name + owner), the facts that decide whether a
 * model is worth opening (format, size, quantisation, context, vision) and
 * the social/storage stats. Everything comes from [ModelCardFacts] so the
 * card stays a thin formatting layer over tested logic.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelBrowseCard(model: ModelSummary, onClick: () -> Unit) {
    val facts = remember(model) { ModelCardFacts.of(model) }
    val repoName = remember(model.modelId) { model.modelId.substringAfterLast('/') }
    val resources = LocalContext.current.resources

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RepoAvatar(repoName)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = repoName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    facts.format?.let { format ->
                        Spacer(modifier = Modifier.width(8.dp))
                        FormatBadge(format)
                    }
                }
                facts.author?.let { author ->
                    Text(
                        text = stringResource(R.string.models_by_author, author),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val parameters = ModelCardFacts.formatParameters(facts.parameterBillions)
                val context = ModelCardFacts.formatContext(facts.contextLength)
                if (parameters != null || facts.quantization != null || context != null || facts.hasVision) {
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        parameters?.let { label ->
                            SpecChip(
                                label = label,
                                description = stringResource(R.string.models_card_parameters_desc, label)
                            )
                        }
                        facts.quantization?.let { quant ->
                            SpecChip(label = quant, description = quant)
                        }
                        context?.let { label ->
                            SpecChip(
                                label = stringResource(R.string.models_card_context_chip, label),
                                description = stringResource(R.string.models_card_context_desc, label)
                            )
                        }
                        if (facts.hasVision) {
                            VisionChip()
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val downloads = Formatters.formatCount(model.downloads ?: 0)
                    Stat(
                        icon = Icons.Filled.FileDownload,
                        text = downloads,
                        description = stringResource(R.string.models_card_downloads_desc, downloads)
                    )
                    val likes = Formatters.formatCount((model.likes ?: 0).toLong())
                    Stat(
                        icon = Icons.Filled.Favorite,
                        text = likes,
                        description = stringResource(R.string.models_card_likes_desc, likes)
                    )
                    if (model.runtimeFileCount > 0) {
                        Stat(
                            icon = Icons.Filled.FolderCopy,
                            text = pluralStringResource(
                                R.plurals.models_file_count,
                                model.runtimeFileCount,
                                model.runtimeFileCount
                            ),
                            description = pluralStringResource(
                                R.plurals.models_file_count,
                                model.runtimeFileCount,
                                model.runtimeFileCount
                            )
                        )
                    }
                }
                // One date only, right-aligned under the stats — never repeated
                // next to the chips like the old card did.
                model.lastModified?.takeIf { it.isNotBlank() }?.let { date ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.models_updated_on,
                            Formatters.formatDate(date, resources)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}

/** Coloured initial tile so a list of long repo ids stays scannable. */
@Composable
private fun RepoAvatar(repoName: String) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when ((repoName.hashCode().absoluteValue) % 3) {
        0 -> scheme.primaryContainer to scheme.onPrimaryContainer
        1 -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .size(44.dp)
            // Decoration only — the repo name is already read out next to it.
            .clearAndSetSemantics { }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = repoName.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun FormatBadge(format: ModelFormat) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(percent = 50)
    ) {
        Text(
            text = formatLabel(format),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun formatLabel(format: ModelFormat): String = when (format) {
    ModelFormat.LITERTLM -> stringResource(R.string.model_format_litertlm)
    ModelFormat.GGUF -> stringResource(R.string.model_format_gguf)
    ModelFormat.TASK -> stringResource(R.string.model_format_mediapipe)
    ModelFormat.TFLITE -> stringResource(R.string.model_format_tflite)
}

/** Neutral metadata pill: size, quantisation, context window. */
@Composable
private fun SpecChip(label: String, description: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** Signals that the repo ships an mmproj — image input works. */
@Composable
private fun VisionChip() {
    val description = stringResource(R.string.models_card_vision_desc)
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Visibility,
                contentDescription = null,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = stringResource(R.string.models_card_vision_chip),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** Icon + compact count, announced as one readable phrase. */
@Composable
private fun Stat(icon: ImageVector, text: String, description: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
