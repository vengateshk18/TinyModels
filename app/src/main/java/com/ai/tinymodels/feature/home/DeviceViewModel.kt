package com.ai.tinymodels.feature.home

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.tinymodels.core.common.MemoryUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DeviceInfoState(
    val deviceName: String = "",
    val totalRamMb: Long = 0,
    val availableRamMb: Long = 0,
    val freeStorageMb: Long = 0,
    val usedStorageMb: Long = 0,
    val cpuCores: Int = 0,
    val arch: String = "",
    val aiCapabilityLevel: AiCapability = AiCapability.UNKNOWN,
    val recommendedMaxParams: String = "",
    val availableRamForAiMb: Long = 0,
    val ramUsagePercent: Double = 0.0,
    val ramHistory: List<Long> = emptyList(),
    val thermalState: Int = 0,
    val batteryLevel: Int = -1,
    val batteryStatus: Int = 0,
    val gpuRenderer: String = ""
)

enum class AiCapability(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    LIMITED("Limited"),
    LOW("Low"),
    UNKNOWN("Unknown")
}

@HiltViewModel
class DeviceViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    // Dispatcher for the polling work - can be overridden in tests
    internal var pollingDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

    private val _state = MutableStateFlow(DeviceInfoState())
    val state: StateFlow<DeviceInfoState> = _state.asStateFlow()

    init {
        // Initial device info collection
        _state.value = collectDeviceInfo()

        // Start real-time polling coroutine on Main dispatcher (testable with virtual time)
        viewModelScope.launch {
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            while (true) {
                // Do the actual work on IO dispatcher (or test dispatcher in tests)
                withContext(pollingDispatcher) {
                    updateRealTimeFields(batteryFilter)
                }
                // Delay on Main dispatcher (controlled by test virtual time)
                delay(500)
            }
        }
    }

    private fun collectDeviceInfo(): DeviceInfoState {
        val snapshot = MemoryUtils.snapshot(context)
        val totalRamMb = snapshot.totalBytes / (1024 * 1024)
        val availableRamMb = snapshot.availableBytes / (1024 * 1024)

        val stat = StatFs(context.filesDir.absolutePath)
        val totalStorageBytes = stat.blockCountLong * stat.blockSizeLong
        val freeStorageBytes = stat.availableBlocksLong * stat.blockSizeLong
        val usedStorageBytes = totalStorageBytes - freeStorageBytes
        val freeStorageMb = freeStorageBytes / (1024 * 1024)
        val usedStorageMb = usedStorageBytes / (1024 * 1024)

        val cpuCores = Runtime.getRuntime().availableProcessors()
        val arch = Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown"
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val availableRamForAiMb = (totalRamMb * 0.45).toLong()

        val (capability, maxParams) = AiCapabilityClassifier.classify(totalRamMb)

        return DeviceInfoState(
            deviceName = deviceName,
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            freeStorageMb = freeStorageMb,
            usedStorageMb = usedStorageMb,
            cpuCores = cpuCores,
            arch = arch,
            aiCapabilityLevel = capability,
            recommendedMaxParams = maxParams,
            availableRamForAiMb = availableRamForAiMb
        )
    }

    private fun updateRealTimeFields(batteryFilter: IntentFilter) {
        val currentState = _state.value
        val snapshot = MemoryUtils.snapshot(context)

        // RAM usage percent = (1 - availMem / totalMem) * 100
        val totalMem = snapshot.totalBytes.toDouble()
        val availMem = snapshot.availableBytes.toDouble()
        val ramUsagePercent = if (totalMem > 0) (1.0 - availMem / totalMem) * 100.0 else 0.0

        // RAM history - store available bytes, capped at 120 samples (60 seconds at 500ms)
        val newRamHistory = (currentState.ramHistory + snapshot.availableBytes).takeLast(120)

        // Storage - update periodically
        val stat = StatFs(context.filesDir.absolutePath)
        val totalStorageBytes = stat.blockCountLong * stat.blockSizeLong
        val freeStorageBytes = stat.availableBlocksLong * stat.blockSizeLong
        val usedStorageBytes = totalStorageBytes - freeStorageBytes
        val freeStorageMb = freeStorageBytes / (1024 * 1024)
        val usedStorageMb = usedStorageBytes / (1024 * 1024)

        // Thermal state via PowerManager (API 29+)
        var thermalState = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            thermalState = powerManager.currentThermalStatus
        }

        // Battery level and status via BatteryManager
        var batteryLevel = -1
        var batteryStatus = 0
        val batteryIntent = context.registerReceiver(null, batteryFilter)
        if (batteryIntent != null) {
            batteryLevel = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (batteryLevel >= 0 && scale > 0) {
                batteryLevel = (batteryLevel * 100) / scale
            }
            batteryStatus = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
        }

        // GPU renderer via EGL14 (best-effort)
        var gpuRenderer = ""
        try {
            val egl = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            val major = IntArray(1)
            val minor = IntArray(1)
            if (android.opengl.EGL14.eglInitialize(egl, major, 0, minor, 0)) {
                val vendor = android.opengl.EGL14.eglQueryString(egl, android.opengl.EGL14.EGL_VENDOR)
                gpuRenderer = vendor ?: ""
                android.opengl.EGL14.eglTerminate(egl)
            }
        } catch (e: Exception) {
            gpuRenderer = ""
        }

        _state.value = currentState.copy(
            availableRamMb = snapshot.availableBytes / (1024 * 1024),
            ramUsagePercent = ramUsagePercent,
            ramHistory = newRamHistory,
            freeStorageMb = freeStorageMb,
            usedStorageMb = usedStorageMb,
            thermalState = thermalState,
            batteryLevel = batteryLevel,
            batteryStatus = batteryStatus,
            gpuRenderer = gpuRenderer
        )
    }
}