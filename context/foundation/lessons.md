# Lessons Learned

Recurring rules and pitfalls for the Smart Chessboard project. New lessons are appended at the end of this file. Format follows the convention introduced by `/10x-lesson` (M1L4): one heading per lesson, four bullets (Context / Problem / Rule / Applies to).

This file is not sorted, deduplicated, or reorganized when new entries land — it grows append-only so the chronology of discoveries is preserved.

## Skip /10x-tech-stack-selector and /10x-bootstrapper for the KMP + ESP32 + Supabase stack

- **Context**: Smart Chessboard greenfield project. Stack = Kotlin Multiplatform + Compose Multiplatform (Android / iOS / WasmJS) + ESP32 firmware in C++ via PlatformIO + ESP-IDF + Supabase as managed backend (no bespoke server). The decision was settled 2026-05-27 alongside `context/foundation/tech-stack.md`.

- **Problem**: The `/10x-tech-stack-selector` starter registry (`.agents/skills/10x-tech-stack-selector/references/starter-registry.yaml`) has no entry for this stack combination — mobile starters cover only Expo (React Native, JS) and Flutter (Dart). Running the selector force-fits to one of them. `/10x-bootstrapper` has a registry-sync validator (`scripts/validate-starter-registry-sync.mjs`) that refuses any `starter_id` not present in the registry. A new session's AI agent may attempt these skills without realizing they will fail or produce wrong scaffolding.

