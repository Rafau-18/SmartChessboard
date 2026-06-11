<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Google Sign-In & Own Game History (S-01)

- **Plan**: context/changes/google-signin-own-history/plan.md
- **Mode**: Deep
- **Date**: 2026-06-11
- **Verdict**: REVISE → SOUND (all accepted fixes applied to plan.md during triage, 2026-06-11)
- **Findings**: 1 critical, 3 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | WARNING |
| Plan Completeness | WARNING |

## Grounding

10/11 paths ✓ (main.kt path omits package dirs — actual: `webApp/src/webMain/kotlin/org/rurbaniak/smartchessboard/main.kt`), 5/5 symbols ✓, brief↔plan ✓, SDK claims 7/7 ✓ (supabase-kt auth-kt wasmJs support, SettingsSessionManager default persistence incl. wasm/localStorage, `scheme`/`host` config + `handleDeeplinks` on Android/iOS, automatic callback-URL consumption on web with `SessionStatus.Initializing`, Koin 4.x wasm-js artifacts for koin-compose-viewmodel, compose-auth native Google via Credential Manager with serverClientId, `supabase test db` runs `supabase/tests/*.sql`).

## Findings

### F1 — Progress phase titles don't match body phase headers

- **Severity**: ❌ CRITICAL (mechanical Progress contract)
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: `## Progress` vs Phase 1/3/4 headers
- **Detail**: `progress-format.md` requires each `### Phase N: <name>` in Progress to match the `## Phase N: <name>` body header. Three don't: Phase 3 body "Shared auth core — Koin, Auth plugin, repositories, ViewModels, tests" → Progress "Shared auth core"; Phase 4 body "UI + platform wiring — screens, deep links, web redirect, E2E" → Progress "UI + platform wiring + E2E"; Phase 1 has backticks around `games` in the body header but not in Progress. Note: `/10x-implement` keys on the `### Phase N:` prefix so parsing would likely survive, but titles become immutable once this review passes — now is the moment to align them.
- **Fix**: Align the three Progress headings to the body headers (or shorten the body headers to match — either direction, one Edit each).
- **Decision**: FIXED — Progress headings aligned to body phase headers (Phases 1, 3, 4)

### F2 — Phase 2 omits the OAuth consent screen and test users

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 2 — Google Cloud Console
- **Detail**: Google requires the OAuth consent screen to be configured (app name, support email, scopes openid/email/profile, publishing status) before an OAuth client can be created. In the default "Testing" status only allowlisted test users can sign in — which directly affects the Phase 4 two-account privacy check (4.8): both Google accounts must be added as test users, or the app published to Production. Missing from the Phase 2 checklist.
- **Fix**: Add a consent-screen step to Phase 2 (config + publishing-status decision) and a note on 4.8 that both privacy-check accounts must be registered test users while in Testing.
- **Decision**: FIXED — consent-screen step added to Phase 2 (+ Progress 2.4); test-user note added to Phase 4 privacy check

### F3 — Local-stack Google provider: secrets don't exist yet in Phase 1, and local OAuth can't round-trip anyway

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 1 #3 (config.toml) vs Phase 2 ordering
- **Detail**: Phase 1 adds `[auth.external.google]` with `env(...)` values sourced from `supabase/.env.local` — but those credentials are only created by Phase 2 (user console homework). Phase 1's own automated criterion (`supabase db reset`) may run against a config.toml referencing env vars that don't exist yet. Even after Phase 2, local OAuth can't round-trip: the Google client only registers the cloud callback (`https://<ref>.supabase.co/auth/v1/callback`), not the local stack's `http://127.0.0.1:54321/auth/v1/callback`. No success criterion exercises the local provider — it's parity config with no consumer in this slice.
- **Fix A ⭐ Recommended**: Move the `[auth.external.google]` config.toml edit from Phase 1 to Phase 2, and add the local callback URI to the Google client's authorized redirect URIs there.
  - Strength: Provider config lands together with the credentials that make it meaningful; Phase 1 stays purely schema+RLS and `supabase db reset` can't trip on unresolved env().
  - Tradeoff: Phase 2 (user homework) grows by two small steps.
  - Confidence: HIGH — pure resequencing, no new work invented.
  - Blind spot: CLI behavior on missing env() vars untested either way (may be warning, may be hard error).
- **Fix B**: Keep the block in Phase 1 but add creating `.env.local` with placeholder values to Phase 1; real values land in Phase 2.
  - Strength: config.toml parity visible from the first commit.
  - Tradeoff: A deliberately misconfigured provider sits in the local stack; confusing failure if anyone tries it.
  - Confidence: MEDIUM — placeholder-cred behavior unverified.
  - Blind spot: Same env() question as Fix A.
- **Decision**: FIXED via Fix A — config.toml block moved to Phase 2 (+ Progress 2.5); local callback URI added to Google client step

### F4 — Phase 5 claims contract change-control "followed" while skipping its PRD mirror/note steps

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 5 #3 — contract-surfaces.md §4 write-back
- **Detail**: contract-surfaces.md "Change control" says any §2–4 change requires (2) mirroring impact in prd.md and (3) a dated one-line rationale in the PRD's Implementation Decisions. Phase 5 edits §4.1 (scheme, web origins) and §4.2 (session storage) yet states "PRD untouched — no user-facing wording changed". The exemption may be reasonable, but as written the plan asserts the checklist is followed while skipping two of its three steps.
- **Fix**: Add to Phase 5 #3: one dated rationale line in prd.md Implementation Decisions (deep-link scheme locked; session storage = SDK default, Keychain/Keystore post-MVP). Keeps the contract's own ritual intact at the cost of one line.
- **Decision**: FIXED — prd.md Implementation Decisions note added to Phase 5 #3 (+ Progress 5.4)

### F5 — iOS app build has no automated criterion in Phase 4

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 4 — Success Criteria
- **Detail**: Automated checks cover `:androidApp:assembleDebug` and the web bundle, but nothing compiles the Xcode project — Swift glue (Info.plist scheme, onOpenURL) errors surface only at manual E2E 4.5. `iosSimulatorArm64Test` compiles shared code, not the iosApp target.
- **Fix**: Optionally add `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator build` to Phase 4 automated criteria (and a matching Progress 4.x item).
- **Decision**: FIXED — xcodebuild criterion added to Phase 4 Automated (+ Progress 4.10)

### F6 — Koin Compose Multiplatform web support is "Experimental"

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3 — Koin / `koinViewModel()` on wasmJs
- **Detail**: Verified: koin-compose-viewmodel ships wasm-js artifacts and `koinViewModel()` is the right API — but Koin's own support table marks Compose Multiplatform Web as Experimental (Android/iOS are full support). If it misbehaves on wasm, the fallback (resolve VMs from Koin at the root composable and pass them down) is trivial.
- **Fix**: Add one line to the plan's Open Risks (and plan-brief): Koin CMP web = experimental; fallback is root-level manual VM resolution.
- **Decision**: SKIPPED — risk known, not recorded in plan docs
