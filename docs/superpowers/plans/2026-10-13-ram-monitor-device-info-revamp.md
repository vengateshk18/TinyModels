# Active RAM Usage Monitor & Device Info Page Revamp Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real-time animated RAM usage chart to the Device Information page with percentage display and contextual recommendations based on current available RAM. Revamp the page with additional device insights (thermal, battery, GPU, storage breakdown).

**Architecture:**
- Extend `DeviceViewModel` to poll `ActivityManager.MemoryInfo` every 500ms and expose a `StateFlow<List<RamSample>>` for the chart.
- Add a pure Compose `RamUsageChart` composable that draws a smooth animated line chart from samples (no external chart library).
- Add a `RamRecommendations` composable that computes suggestions from current available RAM.
- Update `DeviceInfoScreen` to include the new chart, percentage card, recommendations, and expanded device insights (thermal state, battery level, GPU info, storage details).

**Tech Stack:** Kotlin, Jetpack Compose, Coroutines Flow, Android `ActivityManager`/`BatteryManager`/`PowerManager`, Hilt DI.

**Spec:** This plan.

## Global Constraints

- Target SDK: 37, Min SDK: 26 (Android 8.0+)
- Use existing `MemoryUtils.MemorySnapshot` where applicable.
- No new third-party chart dependencies — draw with `Canvas`/`Path`.
- Poll interval: 500ms; chart window: last 60 seconds (120 samples).
- Recommendations derive from `availableRamMb` only (no heuristics beyond thresholds).
- All new Compose components in `feature/home/components/`.
- Unit tests for pure logic (sampling math, recommendation rules).
- Keep `DeviceInfoState` backward compatible; append new fields.

## Review Focus

| Input / Condition | Expected Behavior |
|-------------------|-------------------|
| Device with < 2 GB available RAM | Recommendations warn "Cannot load most models; close apps" and chart shows >80% usage red zone. |
| Rapid memory pressure (background app kills) | Chart updates within 500ms; percentage card reflects new `availMem` immediately. |
| Screen rotated / process recreated | Chart restarts sampling from zero; no crash, no stale data. |
| Low battery (< 15%) + thermal throttling | Battery/thermal cards show warning chips; recommendations note "Performance may be reduced". |
| First app launch (cold start) | Chart starts empty, animates first sample in; no frame drops. |

---

### Task 1: Extend DeviceInfoState with Real-Time RAM Fields

**Files:**
- Modify: `app/src/main/java/com/example/tinymodels/feature/home/DeviceViewModel.kt:15-25`
- Test: `app/src/test/java/com/example/tinymodels/feature/home/DeviceInfoStateTest.kt` (new)

**Interfaces:**
- Produces: `DeviceInfoState` with new fields `ramUsagePercent: Double`, `ramHistory: List<Long>` (available bytes samples), `thermalState: Int`, `batteryLevel: Int`, `batteryStatus: Int`, `gpuRenderer: String`.

- [ ] **Step 1: Write failing test for new state fields**

```kotlin
// DeviceInfoStateTest.kt
@Test fun `state holds real-time ram fields with defaults`() {
    val state = DeviceInfoState()
    assertEquals(0.0, state.ramUsagePercent)
    assertTrue(state.ramHistory.isEmpty())
    assertEquals(0, state.thermalState)
    assertEquals(-1, state.batteryLevel)
    assertEquals(0, state.batteryStatus)
    assertEquals("", state.gpuRenderer)
}
```

- [ ] **Step 2: Run test to verify it fails** — compilation error on missing fields.

- [ ] **Step 3: Add fields to `DeviceInfoState` data class** in `DeviceViewModel.kt:15-25` with sensible defaults.

- [ ] **Step 4: Run test to verify it passes** — `./gradlew :app:testDebugUnitTest --tests DeviceInfoStateTest`

- [ ] **Step 5: Commit** — `git add DeviceViewModel.kt DeviceInfoStateTest.kt && git commit -m "feat(device): add real-time RAM/battery/thermal fields to DeviceInfoState"`

---

### Task 2: Implement Real-Time Memory Sampling in DeviceViewModel

**Files:**
- Modify: `app/src/main/java/com/example/tinymodels/feature/home/DeviceViewModel.kt` (add polling coroutine)
- Test: `app/src/test/java/com/example/tinymodels/feature/home/DeviceViewModelTest.kt` (new)

