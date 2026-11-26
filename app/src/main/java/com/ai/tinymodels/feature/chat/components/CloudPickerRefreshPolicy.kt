package com.ai.tinymodels.feature.chat.components

import com.ai.tinymodels.core.network.cloud.CloudModel

/**
 * Decides whether opening the model picker should fire a cloud-model fetch.
 *
 * Fetching is opportunistic: once per sheet opening, and only when there is
 * nothing cached to show. Any failure is surfaced to the user, who retries
 * explicitly with the Refresh button — so a failed attempt must not retrigger
 * itself on every recomposition.
 */
object CloudPickerRefreshPolicy {

    fun shouldRequest(
        alreadyRequested: Boolean,
        models: List<CloudModel>,
        loading: Boolean,
        error: String?
    ): Boolean = !alreadyRequested && models.isEmpty() && !loading && error == null
}
