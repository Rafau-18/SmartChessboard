# Google Sign-In & Own Game History (S-01) — Plan Brief

> Full plan: `context/changes/google-signin-own-history/plan.md`

## What & Why

The first user-facing slice of Smart Chessboard: players sign in with Google (account auto-created), can sign out, and see their own private, chronological game history — the auth + per-user scoping that every later slice (replay, analysis, play) inherits. Without it, nothing user-visible can ship, because the PRD forbids anonymous access to any game view.

## Starting Point

The KMP app is still the wizard skeleton (demo screen + Supabase connectivity probe; no layers, no DI, no navigation). supabase-kt has only the Postgrest module; no OAuth provider is configured anywhere; the `games` table does not exist. What does exist: the deployed web shell, the `position_evals` migration, BuildKonfig secret injection, and — crucially — `contract-surfaces.md`, which already specifies the `games` schema (§2) and the whole OAuth flow (§4).

## Desired End State

On Android, iOS, **and web**, a player taps "Continue with Google", round-trips through the browser, and lands on their own (initially empty) game list; the session survives restarts; sign-out works; RLS provably isolates users. Architecture decisions (MVVM default, Koin) are recorded in the foundation docs, and the contract is updated with implemented reality.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Sign-in mechanics | Browser OAuth via supabase-kt (one flow, all platforms) | Least code/config, exactly the contract §4.2 path; native dialog is optional polish | Plan (interview) |
| Native Android one-tap | Optional Phase 6, skippable | Nice-to-have per user; acceptance never depends on it | Plan (interview) |
| Dependency injection | Koin KMP (commit, record in lessons.md) | Most popular, best-documented KMP DI — future agent sessions know it; resolves roadmap OQ-1 | Plan (interview) |
| Presentation pattern | MVVM default; MVI allowed for complex screens with justification | Simple screens shouldn't pay MVI boilerplate; complex ones (board, BLE) may opt in | Plan (interview) |
| Web target | Wire web OAuth + history too | User pulled a small slice of parked FR-020 forward — web sign-in is nearly free on commonMain | Plan (interview) |
| Test scope | VM/repo fakes + pgTAP RLS suite | Covers the gating logic and the privacy boundary mocks can't reach | Plan (interview) |
| `games` schema, RLS, index, trigger | Transcribed verbatim from contract §2.2–2.6 | Contract is the backend spec; no redesign | Contract |
| OAuth topology | Web-type client ID → Supabase callback → deep link `com.smartchessboard://callback` | Contract §4.1–4.2; scheme locked and written back | Contract |
| List query shape | `select … order created_at desc`, no explicit user filter | RLS scopes rows (§3.2); client never passes user_id | Contract |
| Navigation library | Not yet — root is a session-state switch | First push navigation arrives in S-02; avoids alpha Nav3 cost now | Roadmap/Plan |
| Local persistence | None this slice (cloud read only) | Offline-first (§3.4) starts when games are created in S-04 | Roadmap |

## Scope

**In scope:** `games` migration + RLS + pgTAP; Google provider config (manual consoles incl. OAuth consent screen, plus `config.toml` local parity); supabase-kt Auth + Koin + domain/data/presentation skeleton; SignIn + History screens (empty state, sign-out); deep links (Android/iOS) + web redirect; E2E on three surfaces; decision write-back (lessons, tech-stack, contract + dated prd.md change-control note).

**Out of scope:** game creation/replay/seeding (S-02/S-04), Room/offline-first (S-04), Navigation 3 (S-02), Apple Sign In (post-MVP), full web digital subset (FR-020 stays parked), `lichess-eval` (S-03), CI.

## Architecture / Approach

Clean Architecture skeleton lands in `commonMain`: `domain/` (AuthRepository, GamesRepository interfaces + SessionState incl. an explicit `Restoring` state for the web-redirect window) ← `data/` (supabase-kt Auth/Postgrest implementations, single client provider) ← `presentation/` (AuthViewModel, HistoryViewModel — MVVM, Koin-injected). Root composable switches SignIn ↔ History on session state. Platform shells only: deep-link registration (Android manifest, iOS Info.plist) and `initKoin()` calls.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Backend schema + RLS + pgTAP | `games` table provable-private before any UI | pgTAP impersonation setup fiddliness |
| 2. Provider config (manual) | Google OAuth usable against the cloud project + `config.toml` local parity | Console misconfig → cryptic OAuth errors; Testing-status consent screen needs both privacy-check accounts as test users |
| 3. Shared auth core | Koin + repos + VMs + green tests on all targets | wasm yarn.lock / alpha-tooling friction |
| 4. UI + platform wiring + E2E | Working sign-in/history on Android, iOS, web | Deep-link edge cases; web redirect state loss |
| 5. Decision records | lessons/tech-stack/contract/prd note in sync with reality | None (docs) |
| 6. (Optional) Android one-tap | Native sign-in sheet | Extra Google config; skippable |

**Prerequisites:** Supabase cloud project access (exists), a Google Cloud account for the OAuth client (Phase 2, user), local Docker for `supabase test db`.
**Estimated effort:** ~3–4 implementation sessions; Phase 2 is ~15 min of user console work that can run parallel to Phase 3.

## Open Risks & Assumptions

- supabase-kt 3.6 session persistence is multiplatform-settings/localStorage, not Keychain/Keystore as contract §4.2 currently claims — accepted for MVP, contract gets amended (Phase 5); hardening is post-MVP.
- Web OAuth assumes the SDK consumes the callback URL on load before the app concludes SignedOut — the explicit `Restoring` state guards this; confirmed during plan review (2026-06-11): the Auth plugin auto-consumes the callback URL at init and exposes `SessionStatus.Initializing`.
- Google blocks WebView OAuth — the flow must stay in external browser/custom tab (SDK default).

## Success Criteria (Summary)

- A player signs in with Google on Android, iOS, and web, sees only their own (empty) list, and stays signed in across restarts; sign-out returns to the gate.
- `supabase test db` proves two-user RLS isolation on `games`; VM tests prove the gating state machine.
- lessons.md / tech-stack.md / contract-surfaces.md (+ dated prd.md Implementation Decisions note) reflect the committed decisions and implemented flow.
