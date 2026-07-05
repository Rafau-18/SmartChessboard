# Test Plan

> Phased test rollout for this project. Strategy is frozen at the top
> (§1–§5); cookbook patterns at the bottom (§6) fill in as phases ship.
> Read before writing any new test.
>
> Refresh: re-run `/10x-test-plan --refresh` when stale (see §8).
>
> Last updated: 2026-07-04

## 1. Strategy

Tests follow three non-negotiable principles for this project:

1. **Cost × signal.** The cheapest test that gives a real signal for the
   risk wins. Do not promote to e2e because e2e "feels safer." Do not put a
   vision model on top of a deterministic visual diff that already catches
   the regression.
2. **User concerns are first-class evidence.** Risks anchored in "the
   owner is worried about X, and the failure would surface somewhere in
   that area" carry the same weight as PRD lines or hot-spot data.
3. **Risks are scenarios, not code locations.** This plan documents *what
   could fail* and *why we believe it's likely* — drawn from documents,
   interview, and codebase *signal* (churn, structure, test base). It does
   NOT claim to know which line owns the failure. That knowledge is
   produced by `/10x-research` during each rollout phase. If the plan and
   research disagree about where the failure lives, research is the
   ground truth.

Hot-spot scope used for likelihood weighting: `SmartChessboard/shared/src`,
`SmartChessboard/androidApp|webApp|iosApp`, `firmware/`,
`supabase/functions|migrations|tests` (30 days, 103 commits; golden
snapshots and lockfiles excluded). Caveat applied: a large share of the
`presentation/*` churn is shipped UI slices *plus the tests protecting
them* — treated as risk-lowering, not risk-raising.

## 2. Risk Map