**Interfaces:**
- Consumes: `Context`, `ActivityManager`, `BatteryManager`, `PowerManager`
- Produces: `_state` updated every 500ms with `ramUsagePercent`, `ramHistory` (capped at 120 samples), `thermalState`, `batteryLevel`, `batteryStatus`, `gpuRenderer`.

- [ ] **Step 1: Write failing test for sampling logic**

```kotlin
// DeviceViewModelTest.kt
@Test fun `viewModel updates ramUsagePercent from memory info`() = runTest {
    val vm = DeviceViewModel(mockContext)
    advanceTimeBy(600) // one poll interval
    val state = vm.state.first()
    assertTrue(state.ramUsagePercent >= 0.0 && state.ramUsagePercent <= 100.0)
    assertEquals(1, state.ramHistory.size)
}
```

- [ ] **Step 2: Run test to verify it fails** — `vm` not constructed, polling not implemented.

- [ ] **Step 3: Implement polling coroutine in `init` block:**
  - Launch coroutine on `viewModelScope` with `Dispatchers.IO`.
  - Loop every 500ms: get `MemoryInfo`, compute `percent = (1 - availMem/totalMem)*100`, append to `ramHistory` (drop oldest if > 120).
  - Read thermal via `PowerManager.getCurrentThermalStatus()`.
  - Read battery via `BatteryManager` intent.
  - Read GPU renderer via `EGL14.eglQueryString(EGL14.EGL_VENDOR)` (best-effort).
  - Update `_state.value = state.copy(...)`.

- [ ] **Step 4: Run test to verify it passes** — with mocked system services.

- [ ] **Step 5: Commit**

---

### Task 3: Create RamUsageChart Composable (Animated Line Chart)

**Files:**
- Create: `app/src/main/java/com/example/tinymodels/feature/home/components/RamUsageChart.kt`
- Test: `app/src/test/java/com/example/tinymodels/feature/home/components/RamUsageChartTest.kt` (new)

**Interfaces:**
- Consumes: `samples: List<Long>` (available bytes), `totalRam: Long`, `modifier: Modifier = Modifier`, `height: Dp = 120.dp`
- Produces: `@Composable` drawing animated path + percentage overlay.

- [ ] **Step 1: Write failing test for chart math helper**

```kotlin
// RamUsageChartTest.kt - test pure function toPoints(samples, total, width, height)
@Test fun `toPoints maps available bytes to chart coordinates`() {
    val total = 8000L * 1024 * 1024
    val samples = listOf(6000L * 1024 * 1024, 4000L * 1024 * 1024) // 25%, 50% used
    val pts = RamUsageChartKt.toPoints(samples, total, 300f, 120f)
    assertEquals(2, pts.size)
    assertEquals(75f, pts[0].y, 1f) // 25% used -> 75% from top
    assertEquals(60f, pts[1].y, 1f) // 50% used -> 60% from top
}
```

- [ ] **Step 2: Run test to verify it fails** — function not defined.

- [ ] **Step 3: Implement `toPoints` internal function and `RamUsageChart` composable:**
  - `Canvas` with `drawPath` for smooth cubic curve (`cubicTo`).
  - Animate `fraction` from 0→1 over 800ms using `animateFloatAsState`.
  - Draw gradient fill under line (green→yellow→red based on usage).
  - Overlay current percentage text (large, centered).

- [ ] **Step 4: Run test to verify it passes** — math helper only; composable not unit-testable.

- [ ] **Step 5: Commit**

---

### Task 4: Create RamRecommendations Composable

**Files:**
- Create: `app/src/main/java/com/example/tinymodels/feature/home/components/RamRecommendations.kt`
- Test: `app/src/test/java/com/example/tinymodels/feature/home/components/RamRecommendationsTest.kt` (new)

**Interfaces:**
- Consumes: `availableRamMb: Long`, `totalRamMb: Long`, `modifier: Modifier = Modifier`
- Produces: `@Composable` rendering a `Column` of recommendation chips.

- [ ] **Step 1: Write failing test for recommendation rules**

