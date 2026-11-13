# Implicit State Machines Audit

This document inventories the implicit state machines found in the TinyModels app and notes issues/risks observed while auditing them.

> Scope: `app/src/main/java` (and related app resources/tests where relevant). `docs/`, generated/build outputs, and native/prebuilt runtime internals are not exhaustively modeled except where they affect app state.

## What counts as an implicit state machine here

A state machine is treated as **implicit** when lifecycle/state is represented by combinations of fields, flags, nullable values, string statuses, progress/error payloads, maps, or multiple coordinated sources of truth such that:

- only some combinations are valid,
- transitions/meaning are enforced by convention rather than a single explicit state type,
- or the same logical state is split across components that must be reconciled manually.

Explicit sealed interfaces/enums are noted when they are embedded inside larger implicit aggregates.

---

## Inventory

### 1. Download lifecycle (distributed across DB, WorkManager, domain, disk, and UI)

**Files**
- `app/src/main/java/com/ai/tinymodels/core/database/entities/ModelFileEntity.kt`
- `app/src/main/java/com/ai/tinymodels/core/database/ModelFileDao.kt`
- `app/src/main/java/com/ai/tinymodels/domain/model/Model.kt` (`FileDownloadStatus`, `DownloadedModelFile`)
- `app/src/main/java/com/ai/tinymodels/data/repository/ModelRepositoryImpl.kt`
- `app/src/main/java/com/ai/tinymodels/domain/usecase/model/DownloadModelUseCase.kt` (`DownloadStatus`, `DownloadState`, `ActiveDownload`)
- `app/src/main/java/com/ai/tinymodels/domain/usecase/model/FileRowState.kt` (`FileRowPhase`, `FileRowState`, `fileRowState(...)`)
- `app/src/main/java/com/ai/tinymodels/domain/usecase/model/DownloadPaths.kt`
- `app/src/main/java/com/ai/tinymodels/data/worker/ModelDownloadWorker.kt`
- `app/src/main/java/com/ai/tinymodels/feature/models/ModelDetailsViewModel.kt`

**State representations**
- **DB/persistence (string):** `ModelFileEntity.status: String` with expected values `NOT_DOWNLOADED | DOWNLOADING | PAUSED | DOWNLOADED | FAILED`.
- **Domain enum:** `FileDownloadStatus { NOT_DOWNLOADED, DOWNLOADING, PAUSED, DOWNLOADED, FAILED }` (`DownloadedModelFile.status`, DAO/repository conversions).
- **WorkManager observation:** `WorkInfo.State` is mapped to `DownloadStatus { IDLE, CHECKING_SIZE, WAITING_FOR_NETWORK, DOWNLOADING, COMPLETED, FAILED }`.
  - `SUCCEEDED -> COMPLETED`
  - `FAILED -> FAILED (+ error)`
  - `CANCELLED -> IDLE`
  - `ENQUEUED -> WAITING_FOR_NETWORK`
  - `RUNNING/BLOCKED -> DOWNLOADING`
- **Whole-download UI state:** `DownloadState(status, progress, downloadedBytes, totalBytes, bytesPerSecond, error)` with derived `isDownloading = status in {DOWNLOADING, CHECKING_SIZE, WAITING_FOR_NETWORK}`.
- **Active jobs:** `ActiveDownload(workId, modelId, fileName?, status, progress, downloadedBytes, totalBytes, bytesPerSecond)` derived from tagged WorkManager infos.
- **Per-file render state:** `fileRowState(dbStatus, active, partialBytes, totalBytes)` combines DB status, live active download, and on-disk `.part` size into `FileRowPhase { IDLE, QUEUED, DOWNLOADING, PAUSED, DOWNLOADED, FAILED }` + progress/bytes/speed.
  - Live active download wins: `WAITING_FOR_NETWORK -> QUEUED`, else `DOWNLOADING`.
  - No active job: `DOWNLOADED -> DOWNLOADED`; `PAUSED/FAILED -> PAUSED/FAILED` with partial fraction; `NOT_DOWNLOADED` and stale `DOWNLOADING -> IDLE`.
- **Disk state:** final model files, `.part` files, and model directories (`DownloadPaths`, worker promotion/cleanup, discard/delete flows).

**Implicit transitions/invariants**
- DB status, WorkManager job state, progress data, final file presence, and `.part` presence must agree but are updated in different places/times.
- Cancellation/failure semantics differ by layer (worker keeps partials and marks `PAUSED`/`FAILED`; observer may surface `IDLE`/`FAILED`).
- `error`, `progress`, and byte counters are only meaningful for certain statuses but are not constrained by type.
- Whole-model download state (`UiState.download`) and per-file states (`fileStates`/`modelFiles`) are tracked separately.

