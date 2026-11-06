package com.ai.tinymodels.feature.chat.model

/**
 * What the loading dialog should render for a [ModelLoadProgress].
 *
 * The native engine reports a small handful of honest milestones (~10%, ~30%,
 * ~50%) around one long blocking call. Everything before the last milestone we
 * can measure; the blocking span we cannot. Fabricating motion in that span
 * pretends the user is nearly done — so that span shows honest indeterminate
 * work instead, not an invented percentage.
 */
enum class ModelLoadVisual {
    /** Show a determinate indicator animating toward the real milestone. */
    DETERMINATE,

    /** Engine is in the unmeasurable blocking span — show indeterminate work. */
    WORKING,

    /** Engine finished — determinate, full. */
    COMPLETE
}

/**
 * Maps the engine's milestone stream onto what the dialog renders.
 *
 * [MODEL_LOAD_DETERMINATE_CEILING] is the milestone before the engine's long
 * blocking `initialize()` call; beyond it there is no honest pace information,
 * so the UI switches to indeterminate.
 */
fun modelLoadVisual(progress: Float, stage: ModelLoadProgress.LoadStage): ModelLoadVisual =
    when (stage) {
        ModelLoadProgress.LoadStage.READY -> ModelLoadVisual.COMPLETE
        else ->
            if (progress >= MODEL_LOAD_DETERMINATE_CEILING) ModelLoadVisual.WORKING
            else ModelLoadVisual.DETERMINATE
    }

/** The milestone at which honest measurement ends — just before the blocking call. */
const val MODEL_LOAD_DETERMINATE_CEILING: Float = 0.3f
