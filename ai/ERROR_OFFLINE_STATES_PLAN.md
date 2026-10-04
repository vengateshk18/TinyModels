# Error & No-Network States — App-Wide Implementation Plan

> **Status: ✅ IMPLEMENTED** — all phases complete, 43 unit tests passing.

> Goal: Every screen that touches the network (or fails for any reason) shows a **consistent, friendly, actionable** error state, and the app **detects offline mode proactively** instead of failing silently. Chat/inference stays fully offline-capable — offline is a *feature* there, not an error.

---

## 1. Current-State Audit

### What exists today
| Piece | Location | Status |
|---|---|---|
| `AppResult<T>` / `AppError` (Network/NotFound/Storage/Unknown) | `core/common/AppResult.kt` | ✅ Exists, but errors carry raw exception text |
| `InferenceError` (OOM, file missing, backend) | `core/common/AppResult.kt` | ✅ Chat layer handles these well |
| `SnackbarManager` | `core/common/SnackbarManager.kt` | ⚠️ Exists but unused by most screens |
| Per-screen inline error UI (text + Retry) | `ModelListScreen`, `ModelsTabScreen`, `ModelDetailsScreen` | ⚠️ Duplicated, inconsistent, no icons/offline copy |
| `BenchmarkUiState.Failed` | `feature/benchmark` | ✅ Has retry path |
| WorkManager `NetworkType.CONNECTED` constraint | `DownloadModelUseCase` | ✅ Downloads wait for network — but UI shows "downloading" forever with no hint |
| **Connectivity monitoring** | — | ❌ **None.** No `ConnectivityManager`, no `NetworkCallback`, no `ACCESS_NETWORK_STATE` permission |
| **Offline-aware UI** | — | ❌ None. Offline failures surface as raw `UnknownHostException` text |
| **Global offline banner** | — | ❌ None |
| **Friendly error copy** | — | ❌ Raw messages like `HTTP 401: Unauthorized` reach the UI |

### Key gap
`ModelRepositoryImpl` maps **every** throwable to `AppError.Network(it.message)`. The UI then prints that raw message. There is no way for a screen to distinguish *"you're offline"* from *"HF is down"* from *"this model is gated"*.

---

## 2. Architecture — New Foundation (Phase 0)

### 2.1 `NetworkMonitor` (connectivity source of truth)
**New file:** `core/network/NetworkMonitor.kt`

```kotlin
interface NetworkMonitor {
    val isOnline: StateFlow<Boolean>
}

@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext context: Context
) : NetworkMonitor {
    // ConnectivityManager + NetworkCallback (register on init, callbackFlow-free,
    // simple MutableStateFlow<Boolean> updated onAvailable/onLost + initial value
    // from activeNetwork capabilities)
}
```
- Register the callback once (singleton); survives config changes.
- Expose `StateFlow<Boolean>` (keep it simple; add `isMetered` later if needed).
- **Manifest:** add `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />`.
- **DI:** bind in `di/NetworkModule.kt` (`@Binds` in a companion interface module or provide directly).

### 2.2 Typed network errors (make `AppError.Network` actionable)
**Modify:** `core/common/AppResult.kt` — refine `AppError.Network`:

```kotlin
sealed class AppError(open val message: String?) {
    data class Network(override val message: String?) : AppError(message)
    // ↓ new sub-kinds surfaced by the mapper:
    data class NoConnection(...) : AppError(...)      // device offline
    data class Timeout(...) : AppError(...)           // socket timeout
    data class ServerError(val code: Int, ...)        // HTTP 5xx / 429
    data class Auth(...)                              // HTTP 401/403 (gated model)
    data class NotFound(...)                          // HTTP 404
    ...
}
```
*(Either extend the sealed hierarchy or add an `errorKind` enum — keep it minimal: `NoConnection`, `Auth`, `ServerError`, `NotFound`, `Unknown`.)*

### 2.3 Central error mapper (raw throwable → typed error → friendly copy)
**New file:** `core/network/NetworkErrorMapper.kt`

| Throwable / condition | Mapped error | User-facing copy |
|---|---|---|
| `UnknownHostException` | `NoConnection` | "You're offline. Check your connection and try again." |
| `ConnectException`, `SocketTimeoutException` | `Timeout` | "Connection timed out. Try again." |
| HTTP 401 / 403 | `Auth` | "This model is gated. Add a Hugging Face token in Settings." |
| HTTP 404 | `NotFound` | "Model not found. It may have been removed." |
| HTTP 429 | `ServerError` | "Too many requests. Wait a moment and retry." |
| HTTP 5xx | `ServerError` | "Hugging Face is having issues. Try again later." |

- **Modify:** `HuggingFaceApi.get()` to throw a small `HttpException(code)` instead of generic `IOException`, so the mapper can branch on status codes.
- **Modify:** `ModelRepositoryImpl` to use the mapper instead of `{ AppError.Network(it.message) }`.

### 2.4 Reusable state composables (one place, consistent everywhere)
**New file:** `core/ui/components/StateViews.kt`

