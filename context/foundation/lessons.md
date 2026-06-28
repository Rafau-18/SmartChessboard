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

## terrakok navigation3-browser binds once per page session — sign-out → sign-in silently loses browser history sync

- **Context**: WasmJS target using `com.github.terrakok:navigation3-browser:1.1.0` for browser Back/Forward integration. The binding lives inside the SignedIn branch of `App.kt` (S-02 Phase 4). Relevant location: `shared/src/wasmJsMain/.../BrowserNavigation.wasmJs.kt` + upstream library one-shot flag.

- **Problem**: The upstream library sets a process-global `BrowserHistoryIsInUse` flag via `compareAndSet` that is never reset — no `try/finally`, no `onDispose`. When `SignedIn` is disposed on sign-out and recreated on sign-in, the new `rememberNavBackStack` attempts to bind again; `compareAndSet` fails silently. The library logs a warning and returns. For the rest of that page session, browser Back/Forward no longer drive the nav stack and the URL fragment stays stale. Degradation (not a crash or leak); page reload fully recovers.

- **Rule**: Accept this constraint at MVP scope — sign-out → sign-in within one web page session is a rare path and page reload is the recovery. Do not attempt to fix by hoisting a long-lived back stack above the auth gate; it restructures S-01's deliberate simplicity and the saved-state interaction is unverified. Track via upstream issue (flag reset on cancellation). If this becomes unacceptable post-MVP, revisit Fix B from `context/changes/replay-seeded-games/reviews/impl-review-phase-4.md`.

- **Applies to**: impl-review, implement — any wasmJs work touching `App.kt` auth gating or browser-history binding.

## Navigation 3 multiplatform is the committed navigation library — one mechanism, serializable NavKeys, separate browser-history artifact

- **Context**: `App.kt` shipped the first push navigation in S-01 as a bare `SessionState` switch (Restoring / SignIn / History) with no back stack — the navigation library choice was deliberately deferred to S-02. S-02 Phase 4 introduced real stack navigation (History → Replay → back) and had to pick a library that works across Android / iOS / WasmJS.

- **Problem**: Without a committed navigation library the app drifts toward ad-hoc mechanisms — a hand-rolled state switch on one screen, a third-party nav lib on another — which fragments back-stack handling, breaks system-back / iOS edge-pan consistency, and (on iOS/wasm, which have no JVM reflection) crashes on saved-state restore if routes aren't serializable. The browser Back/Forward integration on web is also easy to get wrong: the binding is **not** in the base Nav3 artifact.

- **Rule**: **Jetpack Navigation 3 multiplatform (JetBrains port) is the committed navigation library.** Pins: `org.jetbrains.androidx.navigation3:navigation3-ui` at the **stable `1.1.1`** (catalog `navigation3 = "1.1.1"`; `navigation3-common` arrives transitively); the companion `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` pinned via the **existing** `androidx-lifecycle` version ref (`2.11.0-beta01`) so the whole `org.jetbrains.androidx.lifecycle` group resolves to one version (mixing lifecycle artifact versions is unsupported). Routes are `@Serializable` `NavKey`s with **explicit polymorphic registration** (`Routes.kt`) — iOS/wasm have no reflection, so this is a **permanent multiplatform constraint, not a stability caveat**; omitting it crashes on state save/restore. Web browser-history is **wired** via the **separate** `com.github.terrakok:navigation3-browser:1.1.0` artifact (`wasmJsMain` only; route ↔ URL fragment; **NOT** bundled in `navigation3-ui 1.1.1`) so browser Back/Forward maps to the nav stack. A designed/shareable URL or per-ply deep-link scheme is the only web-routing piece intentionally left out. **Do not introduce a second navigation mechanism** (no parallel hand-rolled stacks, no other nav lib).

- **Applies to**: plan, implement, impl-review — any work adding or reworking navigation / a `presentation/` screen entry in the mobile sub-project. Resolves the navigation-library deferral from S-01. Decided 2026-06-12 (S-02). See also the [terrakok navigation3-browser binds-once] gotcha above for the web sign-out → sign-in caveat.

## External eval providers validate FEN strictly — emit en passant only when capturable, and short-circuit terminal positions before any request

- **Context**: S-03 (`post-game-evals-in-replay`) sends a FEN derived from `Position` (`domain/chess/Fen.kt`, `Position.toFen()`) to the `lichess-eval` Edge Function, which proxies Lichess Cloud Eval and Chess-API.com. Smoke-tested 2026-06-12.

