# Model Browse & Download Revamp Plan

> Goal: Transform the Models tab from a fixed LiteRT-community-only list into
> a **searchable, filterable catalog** supporting both text-generation and
> image-generation models. Add client-side filtering so only models with
> runnable runtime files (`.litertlm`, `.task`, `.tflite`) are shown. Revamp
> the downloaded-models list with richer cards. Every change is additive and
> backward-compatible — existing download/inference/chat pipelines are
> untouched.

---

## 1. Current state (what exists today)

```
ModelsTabScreen (Browse | Downloaded)
├── Browse tab → ModelListScreen (via ModelListViewModel)
│   └── HuggingFaceApi.listModels() → hardcoded author=litert-community
│       → ModelDtoParser.parseModelList() → List<ModelSummary>
│       → flat LazyColumn of ModelListItem cards (id, author, downloads, likes, pipelineTag)
├── Downloaded tab → inline LazyColumn of DownloadedModelItem cards (id, size)
└── ModelDetailsScreen (pushed) → ModelDetailsViewModel → getModelDetails()
    ├── HeaderCard, StatsCard, TryItCard, AboutCard, TagsCard, FilesCard
    └── DownloadBar (sticky) → DownloadModelUseCase → ModelDownloadWorker
```

Key constraints discovered:
- `HuggingFaceApi.listModels()` is hardcoded to `author=litert-community` — no search.
- `ModelSummary` has `siblings: List<String>` NOT parsed from the list API (only from detail API). The list API with `full=true` returns `siblings` but the parser ignores it.
- `ModelDetails.liteRtFiles` filters `siblings` for `.litertlm` only — does NOT include `.task` or `.tflite`.
- `DownloadModelUseCase` downloads only `model.liteRtFiles` — would need to download `.task`/`.tflite` too.
- `ModelDownloadWorker` stores `pipelineTag` in `DownloadedModelEntity` — already supports any pipeline tag.
- `ModelManager` loads via `com.google.ai.edge.litertlm.Engine` — only supports `.litertlm` files.
- The inference engine (`litertlm`) is text-generation only. Image generation would need a different runtime (out of scope for this plan — we surface them in browse but downloading + running image-gen is a future phase).
- No search bar, no task-type filter, no client-side filtering of non-runnable models.
- DB is at version 4. `DownloadedModelEntity` has: modelId, author, libraryName, pipelineTag, localPath, files, sizeBytes, downloadedAt.

---

## 2. Target architecture

```
ModelsTabScreen (Browse | Downloaded)
├── Browse tab
│   ├── SearchBar (TextField) — user types query → debounce 500ms → search
│   ├── FilterChips (FlowRow): [All] [Text Generation] [Image Generation]
│   ├── ModelList → LazyColumn of ModelCard (rich, informative)
│   │   └── Each card shows: id, author, pipelineTag chip, downloads, likes,
│   │       library chip, runtime-file badge (if siblings available), lastModified
│   ├── Empty state: "No models found. Try a different search."
│   └── Error state: retry button
├── Downloaded tab
│   ├── Summary card: total models, total disk usage
│   └── LazyColumn of DownloadedModelCard (rich)
│       └── Each card: id, author, pipelineTag chip, size, file count,
│           downloaded date, delete button, "loaded" indicator if active
└── ModelDetailsScreen (pushed) — unchanged core, minor polish
```

### Key API changes

`HuggingFaceApi` gets new methods:
- `listModels(search: String?, pipelineTag: String?, author: String?, limit: Int)` — builds query string dynamically.
- `searchModels(query: String, pipelineTag: String?)` — convenience wrapper.
- Default (no search): `author=litert-community&sort=downloads&direction=-1&limit=100&full=true` (unchanged behavior).
- Search: `search=$query&filter=$pipelineTag&sort=downloads&direction=-1&limit=100&full=true`.
- `full=true` is always set so `siblings` (filenames) are included in the list response.

`ModelDtoParser.parseModelList()` updated:
- Parse `siblings` from each list item (currently ignored). This lets us do **client-side runtime-file filtering** without a per-model detail call.
- Parse `lastModified` (already parsed).

