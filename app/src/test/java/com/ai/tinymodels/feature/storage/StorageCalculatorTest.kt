package com.ai.tinymodels.feature.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageCalculatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `sumBytes returns total size of existing files`() {
        val a = temp.newFile("a.bin").apply { writeBytes(ByteArray(120)) }
        val b = temp.newFile("b.bin").apply { writeBytes(ByteArray(80)) }
        val missing = File(temp.root, "missing.bin")

        val total = StorageCalculator.sumBytes(listOf(a, b, missing))

        assertEquals(200L, total)
    }

    @Test
    fun `directorySize sums regular files recursively`() {
        val root = temp.newFolder("models")
        File(root, "x.bin").writeBytes(ByteArray(50))
        val nested = File(root, "sub").apply { mkdirs() }
        File(nested, "y.bin").writeBytes(ByteArray(30))

        assertEquals(80L, StorageCalculator.directorySize(root))
        assertEquals(0L, StorageCalculator.directorySize(File(temp.root, "nope")))
    }

    @Test
    fun `findPartFiles returns only part files under models root`() {
        val models = temp.newFolder("models")
        File(models, "a.litertlm").writeBytes(ByteArray(10))
        File(models, "a.litertlm.part").writeBytes(ByteArray(25))
        val nested = File(models, "author_model").apply { mkdirs() }
        File(nested, "b.litertlm.part").writeBytes(ByteArray(15))
        File(nested, "b.litertlm").writeBytes(ByteArray(40))

        val parts = StorageCalculator.findPartFiles(models)

        assertEquals(2, parts.size)
        assertTrue(parts.all { it.name.endsWith(".part") })
        assertEquals(40L, parts.sumOf { it.length() })
    }

    @Test
    fun `findOrphanModelFiles returns files not referenced by downloaded entries`() {
        val models = temp.newFolder("models")
        val modelDir = File(models, "author_model").apply { mkdirs() }
        val tracked = File(modelDir, "tracked.litertlm").apply { writeBytes(ByteArray(10)) }
        val orphan = File(modelDir, "orphan.litertlm").apply { writeBytes(ByteArray(20)) }
        val part = File(modelDir, "tracked.litertlm.part").apply { writeBytes(ByteArray(5)) }
        val trackedKeys = setOf(tracked.absolutePath)

        val orphans = StorageCalculator.findOrphanModelFiles(models, trackedKeys)

        assertEquals(listOf(orphan.absolutePath), orphans.map { it.absolutePath })
        assertTrue(partsExcluded(part, orphans))
    }

    private fun partsExcluded(part: File, files: List<File>): Boolean = files.none { it.absolutePath == part.absolutePath }
}
