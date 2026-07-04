# AGENTS.md — SmartChessboard (mobile / KMP)

Module-scoped guidance for the Kotlin Multiplatform app. **Monorepo-wide rules live
in the root [`../AGENTS.md`](../AGENTS.md)** — commit conventions, cross-sub-project
contracts, the ktlint `PostToolUse` hook, and the reason every Gradle call needs an
inline `ANDROID_HOME`. **Module layout, setup (`local.properties` keys), build/run/test
commands, and web deploy live in [`README.md`](README.md)** — this file covers only
what is specific to agents/contributors and not derivable from a single file.

## Module facts beyond the README

- Gradle multi-module — **not** the single `composeApp/` from the old plan
  (superseded; see `../context/SESSION_HANDOFF.md`).
- iOS framework `baseName = "Shared"` (static).
- `shared/src/` source sets: `commonMain`, `commonTest`, `androidMain`,
  `androidHostTest`, `iosMain`, `iosTest`, `wasmJsMain`, `wasmJsTest`. Package
  `org.rurbaniak.smartchessboard` (shared namespace `…smartchessboard.shared`).
- Put tests in `shared/src/commonTest` to run on every target, or a target-specific
  test source set for platform code.

## Screenshot (golden) tests

JVM-only goldens for Compose UI: Robolectric renders (`@GraphicsMode(NATIVE)`),
Roborazzi compares. Tests live in `shared/src/androidHostTest/kotlin/**/screenshot/`;
committed goldens in `shared/src/androidHostTest/snapshots/*.png` (lossless PNG via
the JDK-native `ImageIO` writer, recorded at 0.5 scale). **PNG, not WebP** — the
`webp-imageio` codec couldn't reliably read its own output; details in
`../context/foundation/lessons.md`. Wrap invocations with inline `ANDROID_HOME` as usual:

- Record (refresh goldens): `:shared:recordRoborazziAndroidHostTest`
- Verify (the gate): `:shared:verifyRoborazziAndroidHostTest`
- Equivalent fallback: `:shared:testAndroidHostTest -Droborazzi.test.record=true`
  (or `…verify=true`) — the flags are forwarded into the test JVM by
  `shared/build.gradle.kts`.
- On a verify failure: triptych diff images land in
  `shared/build/outputs/roborazzi/*_compare.png`, HTML report in
  `shared/build/reports/roborazzi/androidHostTest/`.

Write every golden through `ScreenshotHarness.golden(...)` — it pins `AppTheme`,
an explicit `LocalWindowSizeClass` (the 0×0 default silently renders the landscape
SidePane arrangement, not portrait), a fixed shot size, and the shared
record/compare options. A plain test run (no flags) neither records nor verifies.

**CI is the canonical recording environment.** Goldens are recorded by the
`record-goldens.yml` workflow on ubuntu — the same image `tests.yml` verifies on.
Local record (`recordRoborazziAndroidHostTest` on a Mac) is **preview-only**: fonts
rasterize differently, so Mac-recorded goldens fail CI verify. Never commit them.

**Re-record ritual (intentional UI change):**

1. Make the visual change on a branch; run the local **verify** task — red confirms
   the goldens see it. Preview the triptychs (Reference | Diff | New) in the HTML
   report; the diff must contain exactly the change you intended — nothing else.
2. Push the branch, then dispatch the record workflow on it:
   `gh workflow run record-goldens.yml --ref <branch>` → `gh run watch`.
   The refreshed goldens come back as a `github-actions[bot]` commit
   (`test(goldens): re-record on CI`) on that branch.
3. Pull and review the bot commit's golden diff, then confirm the gate:
   `gh workflow run tests.yml --ref <branch>` → green.
4. Merge. (A bot push via `GITHUB_TOKEN` does not itself trigger `tests.yml` —
   dispatch it explicitly or open the PR.)