`ModelSummary` updated:
- Add `siblings: List<String>` field (filenames from the list API with `full=true`).
- Add computed `hasRuntimeFiles: Boolean` — checks for `.litertlm`, `.task`, `.tflite` in siblings.
- Add computed `runtimeFileCount: Int`.

`ModelDetails` updated:
- Rename `liteRtFiles` → `runtimeFiles` (or add `runtimeFiles` alongside it) — includes `.litertlm`, `.task`, `.tflite`.
- Keep `liteRtFiles` as a deprecated alias for backward compat (returns `.litertlm` subset).

`ModelRepository` updated:
- `listModels(search: String?, pipelineTag: String?): AppResult<List<ModelSummary>>` — replaces the no-arg version.
- `listModels()` (no-arg) delegates to `listModels(null, null)` for backward compat.

`ModelListViewModel` updated:
- `search(query: String?, pipelineTag: String?)` — debounced.
- `UiState` adds `searchQuery: String`, `selectedFilter: ModelFilter`.
- Client-side filter: if `siblings` available, filter out models with no runtime files.

`DownloadModelUseCase` updated:
- Download `model.runtimeFiles` instead of `model.liteRtFiles`.
- Keep backward compat: if `runtimeFiles` is empty, fall back to `liteRtFiles`.

---

## 3. Data / schema changes

### ModelSummary — add `siblings`

```kotlin
data class ModelSummary(
    val id: String,
    val modelId: String,
    val author: String,
    val likes: Int?,
    val downloads: Long?,
    val tags: List<String>,
    val libraryName: String?,
    val pipelineTag: String?,
    val lastModified: String?,
    val siblings: List<String> = emptyList()  // NEW — filenames from full=true
) {
    val hasRuntimeFiles: Boolean
        get() = siblings.any { it.endsWith(".litertlm", true) || it.endsWith(".task", true) || it.endsWith(".tflite", true) }
    val runtimeFileCount: Int
        get() = siblings.count { it.endsWith(".litertlm", true) || it.endsWith(".task", true) || it.endsWith(".tflite", true) }
}
```

### ModelDetails — add `runtimeFiles`

```kotlin
data class ModelDetails(...) {
    // Existing: liteRtFiles (`.litertlm` only) — keep for backward compat
    val liteRtFiles: List<String>
        get() = siblings.filter { it.endsWith(".litertlm", ignoreCase = true) }

    // NEW: all runnable files (.litertlm + .task + .tflite)
    val runtimeFiles: List<String>
        get() = siblings.filter {
            it.endsWith(".litertlm", ignoreCase = true) ||
            it.endsWith(".task", ignoreCase = true) ||
            it.endsWith(".tflite", ignoreCase = true)
        }
}
```

### ModelFilter enum (new)

```kotlin
enum class ModelFilter(val label: String, val pipelineTag: String?) {
    ALL("All", null),
    TEXT_GENERATION("Text Generation", "text-generation"),
    IMAGE_GENERATION("Image Generation", "text-to-image")
}
```

### Room — NO changes
- `DownloadedModelEntity` already stores `pipelineTag` — no new columns needed.
- DB stays at version 4. No migration.
- The worker already stores `pipelineTag` from the model details.

---

## 4. Slices (thin vertical, each committed separately)

### Slice M1 — HuggingFaceApi: searchable list endpoint
**Goal:** Make the API layer support arbitrary search queries + pipeline_tag filtering.

Files:
- `core/network/HuggingFaceApi.kt`:
  - Add `listModels(search: String?, pipelineTag: String?, author: String?, limit: Int = 100): String` — builds query dynamically with `Uri.Builder` or string concatenation.
  - Always include `full=true` and `sort=downloads&direction=-1`.
  - When `search` is null/blank and `author` is null: default to `author=litert-community` (backward compat).
  - When `search` is non-blank: use `search=` param, drop the default author.
  - When `pipelineTag` is non-null: add `filter=$pipelineTag`.
  - Keep existing `listModels(): String` as a delegate to `listModels(null, null, null)`.

**Safety:** Existing `listModels()` call in `ModelRepositoryImpl` still works unchanged.
- **Commit:** `feat(api): M1 — searchable HuggingFace list endpoint`

---

### Slice M2 — Parse siblings from list API + ModelSummary fields
**Goal:** Parse `siblings` from the list response so we can filter client-side.

