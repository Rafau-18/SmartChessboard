# Google Sign-In & Own Game History (S-01) Implementation Plan

## Overview

Implement the first user-facing slice of Smart Chessboard: a player signs in with Google through Supabase Auth (account auto-created on first sign-in), can sign out, and sees their own private, chronologically ordered game list. Unauthenticated users reach no game views. The slice lands the `games` table with RLS (transcribed from the contract), commits two deferred architecture decisions (MVVM-by-default, Koin KMP), and — per planning decision — wires the same auth + history surface on the web target in addition to Android and iOS.

## Current State Analysis

- `SmartChessboard/` is the KMP wizard skeleton: `App.kt` renders a demo greeting + Supabase connectivity probe; no domain/data/presentation layers, no navigation, no DI ([App.kt](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt)).
- supabase-kt 3.6.0 (BOM) with **only `postgrest-kt`** installed ([shared/build.gradle.kts:89](../../../SmartChessboard/shared/build.gradle.kts)); the Auth module is absent. Client is created anonymously in [SupabaseProbe.kt](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/SupabaseProbe.kt) with URL/anon key injected via BuildKonfig from `local.properties` / `-P` properties — this injection pattern carries over unchanged.
- `supabase/` has one migration (`position_evals`); **no `games` table**. `config.toml` has no Google provider section. The cloud project is live (probe confirmed against it; web shell deployed 2026-06-01).
- `lifecycle-viewmodel-compose` + `lifecycle-runtime-compose` are already commonMain dependencies — MVVM needs no new UI-layer library.
- Contract settles design: `docs/reference/contract-surfaces.md` §2.2/§2.4/§2.5/§2.6 fully specify the `games` schema, RLS policies, index, and trigger; §4 specifies the OAuth flow (Web-type client ID, Supabase callback, deep link, JWT lifecycle, sign-out). §3.2 specifies the list query shape (no explicit `user_id` on calls — RLS scopes everything).
- `lessons.md` constraints in force: wasmJs build must stay green (deployed shell), yarn.lock must be re-actualized if a wasm npm-backed dep changes, only the anon key may reach the web bundle.

## Desired End State

A player on Android, iOS, or web opens the app and lands on a sign-in screen; "Continue with Google" completes the OAuth round trip and returns to the app signed in; the History screen shows their own (initially empty) chronological game list scoped by RLS; sign-out returns to the sign-in screen; the session survives app restarts. `supabase test db` proves RLS isolation; VM unit tests prove the gating state machine; the two architecture decisions are recorded in `lessons.md` / `tech-stack.md`, and the final deep-link scheme is written back into the contract.

### Key Discoveries:

- Contract §2 is transcription-ready SQL (policies and trigger are given verbatim) — the migration is not a design task.
- Contract §4.2 explicitly allows browser-based OAuth ("system browser…"); supabase-kt's Auth plugin implements exactly this flow with deep-link callbacks on Android/iOS and redirect-based session pickup on web.
- BuildKonfig injection (`SUPABASE_URL` / `SUPABASE_ANON_KEY`) already works across all three targets — auth adds no new secret surface (anon key only, per lessons.md).
- supabase-kt's default session persistence is multiplatform-settings (SharedPreferences / NSUserDefaults / localStorage), **not** the Keychain/Keystore the contract §4.2 step 5 describes — see Critical Implementation Details.

## What We're NOT Doing

- No game creation, play, replay, or seeding — S-02/S-04 (the list legitimately renders its empty state).
- No Room / local persistence / offline-first sync — contract §3.4 applies from S-04; this slice reads the list directly from PostgREST.
- No Navigation 3 — the root is a two-state switch on session state (SignedOut → SignIn, SignedIn → History); the navigation library enters in S-02 with the first push navigation (History → Replay).
- No Apple Sign In (contract §4.5, post-MVP), no account deletion, no profile UI beyond a sign-out affordance.
- No full web digital subset — FR-020 stays parked; web gets exactly the auth + empty-history surface that falls out of commonMain (a deliberate, recorded sliver of FR-020 pulled forward).
- No Edge Function work (`lichess-eval` belongs to S-03).
- No CI workflows — all verification commands run locally (CI remains parked per roadmap).

