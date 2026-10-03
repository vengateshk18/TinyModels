# Download Notification Plan

> Goal: Surface model-download progress in the **system notification shade**,
> with a determinate progress bar, a **Cancel** action, and terminal
> (complete / failed) notifications — delivered as **thin vertical slices**,
> each committed separately.
>
> Scope chosen: **Full** (progress + complete/failed + cancel button).
>
> **Safety:** The existing download pipeline (WorkManager → `ModelDownloadWorker`
> → Room → `DownloadModelUseCase` → `ModelDetailsViewModel`/UI) must keep working
> exactly as today. All changes are **additive and backward-compatible** — no
> existing UI, state, or DB schema is removed or renamed. No Compose screen /
> theme / font changes are involved; the only new reusable code lives in `core/`
> and reuses the existing `Formatters` helper for byte sizes.

---

## 1. Current state (what exists today)

```
ModelDetailsScreen → ModelDetailsViewModel
   onDownloadClick() / onDownloadFileClick()
        │
        ▼
DownloadModelUseCase.execute() / executeFile()   (enqueues unique WorkManager job)
        │
        ▼
ModelDownloadWorker (@HiltWorker CoroutineWorker)
   ├─ createChannel()                    (channel: model_downloads, IMPORTANCE_LOW)
   ├─ setForeground(createForegroundInfo(0, total))
   └─ downloadFile() → per-chunk onProgress
          ├─ setProgress(workDataOf(...))              → UI progress
          └─ notificationManager.notify(id, notif)    → notification (every 8KB chunk!)
```

