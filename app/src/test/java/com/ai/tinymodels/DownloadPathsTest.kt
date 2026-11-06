package com.ai.tinymodels

import com.ai.tinymodels.domain.usecase.model.DownloadPaths
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Where a model's files live on disk. The worker and the ViewModel must agree
 * exactly, otherwise Discard deletes nothing and Resume finds no partial.
 */
class DownloadPathsTest {

    private val root = File("/data/user/0/com.ai.tinymodels/files")

    @Test
    fun `model directory flattens the repository id`() {
        val dir = DownloadPaths.modelDirectory(root, "owner/model")

        assertEquals("/data/user/0/com.ai.tinymodels/files/models/owner_model", dir.path)
    }

    @Test
    fun `final file drops any repo sub path`() {
        val file = DownloadPaths.finalFile(root, "owner/model", "onnx/model.litertlm")

        assertEquals(
            "/data/user/0/com.ai.tinymodels/files/models/owner_model/model.litertlm",
            file.path
        )
    }

    @Test
    fun `part file sits beside the final file`() {
        val file = DownloadPaths.partFile(root, "owner/model", "onnx/model.litertlm")

        assertEquals(
            "/data/user/0/com.ai.tinymodels/files/models/owner_model/model.litertlm.part",
            file.path
        )
    }
}