```kotlin
// RamRecommendationsTest.kt
@Test fun `recommendations for high available RAM`() {
    val recs = RamRecommendationsKt.computeRecommendations(6000, 8000)
    assertEquals("You can load 3B–7B models comfortably", recs[0])
}

@Test fun `recommendations for low available RAM`() {
    val recs = RamRecommendationsKt.computeRecommendations(500, 4000)
    assertTrue(recs.any { it.contains("Close other apps") })
}
```

- [ ] **Step 2: Run test to verify it fails** — function not defined.

- [ ] **Step 3: Implement `computeRecommendations` pure function + composable:**
  - ≥ 50% free: "You can load X models comfortably"
  - 25–50%: "Moderate space — prefer smaller models (1B–3B)"
  - 10–25%: "Limited — only tiny models (<1B) will fit"
  - < 10%: "Critical — close apps before loading any model"
  - Add thermal/battery caveats if passed.

- [ ] **Step 4: Run test to verify it passes**.

- [ ] **Step 5: Commit**

---

### Task 5: Create DeviceInsights Composable (Expanded Details)

**Files:**
- Create: `app/src/main/java/com/example/tinymodels/feature/home/components/DeviceInsights.kt`
- Test: `app/src/test/java/com/example/tinymodels/feature/home/components/DeviceInsightsTest.kt` (new)

**Interfaces:**
- Consumes: `DeviceInfoState` (all fields), `modifier: Modifier = Modifier`
- Produces: `@Composable` with cards for Thermal, Battery, GPU, Storage breakdown, CPU.

- [ ] **Step 1: Write failing test for thermal/battery label helpers**

```kotlin
@Test fun `thermal label maps correctly`() {
    assertEquals("Normal", thermalLabel(0))
    assertEquals("Light", thermalLabel(1))
    assertEquals("Moderate", thermalLabel(2))
    assertEquals("Severe", thermalLabel(3))
    assertEquals("Critical", thermalLabel(4))
    assertEquals("Unknown", thermalLabel(99))
}

@Test fun `battery status label maps correctly`() {
    assertEquals("Charging", batteryStatusLabel(2))
    assertEquals("Full", batteryStatusLabel(5))
    assertEquals("Discharging", batteryStatusLabel(3))
}
```

- [ ] **Step 2: Run test to verify it fails**.

- [ ] **Step 3: Implement helpers + composable:**
  - Thermal card with colored chip (green→red).
  - Battery card with level + status chip.
  - GPU renderer string (from EGL).
  - Storage: total/used/free with visual bar.
  - CPU: cores + architecture + max freq (from `/proc/cpuinfo`).

- [ ] **Step 4: Run test to verify it passes**.

- [ ] **Step 5: Commit**

---

### Task 6: Integrate New Components into DeviceInfoScreen

**Files:**
- Modify: `app/src/main/java/com/example/tinymodels/feature/home/DeviceInfoScreen.kt`
- Test: (UI test optional; covered by component tests)

**Interfaces:**
- Consumes: `DeviceViewModel.state` (all new fields)
- Produces: Updated screen with chart, percentage card, recommendations, insights.

- [ ] **Step 1: Add imports for new components.**

- [ ] **Step 2: Replace static Memory section with:**
  - `RamUsageChart(samples = state.ramHistory, totalRam = state.totalRamMb * 1024 * 1024)`
  - Percentage card: large text `"{state.ramUsagePercent}% used"` with color coding.
  - `RamRecommendations(availableRamMb = state.availableRamMb, totalRamMb = state.totalRamMb)`

- [ ] **Step 3: Add new `DeviceInsights(state)` section after Storage.**

- [ ] **Step 5: Commit**

---

### Task 7: Full Test Suite Verification

**Files:** (no new files)

- [ ] **Step 1: Run all unit tests** — `./gradlew :app:testDebugUnitTest --offline`
- [ ] **Step 2: Verify no regressions** — all 126+ tests pass.
- [ ] **Step 3: Commit**

---

### Task 8: Manual QA Checklist (Human Verification)

- [ ] Open Device Info page — chart animates smoothly, percentage updates.
- [ ] Background a heavy app (Chrome) — chart rises, recommendations shift.
- [ ] Rotate screen — chart restarts cleanly.
- [ ] Low battery mode — battery chip shows warning.
- [ ] Thermal throttle (run benchmark) — thermal chip escalates.

---