- **Rule**: For the bootstrap phase of this project, **do not invoke `/10x-tech-stack-selector` or `/10x-bootstrapper`**. `tech-stack.md` was hand-written at `context/foundation/tech-stack.md` with frontmatter compliant to `handoff-schema.md` plus extension sections. Bootstrap each sub-project manually via its official wizard: [kmp.jetbrains.com](https://kmp.jetbrains.com/) for `composeApp/`, `pio project init --project-option "framework=espidf"` for `firmware/`, `supabase init` for `supabase/`. Write `context/changes/bootstrap-verification/verification.md` by hand after scaffolding.

- **Applies to**: M1L2 (`/10x-tech-stack-selector`), M1L3 (`/10x-bootstrapper`). Other 10x-* skills (research, frame, plan, implement, impl-review from Module 2 onwards) work normally because they consume PRD + `tech-stack.md` as plain files and do not depend on the registry.

## Web target is digital-only — no BLE, no physical-board flow on web

- **Context**: Smart Chessboard mobile app has Android + iOS + WasmJS targets enabled in the KMP wizard from day 1 (`composeApp/`). FR-020 in `context/foundation/prd.md` is nice-to-have for web.

- **Problem**: Web Bluetooth has inconsistent cross-browser support — Chromium-only on desktop, no Safari on iOS, mobile browsers limited. Attempting to share the BLE board adapter between mobile and web targets would force expect/actual or extra abstractions for a feature the MVP does not need on web.

- **Rule**: Web target supports only the **digital subset**: pass-and-play (FR-003, FR-004), game history list (FR-015), replay (FR-016), post-game analysis (FR-017), and end-of-game marking (FR-018). It explicitly excludes physical-board mode (FR-008–FR-013), BLE transport, and reed-switch diagnostics (FR-011). The BLE library (Kable or expect/actual) is added only to `androidMain` + `iosMain`, never to `wasmJsMain`. This is documented in `prd.md` FR-020, Non-Goals, Implementation Decisions, and `tech-stack.md` mobile sub-project table.

- **Applies to**: any work on the mobile sub-project that touches BLE, physical-board mode, or reed-switch diagnostics. Also applies to architecture decisions (DI, Navigation routes) — web target should never have a route to "Physical game" screens.

## Web target needs COOP/COEP on BOTH the dev server and the production host — keep them identical

- **Context**: The web target (WasmJS) persists with Room 3.0 via `androidx.sqlite:sqlite-web`, which uses OPFS in a Web Worker and requires `SharedArrayBuffer`. `SharedArrayBuffer` is only available in a cross-origin-isolated context, which requires two response headers on the document: `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Embedder-Policy: require-corp`. The web host was decided in M1L5 as **Cloudflare Workers Static Assets** (`context/foundation/infrastructure.md`). As of this lesson, `SmartChessboard/webApp/build.gradle.kts` is bare (`wasmJs { browser(); binaries.executable() }`) with **no** devServer header config, so nothing sets these headers anywhere yet.

- **Problem**: These headers must be configured on **two independent surfaces** — (1) the Kotlin/Wasm webpack dev server used by `:webApp:wasmJsBrowserDevelopmentRun`, and (2) the production host (Cloudflare). If they drift (set in one place, missing in the other), OPFS-backed persistence works on one surface and **silently fails** on the other with "Cannot install OPFS: Missing SharedArrayBuffer and/or Atomics" — a classic "works on my machine" trap that won't surface until someone actually saves and reloads a game in a browser. Separately, once `COEP: require-corp` is on, every cross-origin subresource (public Supabase Storage objects, web fonts, analytics) is **blocked** unless it sends `Cross-Origin-Resource-Policy`.

- **Rule**: Treat COOP/COEP as **one config in two places that must match**. (1) Local dev: add `SmartChessboard/webApp/webpack.config.d/devServerHeaders.js` setting `config.devServer.headers` to both headers. (2) Production: ship a `_headers` file (`/*` → both headers) inside `productionExecutable/` so Cloudflare serves them — source it as a static resource the build copies, or write it in CI before `wrangler deploy`. (3) Any cross-origin asset must send `Cross-Origin-Resource-Policy: cross-origin` or be proxied / self-hosted same-origin — audit Supabase Storage, fonts, analytics. (4) **Verify, never assume**: save a game, reload, confirm persistence on the dev server AND on a deployed preview, across browsers. Never change the headers on only one surface.

- **Applies to**: any web-target work touching Room/persistence or the web build config; the M1L5 deploy step and the `web-deploy.yml` CI workflow; any future addition of a cross-origin asset to the web target.

## A static web bundle has no secrets — only the Supabase anon key may be built in

- **Context**: The web target deploys as static assets to Cloudflare Workers Static Assets (assets-only Worker, `context/foundation/infrastructure.md`). There is no server-side runtime on this surface — no `wrangler secret`, no environment indirection. Everything compiled into the wasm bundle is shipped to every visitor's browser.

- **Problem**: Anything baked into the bundle at build time is **public by definition**. A Supabase **service-role** key (or any privileged secret) placed in `commonMain`/web build config would be a full credential leak the moment the bundle is served. The per-user privacy guarantee (NFR: games private by default) rests on Supabase RLS scoped to the authenticated user, not on hiding a key.

- **Rule**: Only `SUPABASE_URL` and the Supabase **anon key** (RLS-protected) may reach the web bundle, injected at build time from GitHub Actions secrets. The **service-role key never** goes near the web build. Add a CI check that no service-role key pattern appears in `productionExecutable/`. The Cloudflare deploy token is separate (a scoped `CLOUDFLARE_API_TOKEN`, Workers Scripts edit for this one project only) and lives in GH Secrets / env — never committed to `.mcp.json` or pasted into chat.

- **Applies to**: web build configuration, secret injection in CI, RLS policy design in `supabase/`, and any agent session with deploy access.

## After adding an npm-backed dependency to a wasmJs/js target, actualize yarn.lock

- **Context**: Any change that adds (or removes) an npm-backed dependency on a Kotlin Multiplatform `wasmJs`/`js` target in `SmartChessboard/` — e.g. a Ktor JS engine (`ktor-client-js`) or any JS-interop library pulled in for `wasmJsMain`.

- **Problem**: The build fails on `:kotlinWasmStoreYarnLock` with `Lock file was changed. Run the kotlinWasmUpgradeYarnLock task` because the new dependency mutated `kotlin-js-store/yarn.lock`, which Gradle enforces against drift. In an IDE this surfaces as a failed "Gradle sync" and looks mysterious, but the root cause is the stale lock — not the IDE.

- **Rule**: After adding/removing a wasmJs/js npm-backed dependency, run `./gradlew kotlinWasmUpgradeYarnLock` once to actualize `kotlin-js-store/yarn.lock`, then rebuild. The agent can and should run this from the CLI — no IDE "sync" is required. (Separate but co-occurring DSL gotcha: in a Gradle Kotlin DSL build script, `java.util.Properties()` collides with the Gradle `java` plugin accessor and fails with `Unresolved reference 'util'` — use `import java.util.Properties` and call `Properties()` instead.)

- **Applies to**: implement, impl-review

## Presentation pattern: MVVM is the default; MVI only for genuinely event-heavy screens, with written justification

- **Context**: `tech-stack.md` deliberately deferred the UI architecture pattern (MVVM vs MVI), leaving it as Open Decision #7 to be settled after spiking a few screens. The S-01 slice (`google-signin-own-history`) built the first real screens — SignIn and History — as MVVM (`AuthViewModel` / `HistoryViewModel` exposing `StateFlow<UiState>`, intents as methods), and that pattern proved a clean fit for the gating state machine and list-load states. Clean Architecture layering (`domain/` / `data/` / `presentation/`) is independent of this choice and stays in force regardless.

- **Problem**: Without a committed default, every new screen reopens the MVVM-vs-MVI debate, and the codebase drifts toward an inconsistent mix where some screens model explicit intents/reducers and others hold mutable state holders — raising the cost of reading any unfamiliar screen and of moving code between them. MVI's ceremony (intent sealed classes, reducers, single immutable state) is real overhead that pays off only when a screen has a genuinely complex event/state machine.

- **Rule**: **MVVM is the default presentation pattern.** A ViewModel exposes UI state as `StateFlow<UiState>` and accepts intents as plain methods; it depends only on `domain/` interfaces (constructor-injected via Koin). **MVI is permitted only for screens with a genuinely complex, event-heavy state machine** (e.g. the live game board, BLE connection/pairing flows), and the choice **must be justified in that change's plan** — not adopted silently. Do not retrofit MVI onto simple form/list screens.

- **Applies to**: plan, implement, impl-review — any work adding or reworking a `presentation/` screen in the mobile sub-project. Resolves `tech-stack.md` Open Decision #7. Decided 2026-06-10 (S-01).

## Dependency injection: Koin KMP is the committed library — no parallel service locators

- **Context**: `tech-stack.md` left dependency injection as Open Decision #8 (Koin KMP vs a hand-rolled service locator), to be picked during onboarding. The S-01 slice wired the shared auth core through Koin: a single `SupabaseClient`, the data-layer repositories, and the ViewModels are all registered in `di/AppModules.kt`, with one `initKoin()` bootstrap called from each platform entry point (Android `Application`, iOS app start, web `main()`), and `koinViewModel()` resolving ViewModels at the composition root on all three targets.

- **Problem**: A solo MVP can drift into two coexisting wiring styles — some objects resolved through Koin modules, others reached via a hand-rolled singleton/service locator — which fragments object lifetimes, makes test substitution inconsistent (some boundaries fakeable via Koin overrides, others hard-wired), and obscures where a given dependency actually comes from.

- **Rule**: **Koin KMP is the committed DI library.** All new injectable code (clients, repositories, ViewModels, use cases) registers through Koin modules and resolves through Koin; do **not** introduce a parallel service locator, global singletons, or ad-hoc `object` holders for things that belong in a module. The single `initKoin()` entry point is the one bootstrap each platform calls. Tests prefer hand-written fakes (per tech-stack discipline) injected through Koin overrides or direct constructor calls, not a second locator.

- **Applies to**: plan, implement, impl-review — any work adding an injectable dependency in the mobile sub-project. Resolves `tech-stack.md` Open Decision #8. Decided 2026-06-10 (S-01).

## Native Google one-tap needs an Android OAuth client (SHA-1) and an always-visible browser fallback

- **Context**: Any mobile sub-project change adding native Google sign-in via supabase-kt `compose-auth` (Credential Manager / `googleNativeLogin`) — i.e. work touching the `data/supabase` client install + `presentation/auth/SignInScreen`. Web/iOS stay on the browser flow.

- **Problem**: Two distinct failure shapes seen in S-01 Phase 6 (2026-06-11). (1) Missing an OAuth client of type **Android** (package + signing SHA-1) in the same Google Cloud project → GMS `[16] Account reauth failed` / `DEVELOPER_ERROR`, and the flow collapses to a silent `NativeSignInResult.ClosedByUser`. (2) OnePlus/OPPO ROMs (OxygenOS/ColorOS 14) never render the system Credential Manager bottom sheet at all — `GetGoogleIdOperation` succeeds (token retrieved) but no UI shows and the result is a silent `ClosedByUser`; the identical APK shows the sheet fine on the emulator and a stock-Android tablet, proving it is a ROM bug, not app code.

- **Rule**: When wiring native Google one-tap via compose-auth: (a) create an OAuth client of type **Android** (package + each debug/release **SHA-1**) in the **same** Google Cloud project as the Web client ID, and keep the **Web** client ID as `serverClientId`/`GOOGLE_SERVER_CLIENT_ID`; and (b) **always** surface a visible browser-OAuth fallback (`signInWith(Google)` external browser) as the universal escape hatch. compose-auth's `fallback` lambda fires only when native is **unsupported** (iOS/web), NOT when native **errors/cancels** — so it cannot rescue OEM-broken devices.

- **Applies to**: implement, impl-review

## A commonMain parser is not green until it passes on a Native target — JVM-pass proves nothing about iOS

- **Context**: Any commonMain code in the KMP module that parses text (PGN, FEN, input formats) and compiles to Android/JVM + iOS/Native + WasmJS — `SmartChessboard/shared/src/commonMain`.

- **Problem**: S-02 Phase 1 (2026-06-12): the PGN parser's SAN/tag regexes (optional groups + `matchEntire`) passed 20/20 tests on `:shared:testAndroidHostTest`, then failed 16/20 on `:shared:iosSimulatorArm64Test` — Kotlin/Native's regex engine resolves optional-group backtracking differently from the JVM. The fix was a regex-free, hand-rolled deterministic decomposition.

- **Rule**: Regexes are permitted, but no parsing logic in commonMain counts as "green" until its test suite passes on a Native target (`:shared:iosSimulatorArm64Test`), not just on the JVM host. Run the Native suite before declaring any text-parsing step complete.

- **Applies to**: implement, impl-review
