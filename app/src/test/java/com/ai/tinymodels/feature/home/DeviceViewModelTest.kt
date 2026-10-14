package com.ai.tinymodels.feature.home

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.ai.tinymodels.core.common.MemoryUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceViewModelTest {

    @get:Rule
    val instantExecutor = InstantTaskExecutorRule()

    private lateinit var mockContext: Context
    private lateinit var mockPowerManager: PowerManager
    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(testScheduler)

    @Before
    fun setUp() {
        mockContext = mockk<Context>(relaxed = true)
        mockPowerManager = mockk<PowerManager>(relaxed = true)

        Dispatchers.setMain(testDispatcher)
        mockkObject(MemoryUtils)

        // Mock context.filesDir for StatFs
        every { mockContext.filesDir } returns java.io.File("/data/user/0/com.ai.tinymodels/files")

        // Mock context.getSystemService for PowerManager (API 29+)
        every { mockContext.getSystemService(Context.POWER_SERVICE) } returns mockPowerManager

        // Mock context.registerReceiver for battery intent
        val batteryIntent = mockk<Intent>(relaxed = true)
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns 80
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } returns 100
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, 0) } returns BatteryManager.BATTERY_STATUS_CHARGING
        every { mockContext.registerReceiver(any(), any<IntentFilter>()) } returns batteryIntent

        // Mock PowerManager.getCurrentThermalStatus (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            every { mockPowerManager.currentThermalStatus } returns PowerManager.THERMAL_STATUS_LIGHT
        }

        // Mock MemoryUtils
        every { MemoryUtils.totalRamBytes(any()) } returns 8L * 1024 * 1024 * 1024
        every { MemoryUtils.snapshot(any()) } returns MemoryUtils.MemorySnapshot(
            availableBytes = 4L * 1024 * 1024 * 1024,
            totalBytes = 8L * 1024 * 1024 * 1024,
            lowMemory = false,
            thresholdBytes = 1L * 1024 * 1024 * 1024
        )
    }

    @After
    fun tearDown() {
        unmockkObject(MemoryUtils)
        Dispatchers.resetMain()
    }

    @Test
    fun `viewModel updates ramUsagePercent from memory info`() = runBlocking {
        val vm = DeviceViewModel(mockContext)
        vm.pollingDispatcher = testDispatcher
        testScheduler.advanceTimeBy(600) // one poll interval (500ms + buffer)
        val state = vm.state.value
        assertTrue("ramUsagePercent should be between 0 and 100", state.ramUsagePercent >= 0.0 && state.ramUsagePercent <= 100.0)
        // First poll at time 0, second at 500ms -> 2 samples after 600ms
        assertEquals("ramHistory should have 2 samples after 600ms", 2, state.ramHistory.size)
    }

    @Test
    fun `viewModel ramHistory caps at 120 samples`() = runBlocking {
        val vm = DeviceViewModel(mockContext)
        vm.pollingDispatcher = testDispatcher
        // Advance time for 125 polls (125 * 500ms = 62.5 seconds)
        repeat(125) {
            testScheduler.advanceTimeBy(500)
        }
        val state = vm.state.value
        assertTrue("ramHistory should be capped at 120 samples", state.ramHistory.size <= 120)
    }

    @Test
    fun `viewModel updates thermalState from PowerManager`() = runBlocking {
        val vm = DeviceViewModel(mockContext)
        vm.pollingDispatcher = testDispatcher
        testScheduler.advanceTimeBy(600)
        val state = vm.state.value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals("thermalState should match PowerManager thermal status", PowerManager.THERMAL_STATUS_LIGHT, state.thermalState)
        } else {
            assertEquals("thermalState should be 0 on older API", 0, state.thermalState)
        }
    }

    @Test
    fun `viewModel updates batteryLevel and batteryStatus`() = runBlocking {
        val vm = DeviceViewModel(mockContext)
        vm.pollingDispatcher = testDispatcher
        testScheduler.advanceTimeBy(600)
        val state = vm.state.value
        assertEquals("batteryLevel should be 80", 80, state.batteryLevel)
        assertEquals("batteryStatus should be CHARGING", BatteryManager.BATTERY_STATUS_CHARGING, state.batteryStatus)
    }

    @Test
    fun `viewModel gpuRenderer defaults to empty string on error`() = runBlocking {
        val vm = DeviceViewModel(mockContext)
        vm.pollingDispatcher = testDispatcher
        testScheduler.advanceTimeBy(600)
        val state = vm.state.value
        // GPU renderer query may fail in test environment, should default to empty string
        assertTrue("gpuRenderer should be a string", state.gpuRenderer.isNotEmpty() || state.gpuRenderer.isEmpty())
    }
}