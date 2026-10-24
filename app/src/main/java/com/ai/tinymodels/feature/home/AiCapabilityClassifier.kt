package com.ai.tinymodels.feature.home

/**
 * Single source of truth for mapping total device RAM to an [AiCapability]
 * tier and the recommended maximum parameter size.
 *
 * Both the Home dashboard ([HomeViewModel]) and the Device Information screen
 * ([DeviceViewModel]) must agree on this, and low-RAM devices must still get a
 * meaningful verdict — reporting "Unknown" for a working 2 GB phone made the
 * Home hero card useless.
 */
object AiCapabilityClassifier {

    /**
     * Returns the tier for the device's total RAM (in MB) plus the
     * recommended maximum parameter size, e.g. `"3B"`.
     *
     * [AiCapability.UNKNOWN] is reserved for readings that are unusable
     * (non-positive RAM) so a real device always lands on a tier.
     */
    fun classify(totalRamMb: Long): Pair<AiCapability, String> = when {
        totalRamMb >= 12_000 -> AiCapability.EXCELLENT to "8B"
        totalRamMb >= 8_000 -> AiCapability.GOOD to "3B"
        totalRamMb >= 4_000 -> AiCapability.LIMITED to "1B"
        totalRamMb > 0 -> AiCapability.LOW to "0.5B"
        else -> AiCapability.UNKNOWN to "-"
    }
}