- **Problem**: Chess-API.com validates FEN strictly: a FEN whose en passant field names a square no enemy pawn can actually capture onto returns `INVALID_FEN_VALIDATION_ERROR`, and a *terminal* position (checkmate/stalemate on the board) returns `{"type":"error","error":"INVALID_INPUT"}` instead of an eval. Naive FEN emission (print the en passant target whenever a pawn just advanced two) produces FENs a strict validator rejects, and naively asking a provider to evaluate a mate/stalemate board wastes a round-trip and returns an error that's easy to mis-handle as an outage.

- **Rule**: `Position.toFen()` emits the en passant square **only when an enemy pawn can pseudo-legally capture onto it**, else `-` — this is general FEN hygiene (the serializer stays faithful and S-04-reusable), not an eval-specific hack. Terminal positions are detected client-side via `ChessRules.status()` and short-circuit to a `Terminal` UI state — **never sent to a provider**. The function treats a provider's "cannot evaluate" answer (Chess-API `INVALID_INPUT`) as provider-no-eval, not a 5xx. FEN cache keys normalize the halfmove/fullmove counters to `0 1` so identical positions reached at different move numbers share one cache entry.

- **Applies to**: plan, implement, impl-review — any work serializing a position to FEN, or adding/replacing an eval provider.

## Edge Functions are unit-tested with injected `fetch` + cache fakes — no real egress in `deno test`

- **Context**: `supabase/functions/lichess-eval/` — the project's first Edge Function (Deno 2 + TypeScript), a multi-step decision chain (cache lookup → Lichess → Chess-API → negative-cache) with provider HTTP calls and a Postgres cache. Convention set by `supabase/AGENTS.md`.

- **Problem**: A function whose behavior is defined by network responses and DB state can't be tested deterministically if its tests hit real providers and the real database — they're flaky, slow, rate-limited, and can't exercise the failure paths that matter most (404 → fallback, 429 → rate-limited, 5xx → `502`, negative cache, mate-sign mapping, FEN normalization, CORS preflight).

- **Rule**: Structure the function so `fetch` and the cache client are **injected dependencies** (a `Deps` object), and have `*_test.ts` pass **fakes** — zero real egress. The full decision chain (both cache TTLs, fallback order, negative cache, `400/429/502` mapping, mate mapping from both provider shapes incl. Black-mates, counter-normalized cache keys, `OPTIONS` preflight) is covered by `deno test`. Real-egress verification (opening → `lichess`, middlegame → `chess-api`, repeat → cached, row lands) is a **manual gate against `supabase functions serve`**, deliberately *not* part of `deno test`.

- **Applies to**: implement, impl-review — any new or changed Edge Function in `supabase/functions/`.

## An Edge Function called from the web (supabase-kt) must echo the CORS preflight's requested headers — a static allow-list goes stale and breaks only on web

- **Context**: `supabase/functions/lichess-eval/handler.ts`, invoked from the WasmJS target via supabase-kt `functions.invoke`. Surfaced during the S-03 Phase 4 manual gate (2026-06-12).

- **Problem**: supabase-kt attaches its own headers (notably `x-region`) to *every* `functions.invoke`, which trips a CORS preflight on the browser. A **static** `Access-Control-Allow-Headers` allow-list that doesn't name `x-region` makes the browser reject the request — surfacing as a generic web **"Failed to fetch"** while Android/iOS (which don't preflight) work fine, so the breakage is web-only and easy to miss. Compounding it: on the wasm client a fetch failure surfaces as `kotlin.Error`, not `Exception`, so a `catch (e: Exception)` silently misses it.

- **Rule**: In the `OPTIONS` handler, **echo the incoming `Access-Control-Request-Headers` back as `Access-Control-Allow-Headers`** (with a static fallback when the preflight names none) instead of hardcoding the client's header set — client libraries add headers and any fixed list goes stale the moment one appears. Attach `Access-Control-Allow-Origin` to *every* response. On the wasm client, wrap `functions.invoke` in `catch (Throwable)` (not just `Exception`) so fetch failures map to the "temporarily unavailable" state. COOP/COEP on the web host does **not** block these CORS fetches (CORP applies to embedded subresources), so no `_headers` change is needed.

- **Applies to**: implement, impl-review — any Edge Function invoked from the web target via supabase-kt, and any wasm-side error handling around `functions.invoke`.

## A retained screen's "refresh on return" must be push-driven (a data-layer signal), not a composition or lifecycle effect

- **Context**: A retained list screen (S-05 `History`) must re-fetch after returning from a child that created or finished a row — Nav3 with `rememberViewModelStoreNavEntryDecorator`, so the ViewModel and (often) its composition survive the push/pop. Surfaced in S-05 three-surface E2E (2026-06-17).

