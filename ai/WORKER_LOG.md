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

---

## Commit 3 — `ci: Dependabot (weekly Gradle + Actions updates)`

- hash: (filled at finalization — see bottom of file)
- What:
  - Added `.github/dependabot.yml`: weekly PRs for the `gradle` ecosystem
    (version catalog, AGP/Kotlin/AndroidX bumps grouped as `android-stack`,
    max 5 open) and the `github-actions` ecosystem (keeps our new workflow
    pins current).
- Why: the catalog moves fast (AGP 9.x, Kotlin 2.x, Compose BOM). Automated
  PRs + the CI gate from commit 1 mean updates arrive tested, instead of
  rotting until something breaks.
- Architecture: build automation only, no app code.
- Theme/font: n/a (no UI).
- Verified: schema matches Dependabot docs (`version: 2`, two ecosystems,
  weekly interval); every generated PR triggers the CI workflow.

---

## Commit 4 — `feat(chat): visible copy button + copied confirmation`

- hash: (filled at finalization — see bottom of file)
- What:
  - `feature/chat/components/MessageBubble.kt`: added an explicit copy
    `IconButton` (ContentCopy icon) in the message footer for all complete,
    non-blank messages (user and assistant). Long-press keeps working; both
    paths now share one `copy()` lambda that also fires the new `onCopied`
    callback. Copying is hidden while streaming (partial text misleads).
  - `feature/chat/ChatRoomScreen.kt`: threaded `onCopied` from the screen
    through `MessageListRoom` down to the bubble; on copy it shows the
    existing `SnackbarHost` message "Copied to clipboard" via a
    `rememberCoroutineScope` launch.
- Why: copy-on-long-press already existed but was undiscoverable and gave
  zero feedback — users couldn't tell it worked. This was the cheapest
  high-visibility chat-UX win on the attractiveness list.
- Architecture: presentation layer only (`feature/chat` components + screen).
  No domain/data/DI changes — clipboard stays a platform UI concern via
  `LocalClipboardManager`, matching the existing regenerate/edit callback
  pattern. Zero new dependencies.
- Theme/font: all styling from `MaterialTheme` — `labelSmall` typography,
  `onSurfaceVariant` icon tint, standard `IconButton` sizing (24dp/16dp like
  the regenerate button), and the screen's existing `SnackbarHost` (which
  follows the device dynamic-color theme automatically). No hardcoded colors.
- Verified: `:app:compileDebugKotlin` green, full `:app:testDebugUnitTest`
  green (no behavior change to tested layers).