CI test workflows (`.github/workflows/`): `tests.yml` — PR/main gate, JVM suite with
golden verify + wasm suite, uploads the Roborazzi diff report as artifact on failure;
`record-goldens.yml` — dispatch-only golden refresh (above); `ios-tests.yml` —
nightly + dispatch iOS simulator suite on macOS (deliberately not a PR gate, ×10
minutes billing).

Expectations baked into the golden set:

- Material3 is pinned at an **alpha** — an M3 bump legitimately moves component
  pixels (top bar, rail, buttons). A broad, reviewed re-record after the bump is
  the ritual working, not drift.
- Board shots are **light-only by design**: the wood and every on-wood overlay are
  constant across modes (`ChessColors`), so dark board goldens would be
  bit-identical duplicates. The promotion shot is the exception (Material-themed
  picker surface) and exists in both modes.

## UI smoke tests (compose.uiTest v2)

`shared/src/commonTest/.../uitest/` holds compose.uiTest v2 smoke flows: the
production `App()` root composed over Koin overrides (`AppTestHarness.runAppTest` —
fakes at the repository seams, in-memory `Settings`, runs without any Supabase
credentials). **Contract targets: `:shared:iosSimulatorArm64Test` +
`:shared:wasmJsTest`.** The `uitest/` package is excluded from `testAndroidHostTest`
(see the exclude block in `shared/build.gradle.kts`): plain-JUnit4 host tests have no
instrumentation — `AndroidComposeUiTestEnvironment` NPEs probing
`android.os.Build.FINGERPRINT` (Robolectric detection), and commonTest classes cannot
carry `@RunWith(RobolectricTestRunner)`; Android behavior stays covered by the
ViewModel/reducer suites. Harness rules: assert by semantics (never pixels), keep
ViewModel suspend work on `Dispatchers.Main.immediate` via the injectable dispatcher
seams (a `withContext` hop escapes what `waitUntil` pumps on single-threaded wasm),
and never let a test reach the network. The wasm browser run raises karma-mocha's 2s
per-test timeout in `shared/karma.config.d/mocha-timeout.js`.

## IDE split (AGP 9.0.1 vs IntelliJ)

IntelliJ tops out at AGP 9.0.0-alpha06 but the project is on AGP 9.0.1, so each
sub-tree opens in a different IDE:

- `androidApp/` → Android Studio
- `shared/` + `webApp/` → IntelliJ IDEA
- `iosApp/` → Xcode

**Do not load `androidApp` into IntelliJ.**

## Module coding rules

- **Web is WasmJS-only.** The wizard's Kotlin/JS target was removed by design — do
  not add a `js(...)` target or a `jsMain` source set.
- **BLE / physical-board / reed-switch code goes only in `androidMain` + `iosMain`,
  never `wasmJsMain`.** Web ships the digital subset only (pass-and-play, history,
  replay, analysis). Rationale: `../context/foundation/lessons.md`.
- **Architecture — Clean Architecture, all primarily in `commonMain`:**
  `domain/` (pure Kotlin — the chess rules engine, entities, use cases, repository
  interfaces; no Compose/Supabase/BLE deps), `data/` (Supabase / journal / BLE /
  eval implementations), `presentation/` (ViewModels + Compose UI, consumes
  `domain/` use cases, never imports `data/`). **MVVM by default** (MVI only for
  genuinely event-heavy screens, with written justification in the change's plan);
  **DI via Koin** — one `initKoin()` bootstrap per platform entry point (both
  decided 2026-06-10, S-01; see `../context/foundation/lessons.md`). Full rationale:
  `../context/foundation/tech-stack.md` → "Architecture overview".

## Dependencies

Managed in `gradle/libs.versions.toml` (type-safe accessors: `libs.*`; module deps
via `projects.shared`) — that file is the source of truth for the exact pins (Kotlin,
AGP, Compose Multiplatform, Material3, androidx-lifecycle) and `compileSdk` / `minSdk`
/ JVM target. Don't restate version numbers here; they drift out of sync. Formatting:
ktlint (`ktlint_official`, 120-col) via `.editorconfig`.
