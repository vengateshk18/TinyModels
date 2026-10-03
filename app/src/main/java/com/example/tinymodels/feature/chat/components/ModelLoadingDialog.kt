package com.example.tinymodels.feature.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.tinymodels.feature.chat.model.ModelLoadProgress

/**
 * Non-dismissible dialog shown while a model is initialising.
 *
 * Uses [BasicAlertDialog] so the system back-button and outside-tap are
 * consumed and cannot accidentally close the dialog mid-load.
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
                // Indeterminate spinner while progress is near zero.
                if (progress.progress < 0.05f) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { progress.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Loading model",
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

                // Linear progress bar.
                LinearProgressIndicator(
                    progress = { progress.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Stage label + percentage on same row.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = when (progress.stage) {
                            ModelLoadProgress.LoadStage.INITIALIZING -> "Initializing engine…"
                            ModelLoadProgress.LoadStage.LOADING_WEIGHTS -> "Loading weights…"
                            ModelLoadProgress.LoadStage.READY -> "Almost ready…"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${(progress.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