### 2. Model details screen: loading, data, errors, files, sizes, and downloads

**Files**
- `app/src/main/java/com/ai/tinymodels/feature/models/ModelDetailsViewModel.kt`
- `app/src/main/java/com/ai/tinymodels/domain/usecase/model/FileSizeStatus.kt`

**State representations**
- `UiState(isLoading, model, error: AppError?, isDownloaded, download: DownloadState, modelFiles, fileSizes, fileSizeStatuses, fileStates, deviceRamBytes)`
- Derived/helpers: `totalSizeBytes`, `getFileStatus(...)`, `getFileSize(...)`, `fileSizeStatus(...)` (defaults to `LOADING`), `fileState(...)` (defaults to `IDLE`), `fileError(...)`, `isFileLoadable(...)`, `errorMessageRes`, `isOfflineError`
- Per-file size resolution: `FileSizeStatus { LOADING, KNOWN, UNKNOWN }` tracked independently from `fileSizes: Map<String, Long>`

**Implicit transitions/invariants**
- `isLoading`/`model`/`error` form an implicit async-result machine (loading, loaded, offline/error, retry).
- Per-file maps (`modelFiles`, `fileSizes`, `fileSizeStatuses`, `fileStates`) are expected to correspond to model runnable files but are maintained separately.
- Live download observation, per-file actions (start/resume/pause/discard), connectivity changes, and size HEAD requests mutate overlapping state.

### 3. Catalog/model list: loading, pagination, search/filter, and offline/error

**Files**
- `app/src/main/java/com/ai/tinymodels/feature/models/ModelListViewModel.kt`

**State representations**
- `UiState(isLoading, models, error: AppError?, isOffline, searchQuery, selectedFilter, hasMore, isLoadingMore)`
- Derived: `errorMessageRes`, `isOfflineError`
- Internal: `searchJob`, `fetchJob`, `pageLimit` (`CATALOG_PAGE_SIZE` increments)

**Implicit transitions/invariants**
- Initial load vs refresh vs load-more vs debounced search vs filter change are encoded by flag combinations and request cancellation ("last request wins").
- Intended exclusivity (`isLoading` vs `isLoadingMore`) and validity (`hasMore`, `pageLimit`, `models`/`error` coexistence) are convention-based.
- `isOffline` (monitor) is related to but distinct from `error == NoConnection` / `isOfflineError`.

### 4. Downloaded file detail: load phases and async actions

**Files**
- `app/src/main/java/com/ai/tinymodels/feature/models/DownloadedFileDetailViewModel.kt`

**State representations**
- `UiState(isLoading, fileInfo, error, isLoadingModel /*legacy, always false*/, loadModelError, isDeleting, isExporting, exportMessage, ggufContextTokens, mmprojPresent, isVisionProjector, ggufMetaLoading)`

**Implicit transitions/invariants**
- Two-phase loading: DB/file info resolves first (`isLoading=false`) while GGUF metadata/vision probe may continue (`ggufMetaLoading`).
- Runnable model vs projector (`isVisionProjector`) gates chat/start behavior.
- Independent action/result channels (`loadModelError`, `isDeleting`, `isExporting`, `exportMessage`) are not modeled as a single operation state.

### 5. Chat screen aggregate state (model, generation, session, compaction, tools, UI)

**Files**
- `app/src/main/java/com/ai/tinymodels/feature/chat/model/ChatUi.kt`
- `app/src/main/java/com/ai/tinymodels/feature/chat/ChatViewModel.kt`
- `app/src/main/java/com/ai/tinymodels/core/inference/ModelManager.kt` (load progress/model readiness integration)

**State representations**
- `ChatUiState(...)` includes:
  - model chip: `model: ModelChipState { NotSelected | Loading | Ready(modelId, backendLabel, fileName?) | Error(message) }` (explicit sub-state)
  - generation: `generation: GenerationState { IDLE, GENERATING }`
  - streaming/session linkage: `streamingMessageId`, `messages: List<UiChatMessage>`
  - model loading: `modelLoadProgress: ModelLoadProgress?` with `LoadStage { INITIALIZING, LOADING_WEIGHTS, READY }`
  - compaction: `isCompacting`, `compactionMessage`, `compactedSummary`, `compactionCount`
  - errors/UI: `error`, `showInferenceSettings`
  - capabilities/settings/context: `detectedContextTokens`, `contextUsage`, `webSearchEnabled`, `webSearchAvailable`, `isVisionModel`, `keepScreenOn`, `showTokenCounts`, `hasDownloadedModels`, chat selection/list fields
