# Home Screen Revamp Plan (Two-State Home: Onboarding + Dashboard)

> **Goal:** Split the Home tab into two states — a **first-time onboarding state** (hero,
> capability verdict, recommended model, 3-step strip, storage note) and a **returning-user
> dashboard state** (last-used model card with Resume/New chat, live device stats, storage
> breakdown, usage stats, tokens/sec telemetry) — branched on `hasDownloadedModels`.
>
> **Format:** Code-free slice steps. No code snippets. Each slice is independently
> implementable and committed individually.

---

## Problem Summary

### Problem 1 — One static home screen serves both new and returning users
- **Symptom:** `HomeScreen` shows the same device-info dump for a brand-new user (who needs
  to understand the app and download their first model) and a daily user (who wants to jump
  back into a chat). The only CTA is a generic "Browse Models" button at the bottom.
- **Root Cause:** `HomeScreen` has a single layout with no state branching; `DeviceViewModel`
  exposes only device specs, nothing about the user's models or chats.
- **Expected:** `hasDownloadedModels == false` → onboarding hero layout;
  `hasDownloadedModels == true` → dashboard layout. Two distinct screens, one route.

### Problem 2 — Capability shown as a spec dump, not a verdict
- **Symptom:** "AI Capability: Good / Recommended up to 3B models" plus raw RAM/storage/CPU
  rows. A new user can't tell at a glance what their phone can actually run.
- **Root Cause:** `DeviceViewModel` computes capability from total RAM thresholds only, and
  the UI lists numbers rather than a tiered verdict.
- **Expected:** A capability card that reads as a verdict — tier label + gauge + the
  concrete recommended model tier (Small/Medium/High) with sizes, so the user knows exactly
  what to download.

### Problem 3 — No recommended model with one-tap download
- **Symptom:** New users must go hunt through the Models tab to find a compatible model.
- **Root Cause:** No recommendation logic exists; the home screen has no download affordance.
- **Expected:** A "Recommended for your device" card with a pre-picked model (based on
  RAM/storage) and a single tap that starts the download via the existing
  `DownloadModelUseCase` (WorkManager) pipeline.

### Problem 4 — Returning users get no launchpad
- **Symptom:** A returning user opening the app sees device specs, not their last-used
  model or a way to resume chatting.
- **Root Cause:** Home doesn't observe `lastUsedModelId`, chat summaries, or usage stats.
- **Expected:** A last-used model card (name, quantization, context length) with
  "Resume chat" / "New chat" buttons, turning home into a launchpad.

### Problem 5 — No usage stats or performance telemetry
- **Symptom:** The app feels static; power users can't see tokens/sec or usage totals.
- **Root Cause:** No aggregation queries exist for tokens generated; per-message timing
  data exists in Room (`createdAt`/`completedAt`) but is never aggregated.
- **Expected:** Usage stats (total chats, models tried, total tokens generated) and a
  tokens/sec figure computed from the last session's messages.

---

## Recommendation Model Catalog (hardcoded, device-agnostic)

The recommendation engine uses a fixed catalog of `.litertlm` models that fit the mobile
2.5 GB cap. These are the **generic, device-agnostic** variants (no chipset-specific NPU
files). All are gated on Hugging Face — the user must accept Google's license on the model
page once; the app surfaces this clearly in the UI.

| Tier | Model | Variant | Size | HF repo | File |
|---|---|---|---|---|---|
| Small | Gemma 3 270M IT | q8 | 304 MB | `litert-community/gemma-3-270m-it` | `gemma3-270m-it-q8.litertlm` |
| Medium | Gemma 3 1B IT | int4 | 584 MB | `litert-community/Gemma3-1B-IT` | `gemma-3-1b-it-int4.litertlm` |
| High | Gemma 4 E2B IT | web/gpu | 2.01 GB | `litert-community/gemma-4-E2B-it-litert-lm` | `gemma-4-E2B-it-web.litertlm` |

**Selection rules (RAM-based, using `MemoryUtils`):**
- `availableRamForAiMb >= 6000` (or total RAM ≥ 12 GB) → **High** (Gemma 4 E2B, 2.01 GB)
- `availableRamForAiMb >= 2500` (or total RAM ≥ 6 GB) → **Medium** (Gemma 3 1B, 584 MB)
- Otherwise → **Small** (Gemma 3 270M, 304 MB)
- **Storage guard:** if free storage < model size × 1.1, drop to the next smaller tier and
  note it in the UI ("Not enough storage for the 1B model — showing the 270M instead").
- **Avoid:** `gemma-4-E2B-it.litertlm` (no suffix, 2.59 GB — exceeds cap) and all
  Gemma 3n E2B files (3+ GB — exceed cap).

---

## Slice-Based Implementation Plan