## Implementation Approach

Backend-first (migration + RLS + pgTAP so the privacy boundary is provable before any UI exists), then the manual provider-console gates (user homework, front-loaded so nothing blocks later), then the shared auth core behind interfaces (Koin + MVVM, fakes-first tests), then per-platform wiring and screens verified end-to-end on all three surfaces, then decision write-back into the foundation docs and contract. The optional native Android one-tap rides last and can be skipped without affecting acceptance.

Architecture commitments made in this plan (to be recorded in Phase 5): **MVVM by default, MVI permitted for complex event-heavy screens with written justification; Koin KMP for dependency injection.**

## Critical Implementation Details

- **Session-storage deviation from contract §4.2.** supabase-kt's default `SettingsSessionManager` persists sessions via multiplatform-settings (SharedPreferences on Android, NSUserDefaults on iOS, localStorage on web) — not Keychain/Keystore as §4.2 step 5 states. Accepted for the small-circle MVP; Phase 5 updates §4.2 to match reality and notes Keychain/Keystore hardening as post-MVP. Do not silently leave the contract wrong.
- **Deep-link scheme is locked here as `com.smartchessboard://callback`** (the contract's own example). Phase 5 records it back into §4.1 as required by the contract's "recorded back here" clause.
- **OAuth must run in an external browser / custom tab, never an embedded WebView** — Google blocks WebView OAuth. supabase-kt's default external-browser behavior satisfies this; don't "improve" it into a WebView.
- **Web redirect resets the app.** On wasm the OAuth round trip is a full page navigation away and back — all in-memory state is lost. On startup the app must let the Auth plugin consume the callback URL (PKCE code / fragment) **before** concluding SignedOut, or the user will flash back to the sign-in screen after a successful login. Model an explicit `Loading/Restoring` auth state.
- **wasm yarn.lock lesson applies**: after dependency changes, if `:kotlinWasmStoreYarnLock` fails, run `./gradlew kotlinWasmUpgradeYarnLock` once and rebuild (see `context/foundation/lessons.md`).
- **Ordering**: Phase 3 (code) does not depend on Phase 2 (consoles) — but Phase 4's manual E2E does. Phase 2 can be done by the user while Phase 3 is implemented.

## Phase 1: Backend — `games` schema, RLS, pgTAP

### Overview

Land the `games` table exactly as the contract specifies and prove the RLS privacy boundary with pgTAP. (The `config.toml` provider-parity edit lives in Phase 2 — it depends on credentials created there.)

### Changes Required:

#### 1. Migration: `games` table

**File**: `supabase/migrations/<timestamp>_games.sql` (create via `supabase migration new games`)

**Intent**: Transcribe contract §2.2 (columns/constraints), §2.4 (enable RLS + the four `games_*_own` policies, verbatim), §2.5 (`games_user_created_idx on (user_id, created_at desc)`), §2.6 (`set_updated_at()` function + trigger). No design decisions — the contract is the source.

**Contract**: Table `public.games` with `id uuid PK default gen_random_uuid()`, `user_id uuid NOT NULL FK → auth.users(id) ON DELETE CASCADE`, `created_at`/`updated_at timestamptz NOT NULL default now()`, `mode CHECK in ('digital','physical')`, `status CHECK in ('in_progress','finished')`, `result CHECK in ('white','black','draw', NULL)`, `pgn text NOT NULL default ''`, `white_label`/`black_label text NOT NULL` with defaults. RLS enabled; four per-CRUD owner policies on `auth.uid() = user_id`.

#### 2. pgTAP tests for RLS

**File**: `supabase/tests/games_rls.test.sql` (location per `supabase test db` convention — verify the CLI picks it up; adjust path if the CLI expects a subfolder)

**Intent**: Prove the privacy boundary mechanically: structural assertions (RLS enabled on `games`, all four policies present, index exists) plus behavioral assertions impersonating two seeded `auth.users` via `request.jwt.claims` — user A sees only their own rows, cannot insert rows with B's `user_id`, cannot update/delete B's rows; the `anon` role sees nothing.

**Contract**: `supabase test db` exits 0 with all assertions passing against a `supabase db reset` database.

### Success Criteria:

#### Automated Verification:

- Migrations apply cleanly from scratch: `supabase db reset` (local stack)
- pgTAP suite passes: `supabase test db`

#### Manual Verification:

- Migration pushed to the cloud project (`supabase db push`) and `games` table + 4 policies visible in the Dashboard

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 2: Provider configuration (manual console gates)

### Overview

Create the OAuth client and configure the Supabase cloud project, per contract §4.1 — mostly non-agent-drivable console work (checklist for the user; the agent prepares exact values), plus the small `config.toml` parity edit that depends on the credentials created here.

### Changes Required:

#### 1. Google Cloud Console

**Intent**: First configure the **OAuth consent screen** (app name, support email, scopes `openid`/`email`/`profile`; decide publishing status — in **Testing** status only allowlisted test users can sign in, so add both Google accounts used for the Phase 4 privacy check as test users, or publish to Production). Then create an OAuth 2.0 Client ID of type **Web application**; authorized redirect URIs = `https://<project-ref>.supabase.co/auth/v1/callback` (cloud) and `http://127.0.0.1:54321/auth/v1/callback` (local stack parity). Record Client ID + Secret (into a password manager and `supabase/.env.local` for the local stack — never into git).

**Contract**: Contract §4.1 setup, step 1, extended with consent-screen setup and the local-stack callback URI.

#### 2. Supabase Dashboard

**Intent**: Authentication → Providers → Google: enable, paste Client ID + Secret. Authentication → URL Configuration: add `com.smartchessboard://callback` (mobile deep link), `https://smart-chessboard-web.<subdomain>.workers.dev` (deployed web), and `http://localhost:8080` (web dev server) to allowed redirect URLs.

**Contract**: Contract §4.1 setup, steps 2–3, extended with the two web origins (web wiring decided 2026-06-10).

#### 3. Local-stack provider parity

**File**: `supabase/config.toml`

**Intent**: Add the `[auth.external.google]` block (enabled, `client_id`/`secret` via `env(...)` substitution from gitignored `supabase/.env.local`) and extend `additional_redirect_urls` with the mobile deep link and web dev-server origin, so `supabase start` mirrors the cloud configuration. Secrets never enter git (lessons.md rule). Done here (not Phase 1) because the env values only exist once the console work above produces them.

**Contract**: `[auth.external.google]` enabled; redirect allowlist contains `com.smartchessboard://callback` and `http://localhost:8080`.

### Success Criteria:

#### Automated Verification:

- (none — console work)

#### Manual Verification:

- Google provider shows as enabled in Supabase Dashboard
- Redirect allowlist contains the deep link and both web origins
- `supabase/.env.local` holds the Google client credentials for the local stack
- OAuth consent screen configured; while in Testing status, both privacy-check Google accounts are registered test users
- `config.toml` carries the `[auth.external.google]` block and extended `additional_redirect_urls`

---

## Phase 3: Shared auth core — Koin, Auth plugin, repositories, ViewModels, tests

### Overview

Introduce the Clean Architecture skeleton in `commonMain` (`domain/` / `data/` / `presentation/` per `SmartChessboard/AGENTS.md`), install the supabase-kt Auth module, commit Koin, and cover the state machine with fakes-first unit tests. No platform glue yet — everything in this phase compiles and tests on all targets.

### Changes Required:

#### 1. Dependencies

**File**: `SmartChessboard/gradle/libs.versions.toml`, `SmartChessboard/shared/build.gradle.kts`

**Intent**: Add `io.github.jan-tennert.supabase:auth-kt` (version via existing supabase BOM) to commonMain; add Koin (BOM + `koin-core` in commonMain, `koin-compose-viewmodel` for `koinViewModel()` resolution) — latest stable Koin 4.x, pinned in the version catalog.

**Contract**: `libs.supabase.auth` and `libs.koin.*` accessors exist; `:shared` compiles on android/ios/wasmJs after `kotlinWasmUpgradeYarnLock` if the lock drifts.

#### 2. Supabase client provider

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/supabase/SupabaseClientProvider.kt` (replaces the ad-hoc client in `SupabaseProbe.kt`; delete the probe file and its `App.kt` usage when the new root UI lands in Phase 4)

**Intent**: Single `SupabaseClient` for the app with `Postgrest` + `Auth` installed; Auth configured with the deep-link `scheme = "com.smartchessboard"`, `host = "callback"`; keep the existing BuildKonfig empty-credentials guard pattern (clear error instead of a crash when keys are missing).

**Contract**: One client instance provided through Koin; Auth plugin uses PKCE-default settings and the SDK's default session persistence (see Critical Implementation Details).

#### 3. Domain layer

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/auth/AuthRepository.kt`, `domain/games/GamesRepository.kt`, `domain/games/GameSummary.kt`

**Intent**: Pure-Kotlin interfaces and models, no SDK types leaking. `AuthRepository`: `sessionState: Flow<SessionState>` (`Restoring | SignedOut | SignedIn(userId)`), `signInWithGoogle()`, `signOut()`. `GamesRepository`: `listMyGames(): List<GameSummary>`. `GameSummary`: id, createdAt, mode, status, result, whiteLabel, blackLabel — mirroring contract §2.2 columns the list renders.

**Contract**: `domain/` has zero dependencies on Compose/Supabase (AGENTS.md layering rule); the `Restoring` state exists explicitly (web redirect pickup — see Critical Implementation Details).

#### 4. Data layer implementations

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/auth/SupabaseAuthRepository.kt`, `data/games/SupabaseGamesRepository.kt`

**Intent**: `SupabaseAuthRepository` maps the SDK's session status flow into `SessionState` and delegates sign-in (`signInWith(Google)` — external browser / redirect per platform) and sign-out (§4.4). `SupabaseGamesRepository` implements the §3.2 list operation: select from `games` ordered by `created_at desc`, **no explicit user filter** — RLS scopes rows.

**Contract**: Mobile never passes `user_id` (§3.2); list decodes into `GameSummary` DTOs via kotlinx.serialization.

#### 5. Presentation layer (MVVM)

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/auth/AuthViewModel.kt`, `presentation/history/HistoryViewModel.kt`

**Intent**: `AuthViewModel` exposes the session state + sign-in/sign-out intents + error surface (OAuth cancelled/failed → friendly retry state, not a crash). `HistoryViewModel` loads the list on entering SignedIn: `Loading / Empty / Loaded(games) / Error(retryable)` states.

**Contract**: ViewModels depend only on domain interfaces (constructor-injected via Koin); state exposed as `StateFlow<UiState>`.

#### 6. Koin modules

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/di/AppModules.kt` plus a small `initKoin()` entry point callable from each platform

**Intent**: Modules for data (client, repositories) and presentation (ViewModels); `initKoin()` is the single bootstrap the three platforms call (Android `Application`, iOS entry, web `main()`).

**Contract**: `koinViewModel()` resolves both ViewModels at the composition root on every target.

#### 7. Unit tests (fakes-first)

**File**: `shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/AuthViewModelTest.kt`, `HistoryViewModelTest.kt`, with hand-written `FakeAuthRepository` / `FakeGamesRepository`

**Intent**: Cover the gating state machine: Restoring → SignedOut on no session; Restoring → SignedIn on restored session; sign-in success/cancel/failure transitions; sign-out returns to SignedOut; History states for empty list, non-empty list (chronological order respected from repo), and error → retry. Replace the placeholder `SharedCommonTest.kt`.

**Contract**: `:shared:testAndroidHostTest` and `:shared:wasmJsTest` green; no mocking library needed (fakes only, per tech-stack discipline).

### Success Criteria:

#### Automated Verification:

- Unit tests pass on JVM host: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- Unit tests pass on wasm: `… ./gradlew :shared:wasmJsTest --console=plain --no-daemon`
- iOS test task compiles & passes: `… ./gradlew :shared:iosSimulatorArm64Test --console=plain --no-daemon`
- Formatting clean: `ktlint` over `SmartChessboard/` (PostToolUse hook auto-formats; CI-style check via `ktlint` from module root)

#### Manual Verification:

- (none — platform glue and screens arrive in Phase 4)

---

## Phase 4: UI + platform wiring — screens, deep links, web redirect, E2E

### Overview

Replace the wizard `App.kt` with the real root: SignIn and History screens driven by session state; wire deep links on Android/iOS and the redirect flow on web; verify the full round trip on all three surfaces, including a two-account privacy check.

### Changes Required:

#### 1. Root composition

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt` (rewrite), new `presentation/auth/SignInScreen.kt`, `presentation/history/HistoryScreen.kt`

**Intent**: Root observes `SessionState`: `Restoring` → splash/progress; `SignedOut` → SignInScreen (app name + "Continue with Google" + error/retry state); `SignedIn` → HistoryScreen (top bar with sign-out, chronological list rendering date / mode / status-result / player labels, explicit empty state "No games yet — your first game arrives in the next milestone", loading and error-with-retry states). Delete `Greeting*.kt` demo usage from the flow (`SupabaseProbe` UI goes away with it).

**Contract**: No game views reachable while SignedOut (PRD Access Control); list order is `created_at desc` as delivered by the repository.

#### 2. Android deep link + Koin bootstrap

**File**: `SmartChessboard/androidApp/src/main/AndroidManifest.xml`, `MainActivity.kt`, new `Application` class

**Intent**: Register the `com.smartchessboard://callback` intent-filter on `MainActivity`; hand incoming intents to the Supabase Auth deep-link handler (both cold start and `onNewIntent`); call `initKoin()` from the `Application`.

**Contract**: Contract §4.2 steps 2–6 on Android — browser round trip ends with the app foregrounded and session stored. `:androidApp:assembleDebug` builds.

#### 3. iOS deep link + Koin bootstrap

**File**: `SmartChessboard/iosApp/iosApp/*.swift` (URL scheme in Info.plist / target settings, `onOpenURL` forwarding), small `iosMain` bridge if needed to expose the deep-link handler to Swift

**Intent**: Register the same scheme; forward opened URLs into the shared Auth handler; call `initKoin()` at app start.

**Contract**: Contract §4.2 on iOS — external browser (ASWebAuthenticationSession/system browser per SDK) returns to the app with a stored session.

#### 4. Web redirect flow

**File**: `SmartChessboard/webApp/src/webMain/kotlin/main.kt`

**Intent**: Call `initKoin()` and render the same `App()`. Sign-in on web uses the SDK's redirect flow (full-page navigation to Google and back to the site origin); on load, the Auth plugin consumes the callback URL before the root concludes SignedOut (the `Restoring` state covers this window). Session persists in localStorage (SDK default).

**Contract**: Works on `http://localhost:8080` dev server and on the deployed `workers.dev` origin (both already in the redirect allowlist from Phase 2). The wasm bundle still contains only URL + anon key (lessons.md rule).

### Success Criteria:

#### Automated Verification:

- All Phase 3 test commands still green (`:shared:testAndroidHostTest`, `:shared:wasmJsTest`)
- Android app builds: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :androidApp:assembleDebug --console=plain --no-daemon`
- Web production bundle builds: `… ./gradlew :webApp:wasmJsBrowserDistribution --console=plain --no-daemon`
- iOS app compiles: `xcodebuild -project SmartChessboard/iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator build`

#### Manual Verification:

- Android (emulator or device): cold start lands on SignIn → Google round trip → History (empty state) → kill & relaunch app → still SignedIn → sign out → back to SignIn
- iOS (simulator or device, via Xcode): same round trip incl. restart persistence and sign-out
- Web (dev server): same round trip; reload keeps the session
- Web (deployed shell, after a manual `wrangler deploy` of the new bundle): sign-in round trip works on `workers.dev`
- Privacy check with two Google accounts (both must be registered test users while the consent screen is in Testing status — Phase 2): seed one `games` row for account A via Supabase SQL editor → A sees exactly that row rendered correctly (date/mode/labels), B sees the empty state
- OAuth cancel mid-flow (close browser) returns to SignIn with a friendly message, not a crash or stuck spinner

**Implementation Note**: Pause here for manual confirmation of the E2E checks before the docs phase.

---

## Phase 5: Decision records & contract write-back

### Overview

Make the two architecture commitments durable, and bring the contract back in sync with implemented reality (deep-link scheme, session storage, web redirect origins).

### Changes Required:

#### 1. lessons.md — two new entries

**File**: `context/foundation/lessons.md`

**Intent**: Append two lessons in the established Context/Problem/Rule/Applies-to format: (1) *Presentation pattern*: MVVM is the default; MVI is permitted only for screens with genuinely complex event/state machines (live game board, BLE flows) and the choice must be justified in that change's plan; (2) *Dependency injection*: Koin KMP is the committed DI library — new code registers through Koin modules, no parallel service locators.

**Contract**: Append-only; chronology preserved (file's own convention).

#### 2. tech-stack.md — resolve two TBD rows

**File**: `context/foundation/tech-stack.md`

**Intent**: Update the mobile sub-project table: "Architecture pattern" row → MVVM default / MVI-for-complex-screens (decided 2026-06-10, S-01); "Dependency injection" row → Koin KMP. Also tick off the corresponding items in "Open / Deferred decisions" (#7, #8) with a pointer to this change.

**Contract**: No other rows touched.

#### 3. contract-surfaces.md §4 write-back + PRD change-control note

**File**: `docs/reference/contract-surfaces.md`, `context/foundation/prd.md`

**Intent**: Per the contract's own change-control: record the final deep-link scheme `com.smartchessboard://callback` in §4.1 (replacing "exact scheme determined during mobile implementation"); add the two web redirect origins to §4.1; amend §4.2 step 5 to reflect actual session storage (SDK default multiplatform-settings/localStorage; Keychain/Keystore hardening noted as post-MVP); bump frontmatter `updated`. Then complete change-control steps 2–3: add one dated rationale line to prd.md "Implementation Decisions" (deep-link scheme locked; session storage = SDK default, Keychain/Keystore hardening post-MVP). No user-facing PRD wording changes.

**Contract**: All three change-control checklist steps of the document followed.

#### 4. change.md status

**File**: `context/changes/google-signin-own-history/change.md`

**Intent**: Flip `status` to `implemented` and refresh `updated` once Phases 1–5 are verified (done by `/10x-implement` per its convention).

**Contract**: Frontmatter only.

### Success Criteria:

#### Automated Verification:

- (docs phase — none)

#### Manual Verification:

- lessons.md carries both rules; tech-stack.md TBD rows resolved; contract §4 matches the implemented flow and `updated` is bumped; prd.md Implementation Decisions carries the dated rationale line

---

## Phase 6 (OPTIONAL): Native Google one-tap on Android

### Overview

Nice-to-have per planning decision: replace the browser hop with the native Credential Manager dialog on Android only. Skippable — S-01 acceptance does not depend on it; do not start it before Phases 1–5 are verified.

### Changes Required:

#### 1. compose-auth plugin

**File**: `SmartChessboard/gradle/libs.versions.toml`, `shared/build.gradle.kts`, `data/auth/SupabaseAuthRepository.kt`, `presentation/auth/SignInScreen.kt`

**Intent**: Add the supabase-kt `compose-auth` module configured with the Web client ID as `serverClientId`; on Android the sign-in intent goes through Native Google (Credential Manager) with automatic fallback to the browser flow; iOS and web keep the existing flow unchanged.

**Contract**: Browser flow remains the universal fallback; no behavior change on iOS/web; all Phase 3/4 tests stay green.

### Success Criteria:

#### Automated Verification:

- `:shared:testAndroidHostTest`, `:shared:wasmJsTest`, `:androidApp:assembleDebug` green

#### Manual Verification:

- Android shows the native Google account sheet; cancel falls back gracefully; sign-in still round-trips

---

## Testing Strategy

### Unit Tests:

- AuthViewModel state machine over `FakeAuthRepository`: restore → SignedIn, restore → SignedOut, sign-in success / cancel / failure, sign-out.
- HistoryViewModel over `FakeGamesRepository`: loading, empty, loaded (order preserved), error → retry.

### Integration Tests:

- pgTAP (`supabase test db`): RLS enabled, four policies present, behavioral two-user isolation, anon denied (Phase 1).

### Manual Testing Steps:

1. Full OAuth round trip on Android, iOS, web dev server, and deployed web (Phase 4 list).
2. Session persistence across app/page restart on each surface.
3. Two-account privacy check with a seeded row (A sees it, B does not).
4. OAuth cancellation → friendly retry, no stuck state.

## Performance Considerations

None material in this slice: one list query (indexed by `(user_id, created_at desc)` per contract §2.5) and SDK-managed token refresh. The 500 ms move-latency NFR concerns gameplay slices, not auth.

## Migration Notes

`games` is a new table — no data migration. Cloud rollout = `supabase db push` (Phase 1 manual step). Rollback = drop migration locally is irrelevant once pushed; if the slice is abandoned post-push, the empty `games` table is harmless and may stay.

## References

- Roadmap item: `context/foundation/roadmap.md` → S-01 (`google-signin-own-history`)
- Contract: `docs/reference/contract-surfaces.md` §2.2–2.6 (schema/RLS), §3.2 (list op), §4 (OAuth)
- PRD: `context/foundation/prd.md` FR-001, FR-002, FR-015, US-03, Access Control, NFR privacy
- Module rules: `SmartChessboard/AGENTS.md` (layering, build commands, per-target tests)
- Lessons in force: `context/foundation/lessons.md` (wasm green, yarn.lock, anon-key-only)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Backend — `games` schema, RLS, pgTAP

#### Automated

- [x] 1.1 Migrations apply cleanly: `supabase db reset` — 9d159ba
- [x] 1.2 pgTAP suite passes: `supabase test db` — 9d159ba

#### Manual

- [x] 1.3 Migration pushed to cloud (`supabase db push`); table + 4 policies visible in Dashboard — 9d159ba

### Phase 2: Provider configuration (manual console gates)

#### Manual

- [x] 2.1 Google provider enabled in Supabase Dashboard (Client ID + Secret from Google Cloud) — b53644d
- [x] 2.2 Redirect allowlist: deep link + workers.dev + localhost:8080 — b53644d
- [x] 2.3 `supabase/.env.local` holds Google client credentials for local stack — b53644d
- [x] 2.4 OAuth consent screen configured (+ both privacy-check accounts as test users while in Testing) — b53644d
- [x] 2.5 `config.toml`: `[auth.external.google]` block + extended `additional_redirect_urls` — b53644d

### Phase 3: Shared auth core — Koin, Auth plugin, repositories, ViewModels, tests

#### Automated

- [x] 3.1 `:shared:testAndroidHostTest` green — d67e73a
- [x] 3.2 `:shared:wasmJsTest` green — d67e73a
- [x] 3.3 `:shared:iosSimulatorArm64Test` green — d67e73a
- [x] 3.4 ktlint clean — d67e73a

### Phase 4: UI + platform wiring — screens, deep links, web redirect, E2E

#### Automated

- [x] 4.1 Phase 3 test tasks still green — 481ae60
- [x] 4.2 `:androidApp:assembleDebug` builds — 481ae60
- [x] 4.3 `:webApp:wasmJsBrowserDistribution` builds — 481ae60
- [x] 4.10 iosApp compiles via `xcodebuild` (iphonesimulator) — 481ae60

#### Manual

- [x] 4.4 Android round trip (sign-in → history → restart persistence → sign-out) — 481ae60
- [x] 4.5 iOS round trip (same checklist) — 481ae60
- [x] 4.6 Web dev-server round trip incl. reload persistence — 481ae60
- [x] 4.7 Deployed web round trip after manual `wrangler deploy` — 481ae60
- [x] 4.8 Two-account privacy check with seeded row — 481ae60
- [x] 4.9 OAuth cancel → friendly retry state — 481ae60

### Phase 5: Decision records & contract write-back

#### Manual

- [x] 5.1 lessons.md: MVVM-default/MVI-complex rule + Koin rule appended — 2d95a3c
- [x] 5.2 tech-stack.md: two TBD rows resolved (+ deferred-decisions list updated) — 2d95a3c
- [x] 5.3 contract-surfaces.md §4 write-back (scheme, web origins, session storage) + `updated` bump — 2d95a3c
- [x] 5.4 prd.md Implementation Decisions: dated change-control rationale line — 2d95a3c

### Phase 6 (OPTIONAL): Native Google one-tap on Android

#### Automated

- [x] 6.1 Test + build tasks green after compose-auth — 72511e5

#### Manual

- [x] 6.2 Native account sheet on Android with graceful fallback — 72511e5