- Derived: `canSend`, `isModelReady`, `maxContextTokens`

**Implicit transitions/invariants**
- “Loading model”, “ready”, “load failed”, “generating”, “streaming response”, “compacting”, and “tool/vision available” are emergent combinations across multiple fields.
- `streamingMessageId`, `generation`, and the streaming assistant `UiChatMessage` must stay synchronized.
- `modelLoadProgress`, `model`, and engine/runtime readiness are related but represented separately.

### 6. Chat message lifecycle

**Files**
- `app/src/main/java/com/ai/tinymodels/feature/chat/model/ChatUi.kt`

**State representations**
- `UiChatMessage(id, isUser, text, isStreaming=false, timestamp, completedAt?, tokenCount?, imagePath?)`

**Implicit transitions/invariants**
- User vs assistant message behavior differs by convention.
- Assistant message lifecycle is implicit: placeholder/streaming (`isStreaming=true`, partial metadata) -> completed (`isStreaming=false`, `completedAt`/`tokenCount` populated as available).
- Streaming UI/merge behavior depends on coordination with `ChatUiState.streamingMessageId` and persisted Room messages.

### 7. Model manager engine lifecycle (explicit core + implicit surrounding state)

**Files**
- `app/src/main/java/com/ai/tinymodels/core/inference/ModelManager.kt`
- `app/src/main/java/com/ai/tinymodels/domain/model/Model.kt` (`LoadedModel`)
- runtime implementations under `app/src/main/java/com/ai/tinymodels/core/inference/runtime/`

**State representations**
- **Explicit:** `EngineState { Idle, Loading(modelId), Ready(model), Error(modelId, error) }`
- **Implicit/auxiliary:** internal `runtime`, `activeConfig`, mutex-guarded swap/load/unload, progress `ModelLoadStage`, backend/vision fallback retry path, derived readiness/capability properties, and background operations (`unloadInBackground`, `onTrimMemory`, `reloadWithoutVision`).

**Implicit transitions/invariants**
- `engineState`, live runtime instance, `activeConfig`, and derived capability flags must remain consistent across async load/swap/teardown paths.
- “No model loaded”, “loading”, “loaded but vision unavailable/reloaded”, and “error” are reflected in multiple places.

### 8. Home screen: split top-level state and side-channel flows

**Files**
- `app/src/main/java/com/ai/tinymodels/feature/home/model/HomeUi.kt`
- `app/src/main/java/com/ai/tinymodels/feature/home/HomeViewModel.kt`

**State representations**
- **Explicit top-level:** `HomeUiState { Loading, FirstTime(...), Dashboard(...) }`
- **Implicit side channels:** `recommendedDownload: StateFlow<DownloadState?>` and `error: StateFlow<String?>` maintained separately from `uiState`

**Implicit transitions/invariants**
- UI must interpret `uiState` together with recommendation download progress/error.
- First-time vs dashboard branching is recomputed from observed models/chat activity; recommended download observation/execution is coordinated separately.

### 9. Storage screen: top-level state plus action/message sub-states

**Files**
- `app/src/main/java/com/ai/tinymodels/feature/storage/StorageModels.kt`
- `app/src/main/java/com/ai/tinymodels/feature/storage/StorageViewModel.kt`

**State representations**
- **Explicit top-level:** `StorageUiState { Loading, Success(breakdown, actionInProgress?, message?), Error(message) }`
- **Implicit sub-states in `Success`:** idle, action in progress, action feedback (success/failure text), and post-action refreshed/stale breakdown handling

**Implicit transitions/invariants**
- `actionInProgress` is a free-form string (not a typed action) and `message` does not distinguish success vs failure by type.
- `runAction` snapshots prior success state and may restore prior breakdown if refresh fails after an action.

### 10. Benchmark flow

**Files**
- `app/src/main/java/com/ai/tinymodels/feature/benchmark/model/BenchmarkUi.kt`
- `app/src/main/java/com/ai/tinymodels/feature/benchmark/BenchmarkViewModel.kt` (behavioral orchestration)

**State representations**
- **Mostly explicit top-level:** `BenchmarkUiState { Loading, Picker(...), LoadingModel(...), Running(progress), Completed(result), Failed(message) }`
- **Implicit/progress details:** `RunProgress(phase, prompt/run indices, warm-up, overallFraction, sustainedRemainingSec)` and picker validity (`selectedFile`, `canRun`)

