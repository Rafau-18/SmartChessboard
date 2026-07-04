# SmartChessboard — Kotlin Multiplatform app

The client of [Smart Chessboard](../README.md): a Compose Multiplatform app targeting
**Android**, **iOS**, and **web (WasmJS)** from a single Kotlin codebase. It owns the
chess rules engine, the PGN game record, all UI, and the BLE link to the physical
board. Web ships the digital subset only (pass-and-play, history, replay, analysis) —
no BLE in the browser.

## Module layout

- `:shared` — all product code, Clean Architecture in `commonMain`:
  `domain/` (pure chess logic: rules engine, entities, use cases) →
  `data/` (Supabase, local write-ahead journal, BLE board adapter) →
  `presentation/` (Compose UI + MVVM ViewModels, Koin DI).
  Platform specifics (BLE, OAuth glue) live in `androidMain` / `iosMain`.
- `:androidApp` — Android entry point (Compose activity).
- `:webApp` — WasmJS browser entry point.
- `iosApp/` — Xcode project (SwiftUI entry consuming `Shared.framework`; not a
  Gradle module — open in Xcode).

## Setup

Create `SmartChessboard/local.properties` (gitignored):

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
SUPABASE_URL=https://<project>.supabase.co
SUPABASE_ANON_KEY=<anon key>            # public; data is protected by RLS
GOOGLE_SERVER_CLIENT_ID=<web client id> # optional — only the Android native sign-in sheet needs it
```

The app builds without the Supabase values but crashes at startup (DI wiring), so set
them first. For iOS signing, put your Apple `TEAM_ID` in
`iosApp/Configuration/Config.xcconfig` and keep that change out of git
(`git update-index --skip-worktree`).

Instead of `sdk.dir` you can pass the SDK path inline (useful in CI and fresh
worktrees):

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew <task> --console=plain --no-daemon
```

## Run

- **Android:** `./gradlew :androidApp:assembleDebug` (or run from Android Studio)
- **Web (dev server):** `./gradlew :webApp:wasmJsBrowserDevelopmentRun`
- **iOS:** open `iosApp/` in Xcode and run

## Test

Plain `test` does **not** cover the KMP targets — use the per-target tasks on
`:shared`:

- `:shared:testAndroidHostTest` — JVM / Android host (fastest; also runs the
  Roborazzi screenshot goldens)
- `:shared:iosSimulatorArm64Test` — iOS simulator (Apple Silicon)
- `:shared:wasmJsTest` — web, headless browser (also runs the Compose UI smoke tests)

Screenshot-golden **recording** is CI-only (Mac-recorded goldens fail CI verify); the
re-record ritual and UI-test harness rules are in [`AGENTS.md`](AGENTS.md).

## Web deploy

Cloudflare Workers (static assets), configured by the repo-root
[`wrangler.toml`](../wrangler.toml):

```bash
./gradlew :webApp:wasmJsBrowserDistribution   # production bundle
cd .. && npx wrangler deploy                  # publish
```

## More

- [`AGENTS.md`](AGENTS.md) — contributor/agent rules: IDE split (AGP vs IntelliJ),
  golden re-record ritual, module coding rules, dependency conventions.
- [`../context/foundation/tech-stack.md`](../context/foundation/tech-stack.md) — why
  this stack, full architecture rationale.
