# Contributing to TinyModels

Thanks for helping! TinyModels is a community project — issues, pull requests, and
ideas are all welcome.

## Ways to contribute

- **Report bugs** — use the bug-report issue template; include device model, RAM,
  Android version, model + file used, and steps to reproduce.
- **Fix bugs / add features** — comment on the issue first so work isn't duplicated.
- **Improve docs** — README, privacy site (`docs/*.html`), code comments.
- **Add tests** — unit tests live beside the tree under `app/src/test`; run the full
  suite before pushing.

## Setup

```bash
git clone https://github.com/vengateshk18/TinyModels.git
cd TinyModels
./gradlew :app:assembleDebug
```

Requirements: Android Studio (recent), JDK 17. An arm64 device with ≥ 4 GB RAM is
needed to actually run models; UI-only changes build and test without one.

## Workflow

1. Fork the repo, create a branch from `worker`:
   `git checkout -b feat/short-description` (or `fix/...`).
2. Make focused changes — one concern per PR, no drive-by refactors.
3. Verify: `./gradlew :app:compileDebugKotlin` (or the task may be heavy; at minimum
   ensure the files you touched are covered) and
   `./gradlew :app:testDebugUnitTest`.
4. Keep the privacy promise: no new network calls, SDKs, or permissions without
   calling it out in the PR **and** updating `docs/privacy.html` + `docs/security.html`.
5. Open the PR against `worker` with a clear description, test evidence, and screenshots
   for UI changes.

## Code style

- Kotlin + Jetpack Compose, MVI (single immutable `UiState` per screen).
- Match surrounding code; keep functions small and named after behavior.
- User-visible strings live inline in composables (current convention); errors must be
  human-readable, never stack traces.

## Code of Conduct

Be kind and constructive — see [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
