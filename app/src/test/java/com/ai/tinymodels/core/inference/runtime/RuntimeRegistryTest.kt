package com.ai.tinymodels.core.inference.runtime

import com.ai.tinymodels.domain.model.ModelFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Format dispatch: each executable format resolves to exactly one runtime;
 * everything else resolves to null so ModelManager can fail fast with the
 * "not supported yet" message instead of an opaque native error.
 */
class RuntimeRegistryTest {

    private class FakeRuntime(override val format: ModelFormat) : ModelRuntime {
        override val supportsToolCalling: Boolean = false
        override val isInitialized: Boolean = false
        override fun detectVisionSupport(modelFile: File): Boolean = false
        override suspend fun initialize(request: RuntimeInitRequest) = Unit
        override fun createSession(request: SessionRequest): ChatSession = error("unused")
        override suspend fun close() = Unit
    }

    private val litert = FakeRuntime(ModelFormat.LITERTLM)
    private val mediaPipe = FakeRuntime(ModelFormat.TASK)
    private val registry = RuntimeRegistry(listOf(litert, mediaPipe))

    @Test fun `litertlm resolves to the litert runtime`() {
        assertSame(litert, registry.runtimeFor(ModelFormat.LITERTLM))
    }

    @Test fun `task resolves to the mediapipe runtime`() {
        assertSame(mediaPipe, registry.runtimeFor(ModelFormat.TASK))
    }

    @Test fun `tflite and gguf have no runtime yet`() {
        assertNull(registry.runtimeFor(ModelFormat.TFLITE))
        assertNull(registry.runtimeFor(ModelFormat.GGUF))
    }

    @Test fun `supported formats are exactly the registered ones`() {
        assertEquals(setOf(ModelFormat.LITERTLM, ModelFormat.TASK), registry.supportedFormats)
    }
}
