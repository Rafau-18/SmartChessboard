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

### UI smoke tests (compose.uiTest v2)

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
