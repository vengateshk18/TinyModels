# Benchmark Feature — Plan

A Settings-launched benchmark screen that measures a downloaded model's real
on-device performance: **model load time, TTFT, decode speed, prefill speed,
and peak memory (PSS)** — with a fixed, documented methodology so numbers are
comparable across models and runs.

---

## UX Flow

```
Settings → "Benchmark" row → BenchmarkScreen (pushed, full-screen)
  │
  ├─ Picker state: list of downloaded models (file chips when a model has
  │   multiple files) + "How this works" methodology card + Run button
  │
  ├─ Running state: stage label ("Loading model…", "Prompt 1/2 · run 2/3"),
  │   live token counter, progress, Cancel. Screen kept on.
  │
  └─ Result state: metric cards (load time, TTFT, decode, prefill, peak PSS)
      + variance, + Copy summary + Re-run
```

---

## Fixed Methodology (the contract behind the numbers)

Encoded in code, not configurable — so every result is comparable.

| Knob | Value |
|---|---|
| Prompt set | 2 fixed prompts: **Short** (~10 tokens) and **Medium** (~120 tokens, a fixed paragraph to summarize) |
| Output cap | 256 tokens per run (counted from stream emissions; collection cancelled at cap) |
| Iterations | 3 runs per prompt — **run 1 is a warm-up and discarded**; median of runs 2–3 reported |
| Sampler | Fixed: temperature 0.7, topK 40, topP 0.95 (app defaults) |
| Backend | User's default backend setting; the backend actually used is reported |
| Context | Fresh `ConversationSession` per run (no history) — clean KV cache per iteration |
| Load time | Measured once: `unloadModel()` → `loadModel()` timed with `System.nanoTime()` |
| Screen | Kept on during the run (`view.keepScreenOn = true` — no WakeLock permission needed) |
| Threading | Engine work on `Dispatchers.IO` via `DispatcherProvider` (matches `ModelManager`) |

**Metric definitions:**

- **TTFT** = `t(first stream emission) − t(send called)`, per prompt, median of measured runs.
- **Decode speed** = `(emissions − 1) / (t(last emission) − t(first emission))` tok/s.
  LiteRT-LM streams one token per emission, so emission count is the token proxy
  (cross-checked against chars/4 in the results footnote).
- **Prefill speed** = `promptTokens / TTFT` tok/s, where promptTokens = chars/4
  (same estimate as `ConversationSession.estimateTokens`). Approximation: TTFT
  includes the first decode step; documented in the UI footnote.
- **Model load time** = one cold measurement: unload → load → Ready.
- **Peak memory** = max PSS sampled every 250 ms via
  `ActivityManager.getProcessMemoryInfo()` from before load until the last run
  ends; baseline PSS reported alongside.

---

## Architecture

```
feature/benchmark/
  BenchmarkViewModel.kt      — state machine + orchestration
  BenchmarkScreen.kt         — picker / running / result UI
  model/BenchmarkUi.kt       — UiState + result types (@Immutable)
core/inference/
  BenchmarkRunner.kt         — the measurement engine (prompt loop, timing,
                                PSS sampler); pure-ish, unit-testable math
```

**Reuse (no new abstractions):**
- `ModelManager.loadModel/unloadModel/withEngine` — same engine the chat uses.
- `ConversationSession.create/send/close` — same streaming path as chat.
- `ModelRepository.observeDownloadedModels/observeModelFiles` — model + file lists.
- `MemoryUtils` — pre-load OOM guard.
- `Formatters` — bytes/GB formatting for PSS.

**Engine-swap note:** the benchmark swaps the shared `ModelManager` engine
(atomic swap, same as switching models in chat). If a chat screen was open,
its `ConversationSession` is stale — verify ChatViewModel re-creates the
session on next use after an `engineState` change; if not, add a stale-session
check there during Slice 2.

---

## Slices

### Slice 1 — Core: `BenchmarkRunner` + result types + math

**Files:** `core/inference/BenchmarkRunner.kt`, `feature/benchmark/model/BenchmarkUi.kt`

- `PromptSpec(id, label, text)` — the two fixed prompts (constants in companion).
- `RunMetrics(promptId, runIndex, isWarmUp, ttftMs, decodeTokensPerSec, prefillTokensPerSec, outputTokens, durationMs)`.
- `PromptResult(prompt, measuredRuns, medianTtftMs, medianDecodeTps, medianPrefillTps, decodeMinTps, decodeMaxTps)`.
- `BenchmarkResult(modelId, fileName, backendUsed, loadTimeMs, baselinePssBytes, peakPssBytes, promptResults, deviceName, completedAt)`.
- `BenchmarkRunner.run(...)`:
  - `withEngine` → fresh `ConversationSession.create` per run → `send(prompt)`
    collected on IO, each emission timestamped with `System.nanoTime()`;
    cancel collection at 256 emissions; `session.close()` after each run.
  - PSS sampler: separate coroutine, `Debug.MemoryInfo.totalPss` every 250 ms,
    tracks peak; started before load, stopped after last run.
  - Emits progress via callback/Flow: `(promptIndex, runIndex, tokensSoFar)`.
