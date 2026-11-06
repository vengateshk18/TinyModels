# TinyModels — agent instructions

## Development workflow: Test-Driven Development is mandatory

**NO PRODUCTION CODE WITHOUT A FAILING TEST FIRST.**

Every feature, bug fix, refactor, or behavior change follows Red → Green → Refactor:

1. **RED** — write one minimal test for the behavior, run it, and watch it fail for the
   right reason (missing behavior, not a typo or compile error).
2. **GREEN** — write the simplest code that makes it pass. Nothing more.
3. **REFACTOR** — clean up while staying green.

Rules:
- Tests written *after* the code do not count. Delete the code and start from the test.
- Verify the failure. If the test passes immediately, it is testing existing behavior.
- Run the full suite before claiming done, not just the new test.
- Applies to UI code too: extract the logic (clamping, stepping, commit-on-release,
  state transitions) into a testable unit and test that, rather than testing Compose
  rendering.
- Exceptions (throwaway prototypes, generated code, pure config) require asking the
  user first.

## Commands

```bash
# Compile
./gradlew :app:compileDebugKotlin

# Unit tests — all of them, before calling anything done
./gradlew :app:testDebugUnitTest -Pandroid.experimental.disableJdkImageTransform=true

# Single test class
./gradlew :app:testDebugUnitTest --tests "com.ai.tinymodels.feature.settings.SettingsViewModelTest" \
  -Pandroid.experimental.disableJdkImageTransform=true

# Debug APK
./gradlew :app:assembleDebug -Pandroid.experimental.disableJdkImageTransform=true
```

`-Pandroid.experimental.disableJdkImageTransform=true` is required locally: the Gradle
Java 25 toolchain resolves to a VS Code JRE that has no `jlink`, which breaks
`compileDebugJavaWithJavac`. It is an environment workaround, not a project setting.

## Layout

- `app/src/main/java/com/ai/tinymodels/` — code, feature-first
  (`feature/<name>/`, `core/`, `data/`, `domain/`).
- `app/src/test/java/` — JVM unit tests. Put new logic tests here.
- `app/src/androidTest/java/` — instrumentation tests (device required).
- `docs/` — the GitHub Pages site. Plain static HTML/CSS, no build step.

## Codebase search: use graphify before grepping

A knowledge graph of the code lives in `graphify-out/` (~2.1k nodes, ~5.9k edges,
~109 communities). It is gitignored — rebuild it, never commit it.

```bash
# Ask a question about the code; returns only the relevant subgraph
graphify query "how does a chat title flow from the UI to the database?"

# Shortest relationship between two symbols or modules
graphify path "ChatTabScreen()" "ChatEntity" --undirected

# What a symbol imports, calls, or is used by
graphify explain "ChatListViewModel.kt"

# Architectural hubs
graphify god-nodes
```

Rules:
- Reach for `query` / `path` / `explain` **first** for structure, data-flow, and
  "what calls what" questions. Fall back to `grep` and `read` for exact literals,
  string resources, and anything the graph does not cover.
- The graph is **code-only** (`--code-only`): Kotlin sources are indexed; `docs/`,
  images, and XML are not. Use `grep` for those.
- `path` searches directed edges by default and often finds nothing useful; pass
  `--undirected` unless you specifically want call direction.
- `query` exits `3` when a name is ambiguous (candidates are listed) and `4` when
  nothing matches.
- A fresh clone has **no graph**; build it once — free, AST-only, no API key:
  ```bash
  graphify extract . --code-only --force && graphify cluster-only . --no-label
  ```
- Keep it fresh after code changes with `graphify update .` (cheaper, incremental),
  or the two commands above for a full rebuild. Check staleness against the commit
  in `graphify-out/GRAPH_REPORT.md` ("Graph Freshness").

## Device testing

**Never install, launch, or interact with the app on an emulator or a physical
device unless the user explicitly asks for it in the current session.** No
`adb install`, no `adb shell am start`, no UI automation, no screenshots from a
device, no logcat-driven verification. Compile (`:app:compileDebugKotlin`),
`assembleDebug`, and unit tests are fine. When a change needs on-device
verification, say so and wait to be asked.

## Conventions

- Settings flow: `SettingsViewModel` → `SettingsRepository` → DataStore → `AppSettings`.
  The app theme is driven by `settings` collected in `MainActivity`.
- Never write high-frequency UI state (slider drags, text input) to DataStore. Keep it
  local and commit on release.
- Do not bump `versionCode` / `versionName` unless asked.
- GGUF support and the additional languages are not implemented — the website must keep
  labelling them as upcoming.
- Do not commit signing material (`Release.jks`, `release.properties`), `.opencode/`,
  research notes, or generated assets. They are in `.gitignore`; keep it that way.
