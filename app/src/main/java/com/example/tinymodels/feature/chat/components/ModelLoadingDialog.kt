package com.example.tinymodels.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.example.tinymodels.feature.chat.model.ModelLoadProgress

/**
 * Dialog shown while a model is loading. Displays progress bar and stage info.
 */
@Composable
fun ModelLoadingDialog(
    progress: ModelLoadProgress,
    onDismiss: (() -> Unit)? = null
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Spinner
            CircularProgressIndicator(
                modifier = Modifier
                    .size(48.dp)
                    .alpha(0.7f),
                strokeWidth = 3.dp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Model ID
            Text(
                text = progress.modelId.substringAfterLast("/"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Stage label
            Text(
                text = when (progress.stage) {
                    ModelLoadProgress.LoadStage.INITIALIZING -> "Initializing..."
                    ModelLoadProgress.LoadStage.LOADING_WEIGHTS -> "Loading weights..."
                    ModelLoadProgress.LoadStage.READY -> "Almost ready..."
                    else -> "Loading..."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { progress.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Percentage
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${(progress.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = when (progress.stage) {
                        ModelLoadProgress.LoadStage.INITIALIZING -> "Preparing engine"
                        ModelLoadProgress.LoadStage.LOADING_WEIGHTS -> "Loading model data"
                        ModelLoadProgress.LoadStage.READY -> "Finalizing"
                        else -> "Loading..."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
