# AGENTS.md — SmartChessboard (mobile / KMP)

Module-scoped guidance for the Kotlin Multiplatform app. **Monorepo-wide rules live
in the root [`../AGENTS.md`](../AGENTS.md)** — commit conventions, cross-sub-project
contracts, the ktlint `PostToolUse` hook, and the reason every Gradle call needs an
inline `ANDROID_HOME`. This file covers only what is specific to the KMP module and
not derivable from a single file.

## Module layout (actual)

Gradle multi-module — **not** the single `composeApp/` from the old plan (superseded;
see `../context/SESSION_HANDOFF.md`). `settings.gradle.kts` includes:

- `:shared` — the KMP shared module; all shared code + per-target source sets. iOS
  framework `baseName = "Shared"` (static). This is where the Clean Architecture
  layers will live.
- `:androidApp` — Android application entry point (Compose `MainActivity`); depends
  on `:shared`.
- `:webApp` — WasmJS browser executable; depends on `:shared`.
- `iosApp/` — Xcode SwiftUI entry point (**not** a Gradle module); consumes
  `Shared.framework`. Open in Xcode, no Gradle run task.

`shared/src/` source sets: `commonMain`, `commonTest`, `androidMain`,
`androidHostTest`, `iosMain`, `iosTest`, `wasmJsMain`. Package
`org.rurbaniak.smartchessboard` (shared namespace `…smartchessboard.shared`).

Current `shared/` code is still the KMP-wizard skeleton (`App.kt`, `Greeting*.kt`,
`Platform*.kt`) — not yet refactored into `domain/` / `data/` / `presentation/`.

## Build & run (module-prefixed)

Wrap every Gradle call as the root AGENTS.md describes (`local.properties` is
gitignored):

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew <task> --console=plain --no-daemon
```

- Android debug APK — `:androidApp:assembleDebug`
- Web dev server (WasmJS) — `:webApp:wasmJsBrowserDevelopmentRun`
- iOS — open `iosApp/` in Xcode and run

## Test (per-target, on `:shared`)

Plain `test` does **not** cover the KMP targets. Use:

- `:shared:testAndroidHostTest` — JVM / Android host (fastest; pure-logic tests)
- `:shared:iosSimulatorArm64Test` — iOS simulator (Apple Silicon)
- `:shared:wasmJsTest` — web (headless browser)

Put tests in `shared/src/commonTest` to run on every target, or a target-specific
test source set for platform code.

## Screenshot (golden) tests

JVM-only goldens for Compose UI: Robolectric renders (`@GraphicsMode(NATIVE)`),
Roborazzi compares. Tests live in `shared/src/androidHostTest/kotlin/**/screenshot/`;
committed goldens in `shared/src/androidHostTest/snapshots/*.webp` (lossless WebP,
recorded at 0.5 scale). Wrap invocations with inline `ANDROID_HOME` as usual:

- Record (refresh goldens): `:shared:recordRoborazziAndroidHostTest`
- Verify (the gate): `:shared:verifyRoborazziAndroidHostTest`
- Equivalent fallback: `:shared:testAndroidHostTest -Droborazzi.test.record=true`
  (or `…verify=true`) — the flags are forwarded into the test JVM by
  `shared/build.gradle.kts`.
- On a verify failure: triptych diff images land in
  `shared/build/outputs/roborazzi/*_compare.webp`, HTML report in
  `shared/build/reports/roborazzi/androidHostTest/`.

Write every golden through `ScreenshotHarness.golden(...)` — it pins `AppTheme`,
an explicit `LocalWindowSizeClass` (the 0×0 default silently renders the landscape
SidePane arrangement, not portrait), a fixed shot size, and the shared
record/compare options. A plain test run (no flags) neither records nor verifies.

**Re-record ritual (intentional UI change):**

1. Make the visual change; run the **verify** task — red confirms the goldens see it.
2. Review the diff: HTML report + `_compare.webp` triptychs (Reference | Diff | New).
   The diff must contain exactly the change you intended — nothing else.
3. Run the **record** task to refresh the goldens, then verify once more (green).
4. Commit the refreshed goldens together with the code change.

Until the CI record workflow exists (Phase 5 of the `ui-test-layer` change), local
record is canonical. Once it lands, goldens are recorded **by CI only**
(`record-goldens.yml` dispatch) and local record becomes preview-only — update
this section then.

Expectations baked into the golden set:

- Material3 is pinned at an **alpha** — an M3 bump legitimately moves component
  pixels (top bar, rail, buttons). A broad, reviewed re-record after the bump is
  the ritual working, not drift.
- Board shots are **light-only by design**: the wood and every on-wood overlay are
  constant across modes (`ChessColors`), so dark board goldens would be
  bit-identical duplicates. The promotion shot is the exception (Material-themed
  picker surface) and exists in both modes.

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
- **Target architecture — Clean Architecture, all primarily in `commonMain`:**
  `domain/` (pure Kotlin — the chess rules engine, entities, use cases, repository
  interfaces; no Compose/Supabase/Room/BLE deps), `data/` (Supabase / Room / BLE /
  Lichess-eval implementations), `presentation/` (ViewModels + Compose UI, consumes
  `domain/` use cases, never imports `data/`). MVVM-vs-MVI and the DI library are
  still **TBD**. Full rationale: `../context/foundation/tech-stack.md` → "Architecture
  overview".

## Dependencies

Managed in `gradle/libs.versions.toml` (type-safe accessors: `libs.*`; module deps
via `projects.shared`) — that file is the source of truth for the exact pins (Kotlin,
AGP, Compose Multiplatform, Material3, androidx-lifecycle) and `compileSdk` / `minSdk`
/ JVM target. Don't restate version numbers here; they drift out of sync. Formatting:
ktlint (`ktlint_official`, 120-col) via `.editorconfig`.