Files:
- `core/network/dto/ModelDtoParser.kt`:
  - `parseSummary()`: parse `siblings` array → `List<String>` of `rfilename` values (same as `parseDetails` does).
- `domain/model/Model.kt`:
  - `ModelSummary`: add `siblings: List<String> = emptyList()` + `hasRuntimeFiles` + `runtimeFileCount` computed props.
- `ModelDtoParserTest.kt`: update test JSON to include `siblings` in list items, assert parsing.

**Safety:** `siblings` defaults to `emptyList()` — existing code that doesn't use it is unaffected.
- **Commit:** `feat(api): M2 — parse siblings from list API + runtime-file filtering helpers`

---

### Slice M3 — ModelRepository + ModelListViewModel: search + filter
**Goal:** Wire search + pipeline-tag filter through repository → ViewModel.

Files:
- `domain/repository/ModelRepository.kt`:
  - Add `listModels(search: String?, pipelineTag: String?): AppResult<List<ModelSummary>>`.
  - Keep `listModels()` as delegate to `listModels(null, null)`.
- `data/repository/ModelRepositoryImpl.kt`:
  - Implement new overload → calls `api.listModels(search, pipelineTag, null)`.
  - Client-side filter: `result.filter { it.hasRuntimeFiles || it.siblings.isEmpty() }` —
    keep models whose siblings we couldn't parse (defensive) but drop known-non-runnable ones.
- `feature/models/ModelListViewModel.kt`:
  - `UiState`: add `searchQuery: String = ""`, `selectedFilter: ModelFilter = ModelFilter.ALL`.
  - `search(query: String)`: debounce 500ms, call `listModels(query, filter.pipelineTag)`.
  - `setFilter(filter: ModelFilter)`: call `listModels(searchQuery, filter.pipelineTag)`.
  - `refresh()`: call `listModels(searchQuery, selectedFilter.pipelineTag)`.
- `domain/model/ModelFilter.kt`: new enum.

**Safety:** Default behavior (no search, ALL filter) is identical to current.
- **Commit:** `feat(models): M3 — search + pipeline-tag filter in repository + ViewModel`

---

### Slice M4 — Browse UI: search bar + filter chips + rich model cards
**Goal:** Revamp the Browse tab with search, filter chips, and informative cards.

Files:
- `feature/models/screens/ModelsTabScreen.kt`:
  - Browse tab: replace inline LazyColumn with a new `BrowseContent` composable.
  - `BrowseContent`:
    - `OutlinedTextField` (search bar) at top — bound to ViewModel `searchQuery`.
    - `FlowRow` of `FilterChip`s: All / Text Generation / Image Generation.
    - `LazyColumn` of `ModelCard` items.
  - `ModelCard` (replaces `ModelListItem`):
    - Card with `onClick`.
    - Row: model id (titleMedium, 2 lines max) + pipelineTag chip (colored).
    - Row: author with person icon.
    - Row: downloads (with icon), likes (with icon), runtime file count badge.
    - Row: library chip + lastModified date.
    - If `!hasRuntimeFiles && siblings.isNotEmpty()`: show subtle "No runtime files" warning chip.
  - Empty state: "No models found" illustration text.
  - Error state: message + Retry button.
  - Loading: CircularProgressIndicator.

**Safety:** `onModelClick` callback unchanged — navigation to details still works.
- **Commit:** `feat(models): M4 — browse UI with search bar, filter chips, rich cards`

---

### Slice M5 — ModelDetails: runtimeFiles (support .task + .tflite)
**Goal:** Generalize the detail/download pipeline to handle all runtime file types.

Files:
- `domain/model/Model.kt`:
  - `ModelDetails`: add `runtimeFiles` computed property (`.litertlm` + `.task` + `.tflite`).
  - Keep `liteRtFiles` as-is (backward compat).
- `domain/usecase/model/DownloadModelUseCase.kt`:
  - `calculateSize()`: sum `runtimeFiles` instead of `liteRtFiles`.
  - `execute()`: pass `runtimeFiles` to worker instead of `liteRtFiles`.
  - Fallback: if `runtimeFiles.isEmpty()`, use `liteRtFiles` (safety).