| Composable | Purpose |
|---|---|
| `ErrorState(title, message, onRetry, retryLabel)` | Generic failure — icon (⚠️/`ErrorOutline`), title, message, Retry button |
| `OfflineState(onRetry)` | Wifi-off icon, "You're offline", "This needs an internet connection", Retry |
| `EmptyState(icon, title, message, actionLabel?, onAction?)` | Standardize existing "No models found" / "No conversations yet" |
| `OfflineBanner(visible)` | Thin dismissible banner: "No internet connection" (for global scaffold) |

All follow Material 3 theming, centered layout, and replace the 3 duplicated inline implementations.

### 2.5 Global offline banner
**Modify:** `feature/navigation/TinyModelsApp.kt`
- Inject/collect `NetworkMonitor.isOnline` at the root scaffold.
- Show `OfflineBanner` pinned above content when offline (app-wide awareness, including on tabs that work offline — chat users should *know* they're offline even though chat still works).

---

## 3. Screen-by-Screen Matrix — Where Each State Goes

Legend: 🔴 full offline+error treatment · 🟡 error-only (local data, works offline) · 🟢 no change

| # | Screen | Network dependency | Offline state | Error state | Notes |
|---|---|---|---|---|---|
| 1 | **Models tab → Browse** (`ModelsTabScreen` + `ModelListViewModel`) | HF catalog API | ✅ `OfflineState` + auto-refresh on reconnect | ✅ `ErrorState` w/ retry, friendly copy | Primary target |
| 2 | **ModelListScreen** (pushed) | HF catalog API | ✅ Same as #1 | ✅ Same | Shares ViewModel logic |
| 3 | **ModelDetailsScreen** (`ModelDetailsViewModel`) | HF details API + HEAD size calls | ✅ `OfflineState` on load fail; "waiting for connection" on download tap | ✅ `ErrorState` w/ retry; auth error → "Add token" CTA to Settings | Also: file sizes silently fail offline → show "—" instead of 0 B |
| 4 | **HomeScreen — FirstTime** (`HomeViewModel`) | Recommended-model download | ✅ Inline offline message on download tap ("Download will start when you're back online") | ✅ Existing snackbar error → typed copy + Retry action | Dashboard branch is local-only |
| 5 | **Download flows** (`DownloadModelUseCase.observe`) | WorkManager + network constraint | ✅ Map `ENQUEUED` (constraint-blocked) → `CHECKING_SIZE` with **"Waiting for connection…"** label instead of fake progress | ✅ FAILED already surfaces error → friendly copy | Key UX fix: no more silent infinite "downloading" |
| 6 | **ChatTabScreen** | None (Room) | ➖ Works offline (by design) | 🟡 Keep snackbar for "download a model first" | Global banner covers awareness |
| 7 | **ChatRoomScreen / ChatViewModel** | None (local inference) | ➖ Works offline — this is the product's pitch | 🟡 `ChatError` already good; ensure OOM/file-missing copy stays | No offline state needed |
| 8 | **Downloaded tab** (`DownloadedModelsScreen`) | None (Room) | ➖ Works offline | 🟡 Empty state → use shared `EmptyState` | No error path today (Room won't throw) |
| 9 | **DownloadedFileDetailScreen** | None (Room + disk) | ➖ | 🟡 "File not found on disk" → shared `ErrorState` w/ re-download CTA | |
| 10 | **BenchmarkScreen** | None (local) | ➖ | 🟡 `Failed` state → friendly copy (OOM guidance already good) | |
| 11 | **SettingsScreen** | None (DataStore) | ➖ | 🟡 Token save already has feedback; no change needed | |
| 12 | **DeviceInfoScreen** | None | ➖ | 🟢 | |

---

## 4. Implementation Phases

### Phase 0 — Foundation (no UI changes yet)
1. `AndroidManifest.xml`: add `ACCESS_NETWORK_STATE`.
2. Create `core/network/NetworkMonitor.kt` (+ impl) and bind in `di/NetworkModule.kt`.
3. Refine `AppError` sub-kinds in `core/common/AppResult.kt`.
4. Create `core/network/NetworkErrorMapper.kt`; make `HuggingFaceApi` throw `HttpException(code)`; wire mapper into `ModelRepositoryImpl`.
5. Create `core/ui/components/StateViews.kt` (`ErrorState`, `OfflineState`, `EmptyState`, `OfflineBanner`).

### Phase 1 — Catalog screens (highest traffic)
6. `ModelListViewModel`: inject `NetworkMonitor`; on fetch failure choose offline vs server error; **auto-refresh when connectivity returns** (combine `isOnline` + error state); expose `isOffline` flag or typed error in `UiState`.
7. `ModelsTabScreen.BrowseContent` + `ModelListScreen`: replace inline error columns with `OfflineState` / `ErrorState`; keep search/filter UI visible above the state view (don't blank the whole screen when a retry is possible).

### Phase 2 — Details & downloads
8. `ModelDetailsViewModel`: same offline-aware load handling as #6; skip/flag file-size calculation when offline (`fileSizes` → show "—"); on download tap while offline → set `DownloadState(CHECKING_SIZE, "Waiting for connection…")` (WorkManager already queues — just label it honestly).
9. `ModelDetailsScreen`: use shared state components; auth error → button "Add Hugging Face token" navigating to Settings.
10. `DownloadModelUseCase.observe()`: map `ENQUEUED` → waiting-for-network state (distinct from active download) so all download bars (details screen, home) show "Waiting for connection…".
11. `HomeViewModel.downloadRecommended`: typed error copy + retry; offline tap → same "will start when online" messaging.

### Phase 3 — Global banner + polish
12. `TinyModelsApp` root scaffold: collect `isOnline`, render `OfflineBanner`.
13. Standardize empty states (`DownloadedModelsScreen`, `ChatTabScreen`) on shared `EmptyState`.
14. `DownloadedFileDetailScreen` / `BenchmarkScreen`: route existing error strings through shared components.

### Phase 4 — Testing & verification
15. Unit tests: `NetworkErrorMapper` (throwable → error kind → copy), `ModelListViewModel` offline/auto-retry behavior (fake `NetworkMonitor`), `DownloadModelUseCase` ENQUEUED mapping.
16. Manual matrix: airplane-mode on each tab — Browse (offline state), Details (offline state), download tap (waiting message), Chat (works + banner), Home FirstTime (waiting message), Settings (works + banner).
17. Reconnect test: error state auto-recovers on Browse tab without tapping Retry.

---

## 5. Files Touched (summary)

**New (5):**
- `core/network/NetworkMonitor.kt`
- `core/network/NetworkErrorMapper.kt`
- `core/ui/components/StateViews.kt`
- (tests) `NetworkErrorMapperTest.kt`, `ModelListViewModelTest.kt` additions

**Modified (12):**
- `AndroidManifest.xml` — permission
- `di/NetworkModule.kt` — bind monitor
- `core/common/AppResult.kt` — error sub-kinds
- `core/network/HuggingFaceApi.kt` — typed HTTP exception
- `data/repository/ModelRepositoryImpl.kt` — use mapper
- `domain/usecase/model/DownloadModelUseCase.kt` — ENQUEUED → waiting state
- `feature/models/ModelListViewModel.kt` — offline-aware + auto-retry
- `feature/models/ModelDetailsViewModel.kt` — offline-aware + size fallback
- `feature/models/screens/ModelsTabScreen.kt`, `ModelListScreen.kt`, `ModelDetailsScreen.kt` — shared components
- `feature/home/HomeViewModel.kt` (+ `HomeScreen.kt` download section) — typed download errors
- `feature/navigation/TinyModelsApp.kt` — global banner
- `feature/models/screens/DownloadedModelsScreen.kt`, `feature/chat/ChatTabScreen.kt` — shared `EmptyState`

**Explicitly out of scope:** chat/inference offline states (works offline by design), retry/backoff policies for the API layer, caching catalog responses for offline browsing (future enhancement), metered-network warnings.

---

## 6. Implementation Record (what actually landed)

| Phase | Delivered |
|---|---|
| 0 — Foundation | `ACCESS_NETWORK_STATE` permission; `NetworkMonitor`/`ConnectivityMonitor` (+DI); `AppError` sub-kinds (`NoConnection`/`Timeout`/`Auth`/`ServerError`); `HttpException` in `HuggingFaceApi`; `NetworkErrorMapper` wired into `ModelRepositoryImpl`; `StateViews.kt` (`ErrorState`/`OfflineState`/`EmptyState`/`OfflineBanner`) |
| 1 — Catalog | `ModelListViewModel` fail-fast offline + auto-retry on reconnect; `ModelsTabScreen` + `ModelListScreen` use shared state views |
| 2 — Details & downloads | `ModelDetailsViewModel` offline-aware load + size-calc guard + auto-retry; `ModelDetailsScreen` shared views + "Add Hugging Face token" CTA; `DownloadStatus.WAITING_FOR_NETWORK` for ENQUEUED work (honest "Waiting for connection…" in all download bars); `HomeViewModel` friendly download errors; `HomeScreen` waiting label |
| 3 — Banner & polish | Global `OfflineBanner` in root scaffold; `EmptyState` in ChatTab + DownloadedModels; shared `ErrorState` in DownloadedFileDetail + Benchmark |
| 4 — Tests | `NetworkErrorMapperTest` (13 cases), `ModelListViewModelTest` (4 cases incl. offline fail-fast + auto-retry), `ModelDetailsViewModelTest` extended (offline fail-fast + auto-retry) — 43 tests green |

## 7. UX Copy (canonical strings)

| Situation | Title | Body |
|---|---|---|
| Offline list/details | "You're offline" | "Check your internet connection and try again." |
| Download queued offline | — (inline) | "Waiting for connection… download starts automatically." |
| Gated model (401/403) | "Sign-in required" | "This model is gated. Add a Hugging Face access token in Settings." |
| Server error | "Something went wrong" | "Hugging Face is having trouble. Try again in a moment." |
| Not found | "Model not found" | "It may have been removed from Hugging Face." |
| Global banner | — | "No internet connection" |