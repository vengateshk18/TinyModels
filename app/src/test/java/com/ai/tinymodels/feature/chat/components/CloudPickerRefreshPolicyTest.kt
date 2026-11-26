package com.ai.tinymodels.feature.chat.components

import com.ai.tinymodels.core.network.cloud.CloudModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPickerRefreshPolicyTest {

    private val cached = CloudModel(id = "Qwen/Qwen3.8-27B", ownedBy = "Qwen")

    @Test
    fun `requests when the picker opens with an empty cache`() {
        assertTrue(
            CloudPickerRefreshPolicy.shouldRequest(
                alreadyRequested = false,
                models = emptyList(),
                loading = false,
                error = null
            )
        )
    }

    @Test
    fun `does not request twice in the same sheet opening`() {
        assertFalse(
            CloudPickerRefreshPolicy.shouldRequest(
                alreadyRequested = true,
                models = emptyList(),
                loading = false,
                error = null
            )
        )
    }

    @Test
    fun `does not request while a fetch is already running`() {
        assertFalse(
            CloudPickerRefreshPolicy.shouldRequest(
                alreadyRequested = false,
                models = emptyList(),
                loading = true,
                error = null
            )
        )
    }

    @Test
    fun `does not request when a previous failure is on screen`() {
        assertFalse(
            CloudPickerRefreshPolicy.shouldRequest(
                alreadyRequested = false,
                models = emptyList(),
                loading = false,
                error = "Add your Hugging Face token in Settings to use cloud models."
            )
        )
    }

    @Test
    fun `does not request when models are already cached`() {
        assertFalse(
            CloudPickerRefreshPolicy.shouldRequest(
                alreadyRequested = false,
                models = listOf(cached),
                loading = false,
                error = null
            )
        )
    }
}
