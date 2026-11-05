package com.ai.tinymodels.domain.usecase.model

import java.io.File

/**
 * Canonical on-disk layout for downloaded model files.
 *
 * Shared by the worker (which writes them) and the ViewModel (which deletes a
 * paused partial). Keeping the rule in one place is what makes Discard and
 * Resume point at the same file.
 */
object DownloadPaths {

    /** `<filesDir>/models/<repo id with slashes flattened>`. */
    fun modelDirectory(filesDir: File, modelId: String): File =
        File(filesDir, "models/${modelId.replace("/", "_")}")

    /** The finished file; any repo sub-path (`onnx/…`) is dropped. */
    fun finalFile(filesDir: File, modelId: String, fileName: String): File =
        File(modelDirectory(filesDir, modelId), fileName.substringAfterLast("/"))

    /** The in-progress file, next to the final one so a rename promotes it. */
    fun partFile(filesDir: File, modelId: String, fileName: String): File =
        File(modelDirectory(filesDir, modelId), "${fileName.substringAfterLast("/")}.part")
}