- `feature/models/screens/ModelDetailsScreen.kt`:
  - `DownloadBar`: show if `model.runtimeFiles.isNotEmpty()` (instead of `liteRtFiles`).
  - `FilesCard`: show `runtimeFiles` (instead of `liteRtFiles`).
  - `StatsCard`: file count from `runtimeFiles.size`.

**Safety:** `.litertlm` models still work identically — `runtimeFiles` is a superset.
- **Commit:** `feat(models): M5 — runtimeFiles supports .task + .tflite in detail + download`

---

### Slice M6 — Revamp Downloaded models list
**Goal:** Rich, informative downloaded-model cards.

Files:
- `feature/models/screens/ModelsTabScreen.kt`:
  - Downloaded tab: replace inline `DownloadedModelItem` with `DownloadedModelCard`.
  - `DownloadedModelCard`:
    - Card with `onClick` (navigate to details if model still exists on HF).
    - Row: model id (titleMedium) + pipelineTag chip.
    - Row: author (if available) with person icon.
    - Row: size (formatted MB/GB) with storage icon + file count.
    - Row: downloaded date (formatted).
    - Delete IconButton (with confirmation dialog).
    - "Loaded" indicator if `activeModelId == model.modelId`.
  - Summary card at top: "X models · Y GB total".
  - Empty state: "No models downloaded" illustration.
- `feature/models/DownloadedModelsViewModel.kt`:
  - Already has `models`, `totalSizeBytes`, `activeModelId`, `delete()`. No changes needed.
  - Wire `delete()` into the new card's delete button + confirmation dialog.
  - Also revamp the standalone `DownloadedModelsScreen` (pushed route) with the same card style.

**Safety:** `DownloadedModel` domain model unchanged. `delete()` logic unchanged.
- **Commit:** `feat(models): M6 — revamped downloaded model cards with summary`

---

### Slice M7 — Polish: theme consistency, empty states, error handling
**Goal:** Ensure all model screens follow the app theme, font scale, and edge-to-edge rules.

Files:
- Verify `ModelsTabScreen` inner Scaffold has `contentWindowInsets = WindowInsets(0,0,0,0)` (already done in previous fix).
- Verify all cards use `MaterialTheme.colorScheme.surfaceContainer`.
- Verify all text uses `MaterialTheme.typography` (respects fontScale).
- Add `navigationBarsPadding` where needed (already handled via contentWindowInsets zeroing).
- Ensure search bar has proper focus handling + keyboard dismissal on submit.
- Add `ImeAction.Search` to the search TextField.
- Ensure filter chips use `FilterChip` (M3 component) with proper selected state.

- **Commit:** `feat(models): M7 — polish, theme consistency, empty/error states`

---

## 5. Migration / safety summary

| Change | Migration needed? | Why |
|--------|-----------------|-----|
| `HuggingFaceApi.listModels(search, pipelineTag)` | **No** | New overload; old `listModels()` delegates |
| `ModelSummary.siblings` | **No** | New field with default `emptyList()` |
| `ModelSummary.hasRuntimeFiles` / `runtimeFileCount` | **No** | Computed properties, additive |
| `ModelDetails.runtimeFiles` | **No** | New computed property; `liteRtFiles` kept |
| `ModelFilter` enum | **No** | New file, no impact |
| `ModelRepository.listModels(search, pipelineTag)` | **No** | New overload; old `listModels()` delegates |
| `DownloadModelUseCase` using `runtimeFiles` | **No** | `.litertlm` is a subset of `runtimeFiles` |
| `DownloadedModelEntity` | **No** | No new columns; already has `pipelineTag` |
| DB version | **Stays at 4** | No schema changes |

**Key safety principles:**
- Every slice is independently buildable and committable.
- Default behavior (no search, ALL filter) is identical to current app behavior.
- `.litertlm` download/inference pipeline is fully preserved.
- Client-side filtering only drops models when `siblings` is non-empty and has no runtime files — defensive (keeps unknowns).
- Image-generation models are **browseable** but running them requires a different runtime (future phase). For now, they can still be downloaded — the file format is the same `.litertlm`/`.tflite`.
- No existing test breaks — `ModelDtoParserTest` assertions for `.litertlm` still pass.