---

### Slice 1 — Two-state HomeUiState + HomeViewModel skeleton
**Files:** new `feature/home/HomeViewModel.kt`, `HomeScreen.kt`,
new `feature/home/model/HomeUi.kt`
**Commit:** `Split home screen into first-time and dashboard states`

**Steps:**
1. Create `HomeUi.kt` with a sealed interface `HomeUiState`:
   - `Loading`
   - `FirstTime(device: DeviceInfoState, recommended: RecommendedModel?, freeStorageBytes: Long)`
   - `Dashboard(device: DeviceInfoState, lastUsedModel: DownloadedModel?, lastChat: ChatSummary?, usage: UsageStats, storage: StorageBreakdown, tokensPerSecond: Float?)`
   - Supporting data classes: `RecommendedModel(tier, modelId, fileName, sizeBytes, displayName)`,
     `UsageStats(totalChats, totalTokens, modelsTried)`, `StorageBreakdown(modelBytes, freeBytes)`.
2. Create `HomeViewModel`:
   - Inject `ModelRepository`, `ChatRepository`, `SettingsRepository`, `@ApplicationContext`.
   - Expose `uiState: StateFlow<HomeUiState>`.
   - Combine `modelRepository.observeDownloadedModels()` with device info
     (reuse `DeviceViewModel`'s collection logic — extract it into a shared
     `DeviceInfoCollector` or keep the existing `DeviceViewModel` for the
     Device Info screen and duplicate the small collection block).
   - Branch: empty downloaded list → `FirstTime`; else → `Dashboard`.
3. `HomeScreen.kt`:
   - Collect `HomeViewModel.uiState`.
   - `when (state)` → `FirstTimeHome(state)` or `DashboardHome(state)` (built in later
     slices; initially just placeholder text so the branch compiles).
   - Keep the existing top bar ("TinyModels").
4. Keep `DeviceViewModel` + `DeviceInfoScreen` untouched (Settings → Device Info still works).
5. Test: fresh install (no models) → FirstTime state; download a model → state flips to
   Dashboard on next observation.

---

### Slice 2 — First-time state: hero + capability verdict + 3-step strip + storage note
**Files:** `HomeScreen.kt` (FirstTimeHome section), `HomeUi.kt`
**Commit:** `First-time home: hero, capability verdict, 3-step strip, storage note`

**Steps:**
1. **Hero section** (top of FirstTime):
   - Headline: "Run AI models entirely on your phone."
   - Sub-line: "No internet. No cloud. No data leaves your device."
   - Style: `headlineMedium` + `bodyLarge`, primary/onSurface colors, generous padding.
2. **Capability verdict card** (replaces the current spec-dump card):
   - Tier label from `AiCapability` (Excellent/Good/Limited) rendered large.
   - A simple gauge: a `LinearProgressIndicator` (or a semicircle drawn with `Canvas`)
     filled proportionally to `availableRamForAiMb / totalRamMb`.
   - Verdict line: "Your phone can comfortably run up to **{tier}** models" where tier maps
     from RAM (8B / 3B / 1B — reuse `recommendedMaxParams`).
   - Below: the three recommended tiers as a compact list (Small 304 MB / Medium 584 MB /
     High 2.01 GB) with the device's tier highlighted.
3. **3-step strip** (Download → Load → Chat offline):
   - A `Row` of three step items, each: numbered circle (1/2/3) + label + one-line caption
     ("Downloads take a few minutes", "First load can be slow", "Everything stays on device").
   - Connect with small chevron/divider icons between steps.
4. **Storage note** (contextual, one line):
   - "Models range from 300 MB to 2 GB. You have {X} GB free."
   - Uses `Formatters.formatBytes(freeStorageBytes)`.
5. Test: fresh install → hero, verdict card with gauge, 3-step strip, storage note all
   visible; no models required.

---

### Slice 3 — First-time state: "Recommended for your device" card with one-tap download
**Files:** `HomeViewModel.kt`, `HomeScreen.kt`, new `feature/home/RecommendedModels.kt`
**Commit:** `Recommended-for-your-device card with one-tap download`

**Steps:**
1. Create `RecommendedModels.kt` — a pure object with the hardcoded catalog from the table
   above (tier, modelId, fileName, sizeBytes, displayName) and a
   `pickForDevice(availableRamForAiMb, freeStorageBytes): RecommendedModel?` function
   implementing the selection rules (RAM tier → storage guard fallback).