The top failure scenarios this project must protect against, ordered by
risk = impact × likelihood. Risks are failure scenarios in user / business
terms, not test names. The Source column cites the *evidence that surfaced
this risk* — never a specific file as "where the failure lives" (see §1
principle #3).

| # | Risk (failure scenario) | Impact | Likelihood | Source (evidence — not anchor) |
|---|---|---|---|---|
| 1 | A physical-board game is silently recorded with moves that differ from what was actually played; discovered later, irreversible | High | Medium | interview Q1; prd.md guardrail "specific legal move OR visible error" (FR-010); hot-spot `presentation/physical` (26 commits/30d) |
| 2 | Moves the player saw accepted are lost after a crash, kill, or offline window — including the end-of-game result never reaching the cloud | High | Medium | prd.md guardrail FR-014 ("crash must not erase accepted moves"); lessons.md "terminal flush needs its own retry"; hot-spot `domain/games` (17 commits/30d) |
| 3 | The board fails to reconnect mid-game on some devices (history: iOS stale-LTK desync, Android tablet flakiness); session dead-ends, players give up | Medium | High | interview Q3 ("BLE definitely needs stabilizing"); roadmap S-10 `proposed`; prd-firmware FR-FW-012; hot-spots `presentation/connection` (13), `firmware/src` (15 commits/30d) |
| 4 | A signed-in user reads or deletes another user's game (IDOR), or a future migration silently weakens RLS | High | Low | prd.md US-04 + "private by default" NFR; archived S-01 finding: initial RLS defaulted to PUBLIC (burned once); migrations are append-only and keep growing |
| 5 | Firmware and app drift apart on the wire contract (snapshot byte layout / event encoding); symptom: every reconnect loops in board-mismatch recovery | Medium | Medium | prd-firmware FR-FW-005 + contract-surfaces §1.3; lessons.md SYNC-comment rule (hand-mirrored geometry); two codebases evolve independently |
| 6 | Post-game analysis silently degrades — provider drift or a poisoned cache entry yields missing or wrong evals in replay | Low | Medium | hot-spot `supabase/functions/lichess-eval` (16 commits/30d); external providers outside our control; lessons.md strict-FEN finding |

### Risk Response Guidance

| Risk | What would prove protection | Must challenge | Context `/10x-research` must ground | Likely cheapest layer | Anti-pattern to avoid |
|------|-----------------------------|----------------|--------------------------------------|-----------------------|-----------------------|
| #1 | For every accepted sequence (captures, castling, en passant, promotion, reconnect-mid-game) the persisted PGN replays to exactly the physical position; any divergence surfaces as a visible rejection, never silent acceptance | "emulator-green implies board-green"; "accepted move equals persisted move" (persistence is asynchronous) | acceptance → journal → cloud handoff points; the reconnect-reconcile gate; map of what existing emulator E2E already proves | adversarial scenario tests on the interpreter + fault-injected emulator E2E + PGN round-trip invariant | oracle from implementation: expected PGN computed by the same interpreter under test |
| #2 | Kill/crash/offline at any point after "move accepted" leaves the move present after restart; a finished game eventually lands in the cloud without further user activity | "final status 200 means saved"; "the next move re-triggers sync" (terminal states have no next move) | journal write points and flush/retry windows; reconcile-on-load behavior; which failures are swallowed vs surfaced | fault-injection unit/integration on journal + autosaver with fake clock | testing only graceful shutdown; mocking the journal itself away |
| #3 | Automatable floor: the connection state machine survives injected drops, timeouts, and garbage frames without deadlock or stale UI — always reaching reconnect or a clean error. Radio floor: a written manual matrix passes on ≥2 Android + ≥2 iOS devices | "works on my two devices means it works"; "reconnect succeeded means state reconciled" (couples to #1) | the adapter seams (wire adapter vs `BoardConnection` port); which radio failures are observable at which seam; S-10 diagnosis findings | fault-injection at the connection seam + a scripted manual test protocol artifact consumed by S-10 | pretending the radio layer is automatable (real-radio e2e in CI is flake without signal) |
| #4 | Cross-user attempts (select/update/delete another's game by id; insert with a foreign user_id) are denied at the database level; a migration weakening a policy turns the suite red | "logged-in means authorized"; "a pgTAP file exists means edge cases are covered" | current pgTAP coverage depth; whether DB tests run anywhere automatically today (they do not — local-only) | negative pgTAP tests + wiring `supabase test db` into CI | testing only through the app client (exercises nothing the attacker would bypass); asserting policy SQL text (implementation mirror) |
| #5 | The same byte stream decodes to the same occupancy on both sides: one shared vector file consumed by both the firmware suite and the app suite; changing one side without the other fails | "both suites green means same contract" (each side can mirror its own implementation — the oracle problem across two codebases) | where each codec lives; the golden vectors F-02/F-03 already established; how vectors can be shared across build systems | contract tests on shared test vectors | each side generating its own expected values |
| #6 | Provider error or format drift maps to a clean "no eval" state, never a crash or a wrong number; the cache never serves an eval for a different position | "Lichess 404 means outage" (it legitimately means no eval); "mocked-green means live-green" | current `deno test` coverage map; what only a live call can catch | existing deno tests + a small scheduled/dispatch real-egress canary | piling on more mocked tests that by construction cannot see live provider drift |

## 3. Phased Rollout

Each row is a discrete rollout phase that will open its own change folder
via `/10x-new`. Status moves left-to-right through the values below; the
orchestrator updates Status as artifacts appear on disk.

| # | Phase name | Goal (one line) | Risks covered | Test types | Status | Change folder |
|---|---|---|---|---|---|---|
| 1 | UI visual & smoke layer | Freeze the visual surface and core flows behind a CI gate | UI regression (pre-dates this guide) | JVM golden tests, compose.uiTest smoke, CI workflows | complete | context/changes/ui-test-layer/ |
| 2 | Physical-record integrity | Prove no accepted sequence is ever silently mis-recorded and no crash loses accepted moves | #1, #2 | adversarial scenario unit, fault-injected integration, round-trip invariants | planned | context/changes/testing-record-integrity/ |
| 3 | BLE resilience floor | Make the connection state machine un-hangable and ship the manual device-matrix protocol for the radio layer | #3 | fault-injection at seams, manual test protocol artifact | not started | — |
| 4 | Backend & contract gates | Lock RLS against abuse and future migrations, share the wire contract vectors, wire DB/function tests into CI | #4, #5, #6 | negative pgTAP, contract vectors, CI wiring, egress canary | not started | — |

Phase 1 shipped before this guide existed (retrofitted; see §6.6). Order
rationale: Phase 2 attacks the owner's #1 fear with tooling that already
exists (emulator, fakes); Phase 3 builds the safety floor *before* the
planned S-10 connectivity work churns that area; Phase 4 closes cheap
systemic gaps (tests exist, gate does not).

**Status vocabulary** (fixed — parser literals): `not started` →
`change opened` → `researched` → `planned` → `implementing` → `complete`.

## 4. Stack

| Layer | Tool | Version | Notes |
|---|---|---|---|
| unit + integration (KMP) | kotlin-test + kotlinx-coroutines-test | Kotlin 2.4.0 / coroutines 1.11.0 | commonTest runs per target: `testAndroidHostTest`, `iosSimulatorArm64Test`, `wasmJsTest` |
| UI smoke | compose.uiTest v2 (CMP 1.11.1) | 1.11.1 | real `App()` + Koin overrides; contract targets iOS sim + wasm |
| visual regression | Roborazzi + Robolectric | 1.64.0 | 34 goldens, JVM-only; CI is the canonical recording environment |
| firmware unit | Catch2 host tests | per platformio | debounce + protocol suites in `firmware/test/` |
| DB / RLS | pgTAP via `supabase test db` | supabase CLI | 2 test files exist; local-only today — CI wiring is §3 Phase 4 |
| Edge Functions | `deno test` (injected fetch/cache fakes) | Deno 2 | 5 test files, no real egress; CI wiring is §3 Phase 4 |
| e2e (browser/mobile) | none — deliberate | — | see §7 (web) and §2 #3 (radio layer is manual by design) |

**Stack grounding tools (current session):**
- Docs: none (no Context7; Cloudflare-docs MCP present but out of scope here); checked: 2026-07-04
- Search: Exa MCP — used during Phase-1 (ui-test-layer) tool research; not needed for this write; checked: 2026-07-04
- Runtime/browser: Chrome MCP + Claude Preview available — not used (deterministic layers cover current risks); checked: 2026-07-04
- Provider/platform: `gh` CLI authenticated (drives CI gates); no Supabase MCP — CLI (`supabase test db`) is the path; checked: 2026-07-04

## 5. Quality Gates

The full set of gates that must pass before a change reaches production.
"Required after §3 Phase N" means the gate is enforced once that rollout
phase lands; before that, the gate is `planned`.

| Gate | Where | Required? | Catches |
|---|---|---|---|
| ktlint format | local (PostToolUse hook) | required | style drift |
| JVM suite incl. golden verify | CI PR gate (`tests.yml`) | required (wired, Phase 1) | logic + visual regressions |
| wasm suite incl. UI smokes | CI PR gate (`tests.yml`) | required (wired, Phase 1) | web-target + flow regressions |
| iOS simulator suite | CI nightly + dispatch (`ios-tests.yml`) | required nightly (wired, Phase 1) | Native-target divergence |
| golden re-record ritual | CI dispatch (`record-goldens.yml`) | required for any visual change (wired, Phase 1) | unreviewed visual drift |
| physical fault-injection suites | inside per-target test tasks | required after §3 Phase 2 | silent record corruption, durability loss |
| BLE manual device matrix | manual, per protocol artifact | required before S-10 sign-off, after §3 Phase 3 | radio-layer regressions no automation sees |
| pgTAP + deno function tests | CI PR gate | required after §3 Phase 4 | RLS/abuse regressions, provider-mapping bugs |

## 6. Cookbook Patterns

How to add new tests in this project. Each sub-section is filled in once
the relevant rollout phase ships; before that, the sub-section reads
"TBD — see §3 Phase N."

### 6.1 Adding a unit test (KMP)

- **Location**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/` mirroring the source package.
- **Reference tests**: `domain/chess/ChessRulesTest.kt` (rules), `PerftTest.kt` (engine corpus).
- **Run locally**: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon` — and remember the house rule: parsing/regex logic is not green until `:shared:iosSimulatorArm64Test` also passes.
- **Fakes**: hand-written, at repository seams (`FakeGamesRepository` et al.); no mocking library.

### 6.2 Adding a golden (screenshot) test

- **Location**: `SmartChessboard/shared/src/androidHostTest/kotlin/**/screenshot/`; goldens in `shared/src/androidHostTest/snapshots/`.
- **Pattern**: always through `ScreenshotHarness.golden(...)` — pins theme, explicit `LocalWindowSizeClass` (the 0×0 default silently renders landscape), shot size.
- **Record/verify**: `:shared:recordRoborazziAndroidHostTest` / `:shared:verifyRoborazziAndroidHostTest`; CI is the canonical recorder (`record-goldens.yml`); full ritual in `SmartChessboard/AGENTS.md`.

### 6.3 Adding a UI smoke flow

- **Location**: `SmartChessboard/shared/src/commonTest/.../uitest/`.
- **Pattern**: `AppTestHarness.runAppTest(seed) { ... }` — production `App()` over Koin fakes, semantics-only assertions, no network, no secrets; package is excluded from the Android host task (see `shared/build.gradle.kts`).
- **Contract targets**: `:shared:iosSimulatorArm64Test` + `:shared:wasmJsTest`.

### 6.4 Adding an Edge Function test

- **Location**: `supabase/functions/<fn>/*_test.ts`; run `deno test` from the function dir.
- **Pattern**: `fetch` and cache are injected `Deps` — pass fakes, zero real egress; real-egress verification is a manual gate against `supabase functions serve` (see `supabase/AGENTS.md`).

### 6.5 Adding a physical-flow fault-injection test

- TBD — see §3 Phase 2 (will extend the emulator E2E pattern in `commonTest/.../physical/` with kill/offline injection points and the `runCurrent` discipline from lessons.md).

### 6.6 Per-rollout-phase notes

- Phase 1 (ui-test-layer) shipped **before** this guide was written and was
  retrofitted as complete: it delivered §6.2, §6.3, and the three CI
  workflows. Its research file
  (`context/changes/ui-test-layer/research.md`) holds the screenshot-tooling
  landscape incl. pricing, checked 2026-07-03/04.

## 7. What We Deliberately Don't Test

Exclusions agreed during the rollout (Phase 2 interview, Q5). Future
contributors should respect these unless the underlying assumption changes.

- **Web target beyond the existing smokes** — bonus platform for the digital
  subset; current wasm suite + smokes are the ceiling. Re-evaluate if web
  gains its own user base. (Source: interview Q5.)
- **Firmware debounce tuning** — calibrated manually at the physical board;
  an automated test adds no signal over the Catch2 logic suites that already
  exist. Re-evaluate if a second board revision changes the sensor hardware.
  (Source: interview Q5.)
- **Real-radio e2e automation** — the BLE radio/OS layer (advertising,
  bonding, OEM quirks) is covered by the Phase 3 manual matrix, not CI
  automation; automating it buys flake, not signal. (Source: challenger
  pass on risk #3.)

## 8. Freshness Ledger

- Strategy (§1–§5) last reviewed: 2026-07-04
- Stack versions last verified: 2026-07-04
- AI-native tool references last verified: 2026-07-04

Refresh (`/10x-test-plan --refresh`) when:

- a new top-3 risk surfaces from the roadmap or archive,
- a recommended tool's `checked:` date is older than three months,
- the project's tech stack changes (new framework, new test runner),
- §7 negative-space no longer matches what the team believes.
