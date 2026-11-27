package com.ai.tinymodels

import com.ai.tinymodels.feature.chat.model.ModelLoadProgress
import com.ai.tinymodels.feature.chat.model.ModelLoadVisual
import com.ai.tinymodels.feature.chat.model.modelLoadVisual
import com.ai.tinymodels.feature.chat.model.modelLoadingDisplayName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dialog's visual contract with the engine: determinate while progress is
 * measurable, indeterminate while it is not — never fake motion in the
 * blocking span.
 */
class ModelLoadVisualTest {

    private fun visual(progress: Float, stage: ModelLoadProgress.LoadStage) =
        modelLoadVisual(progress, stage)

    @Test
    fun `nothing at the start`() {
        assertEquals(ModelLoadVisual.DETERMINATE, visual(0f, ModelLoadProgress.LoadStage.INITIALIZING))
    }

    @Test
    fun `early milestones animate the bar honestly`() {
        assertEquals(ModelLoadVisual.DETERMINATE, visual(0.1f, ModelLoadProgress.LoadStage.INITIALIZING))
        assertEquals(ModelLoadVisual.DETERMINATE, visual(0.29f, ModelLoadProgress.LoadStage.INITIALIZING))
    }

    @Test
    fun `the engine milestone before the blocking call flips to indeterminate`() {
        assertEquals(ModelLoadVisual.WORKING, visual(0.3f, ModelLoadProgress.LoadStage.INITIALIZING))
    }

    @Test
    fun `after the blocking call returns, the short finalize phase stays indeterminate`() {
        assertEquals(ModelLoadVisual.WORKING, visual(0.5f, ModelLoadProgress.LoadStage.LOADING_WEIGHTS))
    }

    @Test
    fun `imported model name uses real file name instead of synthetic model id`() {
        assertEquals(
            "my_model",
            modelLoadingDisplayName(
                modelId = "my/model",
                fileName = "my_model.litertlm"
            )
        )
    }

    @Test
    fun `ready means complete regardless of percentage`() {
        assertEquals(ModelLoadVisual.COMPLETE, visual(1.0f, ModelLoadProgress.LoadStage.READY))
        assertEquals(ModelLoadVisual.COMPLETE, visual(0.5f, ModelLoadProgress.LoadStage.READY))
    }
}