2. `HomeViewModel`:
   - Compute `recommended` in the `FirstTime` state via `RecommendedModels.pickForDevice`.
   - Add `downloadRecommended()` handler: builds a `ModelDetails`-shaped payload for the
     picked model (id, author, pipelineTag, libraryName, runtimeFiles = [fileName]) and
     calls `DownloadModelUseCase.executeFile(model, fileName, sizeBytes)`, collecting
     progress into a `recommendedDownloadState: StateFlow<DownloadState>`.
   - Surface download progress (percentage + speed) in the state so the card can show it.
   - **Gated-repo handling:** if the download fails with 403, surface a friendly error:
     "This model requires accepting Google's license on Hugging Face first. Open the model
     page, accept the license, then retry." Include an "Open model page" action that
     launches the HF URL (`https://huggingface.co/{modelId}`) via an intent.
3. `HomeScreen` FirstTime section — the card:
   - Title: "Recommended for your device"
   - Model display name (e.g. "Gemma 3 1B IT"), variant chip (int4), size
     (`Formatters.formatBytes`), tier badge (Medium).
   - Primary button: "Download (584 MB)" → `viewModel.downloadRecommended()`.
   - While downloading: replace button with a `LinearProgressIndicator` + percentage +
     speed + a Cancel button (`DownloadModelUseCase.cancelFile`).
   - When complete: the state flips to Dashboard automatically (observed via
     `observeDownloadedModels`).
   - Secondary text button: "Browse all models" → existing `onBrowseModels`.
4. Test: fresh install → card shows the tier-appropriate model; tap Download → progress
   streams; on 403 → license error with open-page action; on success → home flips to
   Dashboard.

---

### Slice 4 — Dashboard state: last-used model card + Resume/New chat
**Files:** `HomeViewModel.kt`, `HomeScreen.kt` (DashboardHome section), `TinyModelsApp.kt`
**Commit:** `Dashboard: last-used model card with resume/new chat`

**Steps:**
1. `HomeViewModel` Dashboard state:
   - `lastUsedModel`: from `settingsRepository.getLastUsedModelId()` →
     `modelRepository.getDownloadedModel(id)` (null if deleted).
   - `lastChat`: first item of `chatRepository.observeChatSummaries()` (already sorted by
     most-recent).