- **Problem**: `LaunchedEffect(Unit) { viewModel.refresh() }` re-fires only on **composition re-entry**, which diverges across targets — a covered Nav3 entry's composition is disposed on Android/web (so it re-fires) but **retained on iOS** (so it never re-fires; the list stayed stale until app relaunch). Switching to `LifecycleEventEffect(Lifecycle.Event.ON_RESUME)` was worse: `LocalLifecycleOwner` resolves to the **app** lifecycle, not the per-entry one, so `ON_RESUME` does not fire on in-app navigation on **any** platform (it regressed Android too). Neither composition-re-entry nor app-lifecycle is a reliable "screen became active again" signal in KMP + Nav3.

- **Rule**: Drive the refresh from a **push signal the data layer emits**, collected in the retained ViewModel's `viewModelScope` (alive for the whole session). The repository exposes `changes: SharedFlow<Unit>`, emitted after mutations that change what the screen shows (`createGame`/`finishGame` — **not** per-move `updatePgn`); `init { viewModelScope.launch { repo.changes.collect { refresh() } } }`. This is lifecycle/composition-independent, so it behaves identically on Android/iOS/web. Do not reach for a UI effect to express "reload when I come back."

- **Applies to**: implement, impl-review — any retained list/detail screen that must reflect changes made elsewhere in the same session.

## Web browser Back needs `HierarchicalBrowserNavigation`, not `Chronological`, for app-style stacks that replace the top

- **Context**: WasmJS maps the Nav3 back stack to browser history via terrakok `navigation3-browser` (`bindBrowserNavigation`, `wasmJsMain`). S-05 added a Play→Replay "Analyse" transition; S-02 already had NewGame→Play — both are *replace* operations (`removeLastOrNull()` + `add()`).

- **Problem**: `ChronologicalBrowserNavigation` records **every** stack state in chronological order, so a replace leaves the replaced-away entry reachable via browser **Back** — on web you landed back on the player-name form (after creating a game) or on the frozen finished board (after Analyse). Native (Android/iOS) was unaffected because system Back follows the Nav3 stack, not a chronological browser log.

