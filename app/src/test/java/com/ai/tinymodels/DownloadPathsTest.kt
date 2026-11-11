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

    @Test
    fun `deletePartials throws away only the in-progress bytes of that model`() {
        val filesDir = java.nio.file.Files.createTempDirectory("tinymodels").toFile()
        val dir = DownloadPaths.modelDirectory(filesDir, "owner/model").apply { mkdirs() }
        val partA = java.io.File(dir, "model.litertlm.part").apply { writeText("partial a") }
        val partB = java.io.File(dir, "other.litertlm.part").apply { writeText("partial b") }
        val finished = java.io.File(dir, "model.litertlm").apply { writeText("done") }
        val otherModelDir = DownloadPaths.modelDirectory(filesDir, "owner/other").apply { mkdirs() }
        val otherPart = java.io.File(otherModelDir, "m.litertlm.part").apply { writeText("x") }

        val deleted = DownloadPaths.deletePartials(filesDir, "owner/model")

        assertEquals(2, deleted)
        org.junit.Assert.assertFalse(partA.exists())
        org.junit.Assert.assertFalse(partB.exists())
        org.junit.Assert.assertTrue(finished.exists())
        org.junit.Assert.assertTrue(otherPart.exists())
    }
}