### 11. Widget display state

**Files**
- `app/src/main/java/com/ai/tinymodels/feature/widget/WidgetStateResolver.kt`

**State representations**
- `WidgetDisplayState { GET_STARTED, READY, CONTINUE }`
- `WidgetUiState(displayState, downloadedModelCount, lastChatTitle)` resolved from inputs

**Implicit transitions/invariants**
- Input-to-state mapping is deterministic but field relevance depends on resolved state (e.g. title/count not meaningful in all states).

### 12. In-app WebView loading

**Files**
- `app/src/main/java/com/ai/tinymodels/feature/web/InAppWebScreen.kt`

**State representations**
- Local Compose `isLoading` boolean + WebView progress callbacks (`onPageStarted`, `onPageFinished`, `onProgressChanged`)

**Implicit transitions/invariants**
- Loading/progress/content/error are approximated with a single boolean and callback ordering; no explicit error state.

### 13. App settings/preferences (value state with cross-field behavioral rules)

**Files**
- `app/src/main/java/com/ai/tinymodels/domain/model/AppSettings.kt`
- `app/src/main/java/com/ai/tinymodels/data/repository/SettingsRepositoryImpl.kt`
- `app/src/main/java/com/ai/tinymodels/feature/settings/SettingsViewModel.kt`
- `app/src/main/java/com/ai/tinymodels/core/ui/theme/Theme.kt`, `app/src/main/java/com/ai/tinymodels/core/ui/theme/AppThemePalettes.kt`

**State representations**
- `AppSettings(themeMode, appTheme, useDynamicColor, fontChoice, fontScale, defaultBackend, webSearchEnabled, wifiOnlyDownloads, autoLoadLastModel, keepScreenOn, showTokenCounts, onboardingCompleted)`
- Separate language persistence via `AppLocaleStore` / `AppLanguage`

**Implicit transitions/invariants**
- Cross-field rules are enforced by consumers (e.g. selecting `appTheme` disables dynamic color in `SettingsViewModel`, dynamic color precedence in theme resolution).
- Global toggles gate other flows (web search availability, Wi-Fi-only download constraints, onboarding/first-run behavior, auto-load last model).

### 14. Model/file kind and capability classification

**Files**
- `app/src/main/java/com/ai/tinymodels/domain/model/Model.kt`
- `app/src/main/java/com/ai/tinymodels/data/repository/ModelRepositoryImpl.kt` (import/export/runtime behavior)

**State representations**
- Derived from filenames/extensions/metadata: `ModelFormat`, `isRunnableFile`, `isVisionProjectorFile`, `allRunnableFiles`, `ggufFiles`/`mmprojFiles`, `companionFilesFor(...)`, `hasFormat(...)`, import guards

**Implicit transitions/invariants**
- A `.gguf` may be a runnable model or a vision projector companion; “importable”, “runnable”, “loadable in chat”, and “vision-capable” are derived states, not a single explicit classification object.
- Companion pairing and projector exclusion rules are distributed across model/domain, download, import, and UI logic.

---

## Issues / risks observed in the implicit state machines

### A. Download state is fragmented and can become inconsistent
- **Multiple authorities:** DB `status` string, domain enums, WorkManager state/progress, UI-derived `DownloadState`/`FileRowState`, and filesystem (`.part`/final files) all represent overlapping truth.
- **Semantic loss/mismatch:** `WorkInfo.State.CANCELLED` is mapped to `DownloadStatus.IDLE` (`DownloadModelUseCase.observe`) while the worker attempts to mark unfinished files `PAUSED` on cancellation; transient/UI-observed state can diverge from persisted intent.
- **Stale DB states require special handling:** `fileRowState` maps DB `DOWNLOADING` (with no active job) to `IDLE`, indicating stale-status risk is known; other code paths that read raw `FileDownloadStatus`/DB strings may not apply the same reconciliation.
- **Stringly-typed persistence:** `ModelFileEntity.status` is stored as `String`; conversions rely on `enum valueOf` with fallback to `NOT_DOWNLOADED`, which can hide invalid/corrupt values.
- **Dual tracking:** whole-model `download: DownloadState` and per-file `fileStates/modelFiles` in model details can disagree (e.g. per-file actions vs whole-model enqueue/cancel flow).