- **Rule**: For an app-style hierarchical stack use **`HierarchicalBrowserNavigation { fragmentForTop }`** (same library) — it mirrors the **current** stack via the `NavigationEvent` dispatcher (it drives `NavDisplay`'s `onBack`), so browser Back pops the live stack and replaced entries are gone. Call it as a sibling **before** `NavDisplay` (the `LocalNavigationEventDispatcherOwner` it needs is in scope there; matches the library sample). Trade-offs accepted at MVP: no URL→state restore on reload (deep links remain out of scope), and the one-shot `BrowserHistoryIsInUse` bind limitation is unchanged (see the binds-once gotcha above).

- **Applies to**: implement, impl-review — any wasm navigation change, especially flows that replace the top entry.

## Every wasm Supabase/Ktor network failure is a `Throwable` (`kotlin.Error`), not an `Exception` — catch `Throwable` at every call site

- **Context**: Generalises the eval-specific CORS lesson above. **Any** Supabase/Ktor call on the WasmJS target — Postgrest CRUD, auth, `functions.invoke` — whose failure must be handled gracefully. Surfaced offline across S-05 flows (2026-06-17).

- **Problem**: On wasm a fetch failure surfaces as `io.ktor…JsError`, a **`kotlin.Error`**, which `catch (e: Exception)` silently misses → it escapes as an uncaught coroutine exception and **crashes the app** (offline, any click that hit the network crashed). This bit eight sites at once — History (load + refresh), Play/Replay/NewGame loads, NewGame create, Auth sign-in/out, and the `GameAutoSaver` flush — not just the eval path.

- **Rule**: At every wasm-reachable network call site, **`catch (Throwable)`** (rethrowing `CancellationException` first), not `catch (Exception)`, and map to the screen's Error/failed state (or swallow, for a best-effort flush). There is no single boundary that converts this — it is a per-call-site discipline. Cover it with a test that throws a non-`Exception` `Throwable` and asserts the graceful state.

- **Applies to**: implement, impl-review — any ViewModel / use-case coroutine that makes a Supabase/Ktor call reachable from the web target.

## A terminal/offline flush needs its own retry — "the next move re-enters" does not hold once the game is closed

- **Context**: `GameAutoSaver.sync` is a best-effort cloud flush with bounded retry; its design relies on "the next accepted move's sync re-enters" after the bounded window gives up (S-04 §6.2). S-05 added the finish flush.

- **Problem**: A **finished** game accepts no further moves, so once the bounded retry gave up nothing re-triggered the flush — on a reconnect slower than the ~7s window the "Saving…" indicator spun forever (until the next load's `reconcile` re-flushed). The bounded-retry assumption silently breaks for any terminal or idle state with no future activity to piggyback on.

- **Rule**: A terminal flush (a *finished* journal entry) must **keep retrying** (backoff capped at the last delay) until it lands or the screen closes (the coroutine is cancelled). Keep the in-progress path **bounded** — a single long-lived in-progress loop could race a newer move's sync and overwrite the cloud with a stale PGN. Durability stays backstopped by `reconcile` re-flushing a journaled-but-unsynced finish on the next load.

- **Applies to**: implement, impl-review — any best-effort sync whose re-trigger depends on future user activity a terminal state will not produce (S-06 physical finish included).

## Engine move-geometry mirrored outside `domain/chess` must be SYNC-commented on both sides

- **Context**: S-06's `SequenceInterpreter` (`domain/board`) resolves a reed-switch lift/place stream into one legal move by matching the observed occupancy signature against each candidate move's *footprint* — squares vacated / filled / captured-in-place, plus the en-passant captured square and the castling rook squares. That footprint geometry is the same arithmetic `ChessRules.applyMove` (`domain/chess`) already does when it relocates the rook on castling and removes the en-passant pawn off a different square than the landing one.

- **Problem**: The interpreter could not *reuse* `applyMove`'s geometry — `applyMove` is `internal` and returns a whole `Position`, and the SAN/PGN derivations (`SanWriter`, `PgnParser`) are `private` and return strings/Booleans; none yields a square-set footprint. So `footprintOf` re-derives the castle/en-passant geometry by hand: a third independent copy of the same move arithmetic. If `applyMove`'s castling or en-passant rules ever change, the hand-mirror drifts silently and physical-move resolution breaks **with no compiler error** — the two sites look unrelated, so nothing flags the coupling.

- **Rule**: When move geometry is hand-mirrored outside `domain/chess` (because the engine's own helper isn't reusable for the shape you need), **SYNC-comment both sides** — a comment on `applyMove` naming the mirror (`SequenceInterpreter.footprintOf`) and a comment on the mirror naming its source (`applyMove`), each saying "change both together." The compiler can't link them, so the comment is the only guard. If a second consumer of the same geometry appears, that is the threshold to stop duplicating and extract a shared `squaresTouched(position, move)` helper instead.

- **Applies to**: implement, impl-review — any code outside `domain/chess` that re-derives move geometry (footprints, touched squares, captured-square offsets, castling rook moves). Surfaced 2026-06-13 (S-06 phase-1 impl-review), captured at slice completion.

## A mid-game physical resume/reconnect E2E must inject occupancy AND settle the connect burst with `runCurrent`, never `advanceUntilIdle`

- **Context**: An emulator-driven end-to-end test that opens an *in-progress* physical game and exercises the board-confirmation gate — S-08's `PhysicalResumeEndToEndTest` (resume after restart), and the same shape FR-012/S-09 will use for BLE reconnect-reconcile. The harness builds a real `PhysicalPlayViewModel` over an `EmulatedBoard` on `backgroundScope`, like `PhysicalRecoverEndToEndTest`.

- **Problem**: Two traps compound. (1) A fresh `EmulatedBoard` reports **start-position** occupancy on connect (`EmulatedBoard.connect()` emits `snapshotEvent()` of its `initialOccupancy`, default the start layout), so a mid-game resume against the default *always* mismatches — a test that relies on the default can never exercise the auto-resume-on-match path. (2) A resume **mismatch** auto-opens diagnostics, which arms the ~10 Hz `DIAGNOSTIC` snapshot stream on `backgroundScope`; a subsequent `advanceUntilIdle()` then advances virtual time forever re-running that periodic `delay`, so the test **hangs**. The recover suite's `connectedGame()` never hit (2) because its start-position fixture *matches* on connect (no stream armed), so it could use `advanceUntilIdle()` after connect — but the resume default path mismatches, so copying that harness verbatim deadlocks.

- **Rule**: (a) **Inject** the board's `initialOccupancy` per scenario — `parsePgn(pgn).positions.last().toOccupancy()` for the match case, that value minus one piece (`and (1L shl sq).inv()`) for mismatch / promotion-lifted-at-kill — never rely on the emulator default for a mid-game position. (b) Settle `load()` with `advanceUntilIdle()` **while the board is still disconnected** (no stream to spin), then settle the connect burst with **`runCurrent()`** (runs everything scheduled at the current instant without advancing the diagnostic clock). Deliver a restore with a single `tick()` (`advanceTimeBy(150.ms); runCurrent()`); the gate-clearing match sends `SetMode(GAME)` which **stops** the stream, so the post-resume move can safely settle with `advanceUntilIdle()` again. Assert "no accepted move lost" via the journal's dirty-write count (`saveLog.count { it.third }`), which a resume must leave at 0 until the first real post-resume move.

- **Applies to**: implement, impl-review — any emulator-driven E2E that loads an in-progress physical game or exercises a board-confirm gate (S-08 resume, S-09/FR-012 reconnect). Surfaced 2026-06-21 (S-08 Phase 2).