- Pure math in a small `BenchmarkMath` object (median, spread, decode/prefill
  formulas) — unit-testable without an engine.

**Verification:** `BenchmarkMathTest` — median of 2 values, decode speed from
synthetic timestamps, prefill from TTFT, warm-up exclusion.

**Commit:** `Benchmark: core runner, metrics math, result types`

### Slice 2 — ViewModel: orchestration + state machine

**Files:** `feature/benchmark/BenchmarkViewModel.kt`

- Injects `ModelManager`, `ModelRepository`, `SettingsRepository`, `DispatcherProvider`, `@ApplicationContext`.
- `BenchmarkUiState`:
  - `Picker(models: List<DownloadedModelInfo>, filesByModel, selectedModelId, selectedFileName, canRun)`
  - `LoadingModel(modelId)` → `Running(promptLabel, promptIndex, runIndex, runTotal, tokensSoFar)`
  - `Completed(BenchmarkResult)` / `Failed(message)`
- `runBenchmark()`:
  1. OOM guard via `MemoryUtils.hasHeadroom` (fail fast with a clear message).
  2. `unloadModel()` → time `loadModel(file, userDefaultBackend, maxNumTokens = 2048)`.
  3. For each prompt × 3 runs: `BenchmarkRunner` measures; run 1 flagged warm-up.
  4. Aggregate medians; emit `Completed`.
- `cancel()` — cancels the job, closes session, keeps engine loaded (retry is cheap).
- Keep-screen-on driven from state (`Running` → on).

**Verification:** compile + manual: run on a downloaded model, cancel mid-run,
re-run; verify chat still works after a benchmark swapped the engine.

**Commit:** `Benchmark: ViewModel state machine + engine orchestration`

### Slice 3 — Screen + navigation + Settings entry

**Files:** `feature/benchmark/BenchmarkScreen.kt`, `feature/navigation/Routes.kt`, `feature/navigation/TinyModelsApp.kt`, `feature/settings/SettingsScreen.kt`

- `Routes.BENCHMARK = "benchmark"`; `composable(Routes.BENCHMARK) { BenchmarkScreen(onBack = { popBackStack() }) }`.
- Settings → About section: `NavigationRow("Benchmark", "Measure load time, TTFT, and tokens/sec")` above "Device information".
- `BenchmarkScreen`:
  - **Picker:** model cards (name, size, file chips when multiple files),
    methodology card ("2 prompts × 3 runs, first run discarded as warm-up,
    256-token cap, median reported"), Run button (48 dp target).
  - **Running:** stage text, per-run progress, live token count, Cancel;
    `view.keepScreenOn = true` while running.
  - **Result:** headline decode tok/s card; rows for TTFT (per prompt),
    prefill (per prompt), load time, peak PSS (GB, with baseline);
    "±min–max" spread on decode; Copy summary button (clipboard);
    Re-run button. Footnote: token proxy + methodology one-liner.

**Verification:** manual end-to-end: Settings → Benchmark → pick model → run →
results; back navigation; screen stays on during run.

**Commit:** `Benchmark: screen, route, Settings entry`

### Slice 4 — Polish + hardening

**Files:** same as Slice 3 + `ai/CHANGES.md`

- Error states: model file missing, OOM guard message, engine load failure —
  each with Retry.
- Copy-summary text format:
  ```
  TinyModels benchmark — Qwen3 0.6B (Qwen3-0.6B.litertlm, GPU)
  Load: 3.2s · TTFT: 180ms (short) / 640ms (medium)
  Decode: 14.2 tok/s (13.8–15.1) · Prefill: 55 tok/s
  Peak PSS: 1.9 GB (baseline 0.4 GB)
  ```
- Result footnote with the full methodology sentence.
- Back handler: confirm before abandoning a running benchmark.

**Verification:** full build + unit tests; fresh-install smoke run.

**Commit:** `Benchmark: error states, copy summary, back-guard`

---

## Test Plan

- **Unit:** `BenchmarkMathTest` — median/spread, decode + prefill formulas,
  warm-up exclusion, 256-cap accounting (synthetic timestamps, no engine).
- **Manual matrix:** one small model (Qwen3 0.6B) + one large (DeepSeek 1.5B);
  cancel mid-run; benchmark immediately after chatting (engine swap);
  low-storage device (OOM guard message).

## Out of Scope (future)

- Persisting benchmark history / comparing past runs (Room table).
- Charts (per-token latency distribution).
- Sharing as image; cross-device comparison.
- NPU-specific variants or per-chipset model files.

## Theme & Font Rules

All colors from `MaterialTheme.colorScheme`; all text styles from
`MaterialTheme.typography`; 48 dp minimum touch targets; edge-to-edge insets
handled like other pushed screens (`systemBars ∪ displayCutout`).
