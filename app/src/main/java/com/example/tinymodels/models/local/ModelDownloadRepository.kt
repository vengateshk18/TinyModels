package com.example.tinymodels.models.local

import com.example.tinymodels.models.data.ModelDetails
import com.example.tinymodels.utils.OkHttpUtil
import com.example.tinymodels.utils.TinyModelsApiConstants
import com.example.tinymodels.utils.UrlGeneratorUtil
import java.io.File

class ModelDownloadRepository(
    private val dao: DownloadedModelDao,
    private val rootDirectory: File
) {
    suspend fun calculateSize(model: ModelDetails): Long =
        model.liteRtFiles.sumOf { fileName ->
            val url = UrlGeneratorUtil.getUrl(
                TinyModelsApiConstants.MODEL_FILE,
                mapOf("modelId" to model.id, "fileName" to fileName)
            )
            OkHttpUtil.contentLength(url)
        }

    suspend fun download(
        model: ModelDetails,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val modelDirectory = File(rootDirectory, model.id.replace("/", "_"))
        var downloadedTotal = 0L
        val total = calculateSize(model)

        model.liteRtFiles.forEach { fileName ->
            val url = UrlGeneratorUtil.getUrl(
                TinyModelsApiConstants.MODEL_FILE,
                mapOf("modelId" to model.id, "fileName" to fileName)
            )
            val target = File(modelDirectory, fileName.substringAfterLast("/"))
            OkHttpUtil.download(url, target) { fileDownloaded, _ ->
                onProgress(downloadedTotal + fileDownloaded, total)
            }
            downloadedTotal += target.length()
        }

        dao.insert(
            DownloadedModelEntity(
                modelId = model.id,
                author = model.author,
                libraryName = model.libraryName,
                pipelineTag = model.pipelineTag,
                localPath = modelDirectory.absolutePath,
                files = model.liteRtFiles.joinToString("\n"),
                sizeBytes = modelDirectory.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                downloadedAt = System.currentTimeMillis()
            )
        )
    }
}
