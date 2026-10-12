# Worker Branch Log

Every change committed on the `worker` branch is explained here, one section
per commit. Each section states **what** changed, **why**, the **clean
architecture layer** it lives in, **theme/font** compliance for UI work, and
**how it was verified**.

Legend: `hash:` is the short commit hash (`git log --oneline`).

---

## Commit 1 — `ci: Android CI (debug build + unit tests + lint)` (+ revamp plan doc)

- hash: e88a1b0
- What:
  - Added `.github/workflows/ci.yml`: on push to `main`/`worker` and PRs to
    `main` it assembles the debug APK, runs `:app:testDebugUnitTest`, runs
    `:app:lintDebug`, and uploads the APK + test reports + lint report as
    artifacts (kept even on failure via `if: always()`).
  - Toolchain pinned to what the repo uses: Temurin JDK 17, Android SDK via
    `android-actions/setup-android`, Gradle caching via `gradle/actions`.
  - Committed the previously untracked `ai/WEB_SEARCH_REVAMP_LIKE_THINAI.md`
    (Thinai-parity web-search plan + implementation status) so the branch is
    self-documenting.
- Why: no CI existed (no `.github` dir at all). Every later worker change is
  now automatically build- and test-checked; contributors get APK/test/lint
  artifacts without a local setup.
- Architecture: no app code touched — build automation only.
- Theme/font: n/a (no UI).
- Verified: YAML structure mirrors the exact Gradle tasks that pass locally
  (`assembleDebug`, `testDebugUnitTest`, `lintDebug`); full unit suite green
  locally before committing.

---

## Commit 2 — `ci: release APK workflow (tags + manual)`

- hash: (filled at finalization — see bottom of file)
- What:
  - Added `.github/workflows/release.yml`: on `v*` tags (and manually via
    `workflow_dispatch`) it runs `:app:assembleRelease` (R8-minified,
    arm64-only release APK), re-runs unit tests as a release gate, uploads
    the APK as an artifact, and — for tag pushes — attaches it to the GitHub
    Release with generated notes via `softprops/action-gh-release`.
  - Documented in-file that the APK is unsigned (repo has no signing config):
    fine for sideloading/CI; a keystore-backed signing step can be added
    later using GitHub secrets.
- Why: turns version tags into one-click installable APKs with zero local
  release labor; the test gate stops a red build from shipping.
- Architecture: build automation only, no app code.
- Theme/font: n/a (no UI).
- Verified: `assembleRelease` is the documented release path in README
  (`./gradlew :app:assembleRelease`); task graph identical to CI's proven
  setup steps.