2. `HomeScreen` Dashboard section — the card:
   - Model display name (file name with `.litertlm` stripped, upper-cased — consistent
     with the chat picker), quantization parsed from the file name (`int4`/`q8` patterns),
     context length from the last chat's `inferenceSettings.maxContextTokens` (or the
     default 2048).
   - Two buttons: "Resume chat" (primary — navigates to the last chat's room) and
     "New chat" (outlined — navigates to a new chat room with the last-used model as
     preference).
   - If no chats exist yet: single "Start chatting" button → new chat room.
3. Navigation wiring in `TinyModelsApp.kt`:
   - `HomeScreen` gains `onResumeChat: (String) -> Unit` and `onNewChat: (String?) -> Unit`
     callbacks; wire to `Routes.chatRoom(chatId)` and `Routes.chatRoomWithPreference(modelId)`.
4. Test: with a downloaded model + existing chats → card shows last-used model + Resume
   opens the most recent chat; New chat opens a fresh room with the model pre-selected.

---

### Slice 5 — Dashboard state: device stats + storage breakdown + usage stats
**Files:** `HomeViewModel.kt`, `HomeScreen.kt`, `ChatDao.kt`, `ChatRepository.kt`,
`ChatRepositoryImpl.kt`
**Commit:** `Dashboard: device stats, storage breakdown, usage stats`

**Steps:**
1. DAO additions (`ChatDao.kt`):
   - `SELECT COUNT(*) FROM chats` → `totalChats()`
   - `SELECT COUNT(DISTINCT modelId) FROM chats` → `modelsTried()` (chats record the model
     they were created with; distinct modelIds = models actually used)
   - `SELECT SUM(tokenCount) FROM messages` → `totalTokensGenerated()`
   - `SELECT SUM(tokenCount), MIN(createdAt), MAX(completedAt) FROM messages WHERE
     chatId = :chatId AND role = 'ASSISTANT' AND completedAt IS NOT NULL` →
     `sessionTokenStats(chatId)` (for tokens/sec)
2. Repository: add `getUsageStats(): UsageStats` and `getLastSessionTokensPerSecond():
     Float?` to `ChatRepository` + impl (tokens/sec = total assistant tokens of the last
     chat ÷ (max(completedAt) − min(createdAt)) in seconds; null when < 2 messages or
     duration ≤ 0).
3. `HomeViewModel` Dashboard state: populate `usage` (totalChats, totalTokens, modelsTried),
   `storage` (modelBytes = sum of downloaded file sizes via
   `modelRepository.observeDownloadedFiles()`; freeBytes from StatFs), and
   `tokensPerSecond`.
4. `HomeScreen` Dashboard section — three stat cards/rows:
   - **Device stats:** available RAM (`MemoryUtils.snapshot`), free storage — compact
     `InfoRow`s or a two-column stat grid.
   - **Storage breakdown:** "Models use {X} GB of {Y} GB free" + a "Manage models" link →
     `Routes.DOWNLOADED_MODELS`.
   - **Usage stats:** total chats, total tokens generated, models tried — a three-tile
     row (number large, label small). Tokens/sec shown as "Last session: ~{N} tok/s"
     when available.
5. Test: with chats/messages in DB → stats render; tokens/sec matches a manual calculation
   from the last chat's messages.

---

### Slice 6 — Polish: thermal advisory + dark theme default
**Files:** `HomeScreen.kt`, `Theme.kt` (or `SettingsRepository` default)
**Commit:** `Home polish: thermal advisory, dark theme default`

**Steps:**
1. **Thermal/battery advisory** (contextual, not permanent):
   - On the Dashboard, show a dismissible one-line advisory under the last-used model card:
     "Large models may warm your device and use more battery." with a close icon.
   - Dismissal persists for the session (a `rememberSaveable` flag) — no DB write needed.
2. **Dark theme default:**
   - Change the default `ThemeMode` in `SettingsRepository`/`AppSettings` from
     `SYSTEM`/`LIGHT` to `DARK` for fresh installs only (existing users keep their
     setting; only the default when no preference is stored changes).
3. Test: fresh install → app opens in dark theme; advisory visible once and dismissible;
   existing users' theme preference unchanged.

---

## Execution Order

```
Slice 1 (two-state skeleton)  ← everything depends on this
    │
    ▼
Slice 2 (first-time: hero + verdict + steps + storage note)
    │
    ▼
Slice 3 (first-time: recommended card + one-tap download)
    │
    ▼
Slice 4 (dashboard: last-used model + resume/new chat)
    │
    ▼
Slice 5 (dashboard: stats + storage + usage + tokens/sec)
    │
    ▼
Slice 6 (polish: advisory + dark default)
```

Slices 2–3 (first-time) and 4–5 (dashboard) are independent of each other after Slice 1 —
they can be built in parallel or in either order. Slice 6 is fully independent.

---

## Verification Checklist

### Slice 1 — Two states
- [ ] Fresh install (no models) → FirstTime layout
- [ ] ≥1 model downloaded → Dashboard layout
- [ ] State flips live when a download completes (no restart needed)

### Slice 2 — First-time content
- [ ] Hero: one-line pitch + privacy line visible above the fold
- [ ] Capability card reads as a verdict (tier + gauge + "up to {X}B models")
- [ ] 3-step strip: Download → Load → Chat offline with captions
- [ ] Storage note shows free space with real numbers

### Slice 3 — Recommended card
- [ ] Card shows the tier-appropriate model (RAM + storage guard)
- [ ] One tap starts the download; progress + speed + cancel visible
- [ ] 403 → friendly license error + "Open model page" action
- [ ] Success → home flips to Dashboard automatically

### Slice 4 — Launchpad
- [ ] Last-used model card: name, quantization, context length
- [ ] "Resume chat" opens the most recent chat room
- [ ] "New chat" opens a fresh room with the model pre-selected
- [ ] No chats yet → single "Start chatting" button

### Slice 5 — Stats
- [ ] Available RAM + free storage shown
- [ ] Storage breakdown + "Manage models" link works
- [ ] Total chats / tokens / models tried render correctly
- [ ] Tokens/sec matches manual calculation from last chat

### Slice 6 — Polish
- [ ] Advisory shown once, dismissible, doesn't return until next app launch
- [ ] Fresh install defaults to dark theme; existing users unchanged

---

## Theme & Font Rules

All changes must follow existing project conventions:
- All colours from `MaterialTheme.colorScheme` only — no hardcoded hex values.
- All text styles from `MaterialTheme.typography` only.
- Minimum touch target: 48 dp for all interactive elements.
- Support font scale up to 200% — no clipped text.
- Edge-to-edge: `WindowInsets` respected (Top + Horizontal only on tab screens; the outer
  Scaffold consumes the bottom nav-bar inset).
- Cards use tonal elevation (`surfaceContainer*` / `primaryContainer`) per M3 expressive
  style; no shadows unless specified.

---

## Commit Messages

```
Slice 1  — Split home screen into first-time and dashboard states
Slice 2  — First-time home: hero, capability verdict, 3-step strip, storage note
Slice 3  — Recommended-for-your-device card with one-tap download
Slice 4  — Dashboard: last-used model card with resume/new chat
Slice 5  — Dashboard: device stats, storage breakdown, usage stats
Slice 6  — Home polish: thermal advisory, dark theme default
```

---

**Plan version:** 1.0
**Created:** fresh plan for the two-state home screen revamp
**Status:** All slices pending implementation
