package com.ai.tinymodels.feature.models

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import androidx.work.await
import androidx.compose.ui.res.stringResource
import com.ai.tinymodels.R
import com.ai.tinymodels.core.common.DownloadNotifier
import com.ai.tinymodels.core.ui.theme.TinyModelsTheme
import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.usecase.model.DownloadModelUseCase
import com.ai.tinymodels.domain.usecase.model.DownloadPaths
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

/**
 * Confirmation shown when the user taps **Cancel** on a download notification.
 *
 * The Cancel action is launched straight from the shade, before any code in
 * the app can run — a notification cannot host a dialog. This transparent,
 * windowless activity is the bridge: it confirms, then discards. Nothing —
 * not a byte — is thrown away until the user says so.
 */
@AndroidEntryPoint
class DiscardDownloadActivity : ComponentActivity() {

    @Inject lateinit var downloadModel: DownloadModelUseCase
    @Inject lateinit var modelRepository: ModelRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val workId = intent.getStringExtra(DownloadNotifier.EXTRA_WORK_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val modelId = intent.getStringExtra(DownloadNotifier.EXTRA_MODEL_ID)
        val fileName = intent.getStringExtra(DownloadNotifier.EXTRA_FILE_NAME)

        // Nothing to act on — never leave a stale window up.
        if (workId == null && modelId == null) {
            finish()
            return
        }

        setContent {
            TinyModelsTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AlertDialog(
                        onDismissRequest = { finish() },
                        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
                        title = { Text(stringResource(R.string.model_details_discard_title)) },
                        text = {
                            Text(
                                buildString {
                                    fileName?.let { append(it.substringAfterLast("/")) }
                                        ?: append(modelId)
                                    append("\n\n")
                                    append(stringResource(R.string.model_details_discard_body_notification))
                                }
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { discard(workId, modelId, fileName) }) {
                                Text(
                                    stringResource(R.string.model_details_discard),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { finish() }) { Text(stringResource(R.string.model_details_keep)) }
                        }
                    )
                }
            }
        }
    }

    private fun discard(workId: UUID?, modelId: String?, fileName: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Cancel first and wait for the worker to settle — its unwind
            // writes PAUSED, and we then overwrite it with NOT_DOWNLOADED.
            if (workId != null) {
                withTimeoutOrNull(5_000) {
                    WorkManager.getInstance(applicationContext)
                        .cancelWorkById(workId)
                        .await()
                }
                DownloadNotifier.cancelProgress(applicationContext, workId)
            }

            if (modelId != null) {
                if (fileName != null) {
                    downloadModel.discardPartial(modelId, fileName)
                    modelRepository.updateFileStatus(
                        modelId, fileName, FileDownloadStatus.NOT_DOWNLOADED
                    )
                } else {
                    // Whole-model job: drop every partial, reset the rows.
                    DownloadPaths.modelDirectory(filesDir, modelId)
                        .walkTopDown()
                        .filter { it.name.endsWith(".part") }
                        .forEach { it.delete() }
                    modelRepository.observeModelFiles(modelId).first()
                        .filter { it.status == FileDownloadStatus.PAUSED || it.status == FileDownloadStatus.DOWNLOADING }
                        .forEach { file ->
                            modelRepository.updateFileStatus(
                                modelId, file.fileName, FileDownloadStatus.NOT_DOWNLOADED
                            )
                        }
                }
            }

            finish()
        }
    }
}
