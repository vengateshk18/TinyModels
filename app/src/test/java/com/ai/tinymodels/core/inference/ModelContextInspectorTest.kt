package com.ai.tinymodels.core.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ModelContextInspectorTest {

    private fun tmp(name: String) = File.createTempFile("probe", name).apply { deleteOnExit() }

    @Test
    fun `context parsed from the file name when no metadata exists`() {
        assertEquals(1280, ModelContextInspector.detectContext(tmp("model-ekv1280.litertlm")))
        assertEquals(4096, ModelContextInspector.detectContext(tmp("qwen ctx 4096 q4.task")))
        assertEquals(2048, ModelContextInspector.detectContext(tmp("ekv2048")))
    }

    @Test
    fun `case-insensitive separators and values`() {
        assertEquals(8192, ModelContextInspector.detectContext(tmp("MODEL-EKV_8192.gguf")))
        assertEquals(512, ModelContextInspector.detectContext(tmp("ctx-512.gguf")))
    }

    @Test
    fun `out-of-range context values are rejected`() {
        assertNull(ModelContextInspector.detectContext(tmp("model-ekv12.litertlm")))
        assertNull(ModelContextInspector.detectContext(tmp("model-ekv99999999.litertlm")))
    }

    @Test
    fun `no pattern in the name yields null`() {
        assertNull(ModelContextInspector.detectContext(tmp("qwen3-0.6b-q4_k_m.litertlm")))
    }

    @Test
    fun `corrupt gguf falls back to the filename pattern`() {
        val f = tmp("model-ctx2048.gguf")
        f.writeBytes(byteArrayOf(0x00, 0x01, 0x02))
        assertEquals(2048, ModelContextInspector.detectContext(f))
    }
}