**Problems (why the notification doesn't behave):**

| # | Issue | Root cause | Impact |
|---|-------|-----------|--------|
| 1 | **Notification never appears on Android 13+** | `targetSdk = 37` but `POST_NOTIFICATIONS` is neither declared in the manifest nor requested at runtime | Worker's foreground notification is **silently suppressed** |
| 2 | **Progress updates are throttled/dropped** | `notificationManager.notify()` is called on **every 8 KB chunk** (100s of calls/sec) | System rate-limits; janky / stale progress, wasted battery |
| 3 | **No completion / failure signal** | Foreground notification just disappears when `doWork()` returns | User can't tell the download finished or failed if the app is backgrounded |
| 4 | **No tap-to-open, no Cancel** | Notification has no content `PendingIntent` and no action buttons | Dead notification; can't jump to the app or stop the download |
| 5 | **Channel created lazily in worker** | `createChannel()` only runs inside `doWork()` | Works, but channel should exist at app startup |

**Key facts:**
- `AndroidManifest.xml` already has `FOREGROUND_SERVICE` +
  `FOREGROUND_SERVICE_DATA_SYNC` and the `SystemForegroundService` — but **no**
  `POST_NOTIFICATIONS`.
- `app/build.gradle.kts`: `minSdk = 26`, `targetSdk = 37`, `compileSdk = 37`.
- `MainActivity` has **no** runtime-permission request code today.
- `ModelDownloadWorker` companion already defines `CHANNEL_ID = "model_downloads"`,
  `NOTIFICATION_ID = 1001`, and progress `KEY_*` constants used by the use case.
- Byte formatting today uses a private `formatMb()` in the worker; the shared
  `Formatters.formatBytes()` in `core/ui/` is the single source of truth we reuse.

---

## 2. Target design

```
App start
   │
   ▼
TinyModelsApp.onCreate()
   └─ DownloadNotifier.createChannel()        (channel exists before any worker)

MainActivity.onCreate()
   └─ rememberLauncherForActivityResult(RequestPermission)
        └─ request POST_NOTIFICATIONS (only when SDK_INT >= 33 and not granted)

Download runs (ModelDownloadWorker)
   ├─ setForeground(DownloadNotifier.progressForegroundInfo(...))
   └─ throttled onProgress (>= ~400 ms or >= 1% delta)
        ├─ setProgress(...)  → UI (unchanged)
        └─ DownloadNotifier.notifyProgress(...)  → system notification (progress + Cancel)

Terminal state
   ├─ success → DownloadNotifier.notifyComplete(modelName)   (auto-dismiss progress)
   ├─ failure → DownloadNotifier.notifyFailed(modelName, err)
   └─ cancel  → (no completion notification; progress dismissed)

Cancel action (from notification)
   └─ CancelDownloadReceiver (BroadcastReceiver)
        └─ WorkManager.cancelWorkById(workId)
```

### Notification behavior
- **Progress:** title = model display name, text = `"X / Y"` (via
  `Formatters.formatBytes`), determinate `setProgress(100, pct)`, `setOngoing(true)`,
  `setOnlyAlertOnce(true)`, content intent opens `MainActivity`, one **Cancel**
  action. Uses the existing `IMPORTANCE_LOW` channel (no sound/vibration).
- **Complete:** separate notification, title `"Download complete"`, auto-cancel,
  tap opens app.
- **Failed:** separate notification, title `"Download failed"`, tap opens app.
- **Notification ID:** stable per-`workId` (hash) so concurrent downloads don't
  clobber each other; the existing single `NOTIFICATION_ID` is kept as a fallback.

---

## 3. Data / schema changes

**None.** This feature touches no Room schema, no DataStore keys, and no domain
model. The only input-data addition is an optional `KEY_MODEL_NAME` string passed
to the worker (for a friendlier title). Existing keys and worker behavior are
unchanged.

---

## 4. Slices (thin vertical, each committed separately)

### Slice N1 — Manifest permission + notification channel at startup
**Goal:** Make notifications *possible* on Android 13+ and ensure the channel
exists before any worker runs. Pure plumbing; no behavior change yet.

Files:
- `app/src/main/AndroidManifest.xml`:
  - Add `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`.
- `core/common/DownloadNotifier.kt` *(new)*:
  - `object DownloadNotifier` with `const val CHANNEL_ID = "model_downloads"`
    (reuse the worker's existing channel id so we don't create a duplicate channel)
    and `fun createChannel(context: Context)` (guarded `SDK_INT >= O`,
    `IMPORTANCE_LOW`, name "Model downloads").
- `TinyModelsApp.kt`:
  - In `onCreate()`, call `DownloadNotifier.createChannel(this)`.

**Safety:** Additive. The worker still creates the same channel idempotently
(creating an existing channel is a no-op), so nothing breaks if a worker runs
first. No existing code path removed.
- **Commit:** `feat(notifications): N1 — POST_NOTIFICATIONS permission + channel at startup`

---

### Slice N2 — Runtime permission request (Android 13+)
**Goal:** Actually obtain the runtime permission so the worker's foreground
notification is allowed to show.

Files:
- `MainActivity.kt`:
  - Add `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`
    and a `LaunchedEffect` that, when `Build.VERSION.SDK_INT >= 33` and
    `ContextCompat.checkSelfPermission(POST_NOTIFICATIONS) != GRANTED`, launches
    the request.
  - No change to theme / edge-to-edge / settings logic.

**Safety:** Additive and guarded to API 33+. Denial is graceful (the app works;
notifications just stay suppressed, as today). No existing UI behavior changes.
- **Commit:** `feat(notifications): N2 — request POST_NOTIFICATIONS at runtime`

---

### Slice N3 — Throttled progress notification in the worker
**Goal:** Show a smooth determinate progress notification without flooding the
system with per-chunk `notify()` calls. This is the core of the feature.

Files:
- `core/common/DownloadNotifier.kt`:
  - Add `progressNotification(context, modelName, downloaded, total, workId)`
    building a `NotificationCompat` with:
    - small icon `R.drawable.ic_launcher_foreground` (existing resource),
    - `setContentTitle(modelName)`,
    - `setContentText("${Formatters.formatBytes(downloaded)} / ${Formatters.formatBytes(total)}")`,
    - determinate `setProgress(100, pct, total <= 0)`,
    - `setOngoing(true)`, `setOnlyAlertOnce(true)`,
    - content `PendingIntent` → `MainActivity` (opens the app on tap).
  - Add `notificationIdFor(workId)` returning a stable per-work id.
  - Add `notifyProgress(...)`, `cancel(id)` helpers around `NotificationManagerCompat`.
- `data/worker/ModelDownloadWorker.kt`:
  - Read optional `inputData.getString(KEY_MODEL_NAME)` for the title; fall back
    to the current `"Downloading model"`.
  - Throttle notification updates: track `lastNotifyMs` and `lastPct`; only call
    `notifyProgress` when `>= ~400 ms` elapsed **or** percent changed by `>= 1`.
    Always allow the final update.
  - Replace the private `formatMb()` usage in the notification with
    `Formatters.formatBytes()` (keep `formatMb` only if referenced elsewhere;
    otherwise remove it as internal cleanup).
  - Keep `setProgress(...)` (UI progress) on every chunk — unchanged.
  - Keep `setForeground(...)` at start (now built via `DownloadNotifier`).
- `domain/usecase/model/DownloadModelUseCase.kt`:
  - Add `ModelDownloadWorker.KEY_MODEL_NAME` and put the model's display name
    into the `Data.Builder()` in both `execute()` and `executeFile()`.

**Safety:** Download logic, Room writes, and the `DownloadState` flow to the UI
are untouched. Only the *notification* update cadence changes; the existing
single `NOTIFICATION_ID` foreground path is preserved as the foreground-service
requirement. Reuses existing channel + icon resources; no new theme/font code.
- **Commit:** `feat(notifications): N3 — throttled determinate progress notification`

---

### Slice N4 — Complete / failed terminal notifications
**Goal:** Tell the user the outcome when the app is backgrounded.

Files:
- `core/common/DownloadNotifier.kt`:
  - Add `completeNotification(context, modelName)` and
    `failedNotification(context, modelName, error)` — `setAutoCancel(true)`,
    content intent opens `MainActivity`, posted on a distinct id derived from the
    workId, then dismiss the progress notification for that work.
- `data/worker/ModelDownloadWorker.kt`:
  - On `Result.success()` → `notifyComplete(modelName)`.
  - On `catch (exception: Exception)` (before `Result.retry()`) →
    `notifyFailed(modelName, exception.message)`.
  - On `CancellationException` → dismiss progress, **no** completion notification
    (a cancel is not a failure), rethrow as today.

**Safety:** Additive; terminal paths already exist — we only *add* a notification
post. The retry and DB-status-update behavior is unchanged. No UI/schema change.
- **Commit:** `feat(notifications): N4 — download complete / failed notifications`

---

### Slice N5 — Cancel action on the progress notification
**Goal:** Let the user stop a download from the notification.

Files:
- `data/worker/CancelDownloadReceiver.kt` *(new)*:
  - `BroadcastReceiver` reading a workId string extra and calling
    `WorkManager.getInstance(context).cancelWorkById(UUID.fromString(workId))`.
  - Non-exported receiver.
- `app/src/main/AndroidManifest.xml`:
  - Register `CancelDownloadReceiver` (`android:exported="false"`).
- `core/common/DownloadNotifier.kt`:
  - In `progressNotification`, add `addAction(0, "Cancel", cancelPendingIntent)`
    where `cancelPendingIntent` is a broadcast `PendingIntent` carrying the workId.
- `data/worker/ModelDownloadWorker.kt`:
  - Pass its own `id` into `DownloadNotifier.progressNotification(...)` so the
    Cancel action targets the right work.

**Safety:** Additive. Tapping Cancel triggers the *same* WorkManager cancel path
the in-app Cancel button already uses, so DB status + disk cleanup behave
identically. The receiver is non-exported (in-app only). No existing flow removed.
- **Commit:** `feat(notifications): N5 — cancel action on download notification`

---

### Slice N6 — Verification + unit test
**Goal:** Prove nothing regressed and the build is green.

Files:
- `app/src/test/.../ModelDetailsViewModelTest.kt`:
  - Existing tests already mock `DownloadModelUseCase`; add a case asserting
    `execute()`/`executeFile()` still stream `DOWNLOADING → COMPLETED` (they will,
    since the use case contract is unchanged). No worker instrumentation test
    (foreground notifications need a device).
- Manual device verification (see §6).

**Safety:** Test-only slice. No production change.
- **Commit:** `test(notifications): N6 — verify download flow unchanged + build green`

---

## 5. Migration / safety summary

| Change | Migration needed? | Risk |
|--------|-------------------|------|
| `POST_NOTIFICATIONS` manifest + runtime request | **No** | Permission plumbing only; denial = current behavior |
| Channel at app startup | **No** | Same channel id; idempotent create is a no-op |
| `DownloadNotifier` helper (`core/common`) | **No** | New file; reuses existing channel + `Formatters` |
| Throttled progress notification | **No** | Only notification cadence; download + Room + UI flow unchanged |
| `KEY_MODEL_NAME` in worker input data | **No** | Optional extra key; worker falls back to old title |
| Complete / failed notifications | **No** | Additive posts on existing terminal paths |
| Cancel action + `CancelDownloadReceiver` | **No** | Uses existing WorkManager cancel path |
| DB schema / DataStore / domain model | **None** | Untouched |
| Compose theme / typography / screens | **None** | No UI-code changes; only system notifications |

**Key safety principles:**
- Every slice is independently buildable and committable (`./gradlew :app:assembleDebug`).
- The existing download pipeline and its UI progress flow are **never removed** —
  only the notification layer is added/throttled.
- The existing `model_downloads` channel (IMPORTANCE_LOW) and launcher-icon
  resources are reused — no new theme, font, or color tokens introduced.
- All permission / API-level logic is guarded (`SDK_INT >= 33`, `SDK_INT >= O`).
- Permission denial degrades gracefully to today's behavior.

---

## 6. Execution order & dependencies

```
N1 (permission + channel) ──► N2 (runtime request) ──► N3 (throttled progress)
                                                          │
                                              ┌───────────┴───────────┐
                                              ▼                       ▼
                                        N4 (complete/failed)    N5 (cancel action)
                                              └───────────┬───────────┘
                                                          ▼
                                                   N6 (verify + test)
```

N1 → N2 → N3 are sequential (each builds on the prior). N4 and N5 are independent
of each other and both depend on N3. N6 is last.

---

## 7. Verification

Each slice:
```bash
./gradlew :app:assembleDebug          # BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest      # green
```

Manual checklist (on a device, ideally API 33+):
- [ ] First launch after install prompts for the notification permission (API 33+).
- [ ] Start a model download → a progress notification appears with model name,
      `X / Y` size, and a determinate progress bar that advances smoothly (not
      flickering from per-chunk updates).
- [ ] Tapping the notification opens the app.
- [ ] Tapping **Cancel** stops the download (same result as the in-app Cancel).
- [ ] On completion, a "Download complete" notification appears and the progress
      notification is dismissed; the file shows as DOWNLOADED in the app.
- [ ] On failure (e.g. airplane mode mid-download), a "Download failed"
      notification appears; the app still marks the file FAILED and retries per
      existing logic.
- [ ] In-app download UI (progress bar, cancel, per-file status) behaves exactly
      as before — unchanged.
