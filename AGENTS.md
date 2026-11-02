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