### B. Aggregate UI states encode orthogonal concerns with unenforced invariants
- **`ChatUiState`** combines model readiness, load progress, generation, streaming message identity, compaction, errors, settings sheet visibility, tool/vision flags, and chat/session data. Invalid or ambiguous combinations are possible if update paths drift (e.g. streaming/generation/message linkage, load progress vs chip state).
- **`ModelDetailsViewModel.UiState`** combines screen loading/error, per-file DB records, live job-derived row states, file size maps/status maps, and whole-download state; map/default behavior (`LOADING`/`IDLE`) can mask missing updates.
- **`DownloadedFileDetailViewModel.UiState`** contains legacy/parallel fields (`isLoadingModel` always false, separate `error`/`loadModelError`/`exportMessage`) and two-phase metadata loading, increasing state-surface complexity.
- **`ModelListViewModel.UiState`** relies on conventions for `isLoading` vs `isLoadingMore`, offline vs error representation, and stale-response prevention via job cancellation.

### C. Action/result sub-states are weakly typed
- **Storage:** `StorageUiState.Success.actionInProgress: String?` and `message: String?` encode operation state and outcome as untyped strings; success/failure is not distinguishable by type, and `runAction` can fall back to a stale pre-action breakdown on refresh failure.
- **Home:** recommendation download progress/error live in separate flows from `HomeUiState`, requiring implicit UI composition.

### D. File size resolution has partial retry behavior
- In `ModelDetailsViewModel`, when offline, unresolved file sizes are marked `UNKNOWN` (known sizes preserved). Reconnect auto-retry is triggered only when `uiState.error != null` (failed details load). If details loaded successfully but size resolution ran while offline, sizes may remain `UNKNOWN` without an explicit refresh/retry path.

### E. Reset flow cannot fully clear last-used model reference via current API
- `StorageViewModel.resetAppData()` resets many settings/preferences and deletes models/chats/images, but it does not clear the last-used model.
- `SettingsRepository` exposes `suspend fun setLastUsedModelId(modelId: String)` (non-null) and `getLastUsedModelId(): String?` but no clear/null API, so post-reset `lastUsedModelId` may remain stale relative to deleted model data.

### F. Per-message streaming state is duplicated across layers
- Streaming is represented by `ChatUiState.generation`, `ChatUiState.streamingMessageId`, and per-message `UiChatMessage.isStreaming`/completion metadata, plus persisted Room message updates. Correct rendering/merge behavior depends on all of these staying aligned.

### G. Runtime capability/file-kind states are derived from conventions
- Runnable/importable/loadable/vision/projector-companion states are inferred from filename extensions and naming patterns (`.gguf` vs `mmproj`/projector), format enums, and runtime detection. This is flexible but easy to desynchronize as support evolves (as seen by outdated import strings prior to this audit/fix work).

---

## Audit notes / issues encountered while checking for implicit state machines

- **Static/code audit only:** this pass was based on repository inspection (`grep`, file reads, and project structure). No on-device/emulator runtime validation was performed.
- **Worker branch test baseline is inconsistent:** during this session, several pre-existing unit tests in the current merge state did not compile due API/interface mismatches (e.g. `HomeViewModelTest`, `DownloadedModelsViewModelTest`, `DownloadedFileDetailViewModelTest`, `HuggingFaceApiUrlTest`) unless temporarily excluded; targeted tests for changes made in this session passed.
- **Native/runtime behavior is partially opaque:** GGUF streaming behavior depends on a prebuilt AAR/native engine (`gguf_lib`); app-side state handling was auditable, but native emission/batching internals could not be fully inspected from source in-repo.
- **Generated/build artifacts are noisy for text search:** `app/build` contains matching strings/classes (dex/jars/apk/caches), so source-of-truth analysis should rely on `app/src/main` and relevant tests/config, not build outputs.
- **Not every possible dynamic state was exhaustively modeled:** this inventory focuses on app-level/domain/UI/persistence state machines visible in source; framework/internal Compose/WorkManager/WebView internals and all runtime race interleavings were not formally verified.

## Suggested follow-ups (optional)

- Introduce explicit typed state models for high-risk aggregates (chat screen state, download/file-row source-of-truth, storage actions, detail/list async states).
- Add a single reconciliation layer for download status (DB + WorkManager + filesystem) and persist enum values via type-safe converters instead of raw strings.
- Make action outcomes typed (success/error/info) rather than message-only strings where UI behavior depends on them.
- Add/restore a settings API to clear `lastUsedModelId` and invoke it during full reset.
- Re-align/repair the currently broken unit tests on the `worker` merge state so full `:app:testDebugUnitTest` can be used as a reliable regression gate.