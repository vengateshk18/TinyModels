# Model Details — Rich Info + Reliable Download (revised)

## What I found in git history
The legacy `ModelDetailsActivity` (removed in slice 6) was actually good and should be recovered:
- **Rich `ModelDetails`** with `usedStorage`, `sha`, `gated`, `createdAt`, `baseModel`, `widgetPrompts`.
- **Stats card** (Downloads / Likes / Required-size), tag chips via `FlowRow`, files card.
- **Download resume logic** — its ViewModel observed existing active WorkManager work on tap.

The current slice-5 rewrite dropped all of this and only parses a fraction of the API fields.

## Key insight
The HF details API returns **`usedStorage`** = total bytes of the whole repo
(e.g. `1432447106` = 1.43 GB). So the download size can be shown **instantly on page load,
zero HEAD requests**. This replaces the original "parallel HEAD" idea. For models with several
.litertlm variants we show `usedStorage` as the repo size and refine per-selection if needed.

## Root causes of the broken Download
1. `onDownloadClick()` blocks on a sequential per-file HEAD `calculateSize()` (60s timeout each)
   before anything shows → looks dead.
2. `observeExisting()` is never called → re-entering mid-download shows a dead button.
3. Progress is only a bare `%` — no X/Y bytes, no speed, no clear state.

---

## Plan (4 thin slices, each committed)

### Slice D1 — Restore rich `ModelDetails` + shared formatters
- Add to `domain/model/ModelDetails`: `usedStorage, sha, gated, createdAt, baseModel, widgetPrompts`.
- Parse them in `ModelDtoParser.parseDetails` (port from legacy `ModelDetailsParser`).
- `core/ui/Formatters.kt`: `formatBytes`, `formatCount` (1.2K/3.4M), `formatDate` (ISO→"Aug 31, 2026").
  Remove duplicated `formatBytes` in the 3 places.
- Commit: `feat(models): D1 — restore rich ModelDetails fields + shared formatters`

### Slice D2 — Download reliability (the actual fix)
- `ModelDetailsViewModel`:
  - `init`: bind to `downloadModel.observeExisting(modelId)` → resume after re-entry.
  - `onDownloadClick()`: set status `CHECKING_SIZE` immediately, then enqueue + stream progress.
    Use `usedStorage` as the known total (no blocking HEAD); worker self-corrects via `x-linked-size`.
  - Storage pre-check: free space vs total → "not enough space" error.
- `DownloadState` gains `status` (IDLE/CHECKING_SIZE/DOWNLOADING/COMPLETED/FAILED), `downloadedBytes`,
  `totalBytes`, `progress`, `bytesPerSecond`.
- Commit: `fix(models): D2 — instant-start, resumable, storage-checked downloads`

### Slice D3 — Rich details UI (recover + improve legacy design)
Stateless content + ViewModel wrapper:
- **Header**: name, author (icon), pipeline + library chips.
- **Stats card**: Downloads, Likes, **Required size** (usedStorage), file count.
- **About card**: created/updated dates, base model, gated badge if present.
- **Try it card**: the 4 widget sample prompts (tappable → prefill chat later; for now display).
- **Tags card**: FlowRow chips.
- **Files card**: each .litertlm file (+ size when known).
- **Sticky download bar**: Idle("Download · 1.43 GB") / Checking("Preparing…") / Downloading
  (bar + % + "X of Y" + speed + Cancel) / Downloaded(✓ + Delete) / Failed(error + Retry).
- Commit: `feat(models): D3 — rich model details screen with full download UX`

### Slice D4 — ViewModel unit tests
- Fake repo + use case; test idle→checking→downloading→completed, resume-on-init, storage-error, cancel.
- Commit: `test(models): D4 — ModelDetailsViewModel state tests`

## Verification
- `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` green each slice.
- Manual: open model → size shows instantly; Download → immediate X/Y MB progress; leave &
  re-enter → resumes; cancel → cleans; done → ✓ and appears in Downloaded models.
