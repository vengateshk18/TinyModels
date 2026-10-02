package com.example.tinymodels.feature.home

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class DeviceInfoState(
    val deviceName: String = "",
    val totalRamMb: Long = 0,
    val availableRamMb: Long = 0,
    val freeStorageMb: Long = 0,
    val cpuCores: Int = 0,
    val arch: String = "",
    val aiCapabilityLevel: AiCapability = AiCapability.UNKNOWN,
    val recommendedMaxParams: String = "",
    val availableRamForAiMb: Long = 0
)

enum class AiCapability(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    LIMITED("Limited"),
    UNKNOWN("Unknown")
}

@HiltViewModel
class DeviceViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceInfoState())
    val state: StateFlow<DeviceInfoState> = _state.asStateFlow()

    init {
        _state.value = collectDeviceInfo()
    }

    private fun collectDeviceInfo(): DeviceInfoState {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalRamMb = mi.totalMem / (1024 * 1024)
        val availableRamMb = mi.availMem / (1024 * 1024)

        val stat = StatFs(context.filesDir.absolutePath)
        val freeStorageMb = stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)

        val cpuCores = Runtime.getRuntime().availableProcessors()
        val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val availableRamForAiMb = (totalRamMb * 0.45).toLong()

        val (capability, maxParams) = when {
            totalRamMb >= 12000 -> AiCapability.EXCELLENT to "8B"
            totalRamMb >= 8000 -> AiCapability.GOOD to "3B"
            totalRamMb >= 4000 -> AiCapability.LIMITED to "1B"
            else -> AiCapability.UNKNOWN to "-"
        }

        return DeviceInfoState(
            deviceName = deviceName,
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            freeStorageMb = freeStorageMb,
            cpuCores = cpuCores,
            arch = arch,
            aiCapabilityLevel = capability,
            recommendedMaxParams = maxParams,
            availableRamForAiMb = availableRamForAiMb
        )
    }
}
