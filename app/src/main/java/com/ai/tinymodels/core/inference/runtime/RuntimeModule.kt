package com.ai.tinymodels.core.inference.runtime

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the model runtimes. Adding a new runtime (e.g. GGUF/llama.cpp later)
 * is one constructor parameter + one entry in the list here.
 */
@Module
@InstallIn(SingletonComponent::class)
object RuntimeModule {

    @Provides
    @Singleton
    fun runtimeRegistry(
        litertLmRuntime: LitertLmRuntime,
        mediaPipeLlmRuntime: MediaPipeLlmRuntime,
        ggufRuntime: GgufRuntime
    ): RuntimeRegistry = RuntimeRegistry(listOf(litertLmRuntime, mediaPipeLlmRuntime, ggufRuntime))
}
