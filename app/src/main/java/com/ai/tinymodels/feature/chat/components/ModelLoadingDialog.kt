package com.ai.tinymodels.feature.chat.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.stringResource
import com.ai.tinymodels.R
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.tinymodels.feature.chat.model.ModelLoadProgress
import com.ai.tinymodels.feature.chat.model.ModelLoadVisual
import com.ai.tinymodels.feature.chat.model.modelLoadVisual

/**
 * Non-dismissible dialog shown while a model is initialising.
 *
 * Honesty over cosmetics: the engine reports only a few discrete milestones,
 * and there is no way to measure the long blocking span where ~95% of the
 * time is spent. So the dialog shows measured progress while it exists and
 * Material's natural indeterminate animation after it — never an invented
 * percentage. The only motion added beyond the engine's own milestones is
 * *animation* (eased transitions, crossfades), not fabricated advancement.
 *
 * The dialog is removed from the composition by the ViewModel once the
 * engine transitions to [EngineState.Ready] or [EngineState.Error].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelLoadingDialog(
    progress: ModelLoadProgress,
    /** Kept for API compatibility — always pass null (non-dismissible). */
    @Suppress("UNUSED_PARAMETER") onDismiss: (() -> Unit)? = null
) {
    // The displayed value is eased toward wherever the engine actually is, so a
    // milestone arriving never shows up as a jarring jump — the bar glides to it.
    val display by animateFloatAsState(
        targetValue = progress.progress.coerceIn(0f, 1f),
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "modelLoadDisplay"
    )
    val visual = modelLoadVisual(progress.progress, progress.stage)

    BasicAlertDialog(
        // Intentionally empty — dialog is dismissed by the ViewModel, not by
        // user interaction.
        onDismissRequest = {}
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Spinner: natural indeterminate while the engine works (constant,
                // comfortable pace — not 100ms-per-frame jumps), it simply
                // completes when the engine says so.
                when (visual) {
                    ModelLoadVisual.COMPLETE ->
                        CircularProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    else ->
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.chat_loading_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = progress.modelId.substringAfterLast("/"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Bar: measured milestone while it exists, indeterminate while the
                // engine is inside its unmeasurable blocking call, full at READY.
                when (visual) {
                    ModelLoadVisual.DETERMINATE ->
                        LinearProgressIndicator(
                            progress = { display.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ModelLoadVisual.WORKING ->
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ModelLoadVisual.COMPLETE ->
                        LinearProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Stage + percentage; the percentage is shown only while the
                // engine's measurement exists — an invented number is not helpful.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AnimatedContent(
                        targetState = progress.stage,
                        transitionSpec = {
                            fadeIn(tween(240)) togetherWith fadeOut(tween(180))
                        },
                        label = "loadStageText"
                    ) { stage ->
                        Text(
                            text = when (stage) {
                                ModelLoadProgress.LoadStage.INITIALIZING ->
                                    stringResource(R.string.chat_loading_stage_initializing)
                                ModelLoadProgress.LoadStage.LOADING_WEIGHTS ->
                                    stringResource(R.string.chat_loading_stage_weights)
                                ModelLoadProgress.LoadStage.READY ->
                                    stringResource(R.string.chat_loading_stage_ready)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (stage == ModelLoadProgress.LoadStage.READY)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AnimatedVisibility(visible = visual != ModelLoadVisual.WORKING) {
                        Text(
                            text = if (visual == ModelLoadVisual.COMPLETE) {
                                stringResource(R.string.common_percent, 100)
                            } else {
                                stringResource(R.string.common_percent, (display * 100).toInt())
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
