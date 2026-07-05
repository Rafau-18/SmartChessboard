# Post-Game Position Evaluations in Replay (S-03) Implementation Plan

## Overview

Deliver FR-017 / US-01 / US-03 — the roadmap's **north star**: a signed-in player toggles
"Analysis" in the Replay screen and sees position evaluations (centipawn score, mate-in-N,
best move) for the position being reviewed. The eval chain is the one decided 2026-06-10 and
specified in contract §3.3: shared Postgres cache → Lichess Cloud Eval → Chess-API.com →
negative-cache `unknown`. This slice ships the project's first Edge Function, a widening
migration on the deployed `position_evals` table, `Position → FEN` serialization in the domain,
the eval data layer in the app, and the replay-side display — including an adaptive two-pane
ReplayScreen on wide windows.

## Current State Analysis

- **Replay (S-02) is complete**: `ReplayViewModel` (MVVM, `Loaded(game, currentPly)`,
  `position = game.positions[currentPly]`) and `ReplayScreen` (board capped at 480dp, transport
  controls, move list, truncation banner) shipped through commit `36d9e75`
  (`presentation/replay/ReplayViewModel.kt`, `ReplayScreen.kt`).
- **The engine carries everything FEN needs**: `domain/chess/Position.kt` stores board, side to
  move, castling rights, en passant target, halfmove clock, fullmove number — the comment at
  `Position.kt:15-17` explicitly defers FEN serialization to this slice. A FEN *parser* exists
  test-only in `commonTest/…/chess/Perft.kt:28-91` as a reference.
- **`status(position)`** (`ChessRules.kt`) already detects checkmate/stalemate — replay can know
  a position is terminal without asking any provider.
- **Edge Function does not exist**: `supabase/functions/` is absent entirely. `config.toml` has
  `[edge_runtime]` enabled (Deno 2). `supabase/AGENTS.md` specifies the testing convention
  (`deno test` without real egress) and the two reasons this function exists (secret custody,
  sole cache writer).
- **Deployed schema lags the contract**: `supabase/migrations/20260531233302_position_evals.sql`
  has `CHECK (source in ('lichess', 'unknown'))` — predates the `'chess-api'` fallback decision.
  Contract §2.3 already flags that a widening migration is required before the proxy ships.
- **Supabase client lacks the Functions plugin**: `data/supabase/SupabaseClientProvider.kt`
  installs Postgrest, Auth, ComposeAuth only. supabase-kt BOM 3.6.0 provides `functions-kt`.
- **Provider smoke tests (this planning session, 2026-06-12)**:
  - Lichess hit: HTTP 200 `{fen, knodes, depth, pvs: [{moves, cp}]}`; miss: HTTP 404
    `{"error":"No cloud evaluation available for that position"}`; anonymous works; `cp` is
    White-POV (empirically confirmed).
  - Chess-API.com success: `{move (UCI), centipawns (string!), mate (string|null), depth, …}`;
    terminal position (mate on board): error `INVALID_INPUT`; FEN with a non-capturable en
    passant target: error `INVALID_FEN_VALIDATION_ERROR`.
- **Web target constraint**: COOP/COEP headers are live on the deployed shell; CORS-enabled
  `fetch` to the Edge Function is *not* blocked by COEP (CORP applies to embedded subresources),
  but the function must answer `OPTIONS` preflight and send `Access-Control-Allow-Origin`.

## Desired End State

A signed-in player opens a replayed game, taps the Analysis toggle, and the current position
gets an evaluation: an eval bar and a text panel (score in pawns or "M3", best move, e.g.
"e2→e4") plus a best-move arrow on the board. Stepping through plies fetches evaluations
on-demand (cached positions resolve instantly — globally in Postgres, per-session in the
ViewModel). Positions no provider knows show "No evaluation for this position"; provider outages
show "Analysis temporarily unavailable" with Retry; terminal positions show "Checkmate" /
"Stalemate" without any request. On wide windows (web/tablet) ReplayScreen lays out as two
panes: board + eval bar | eval panel + move list. Works on Android, iOS, and web against the
hosted project.

Verify: per-target test suites + `deno test` + pgTAP green; manual E2E — toggle analysis on a
seeded game on all three surfaces against the cloud; `position_evals` rows accumulate with
`source` in `lichess`/`chess-api`/`unknown`.

### Key Discoveries:

- `Position.kt:15-17` — all six FEN fields present; serialization deliberately deferred to S-03.
- `ChessRules.kt` `status()` — terminal detection is free; never query providers for mate/stalemate boards.
- Chess-API.com validates FEN strictly (smoke-tested): en passant field must name a *capturable*
  square, and terminal positions return an error instead of an eval — both must be handled
  before any request leaves the app.
- Both providers return mate-in-N (`mate` field / `pvs[].mate`), but neither contract §3.3 nor
  the deployed table can represent it — resolved in this plan (new nullable `mate` column + API field).
- Full-FEN cache keys would fragment the global cache (identical positions reached at different
  move numbers differ only in counters) — the function normalizes counters to `0 1` for the
  cache key and upstream calls.
- `supabase/AGENTS.md` — contract edits come *first*, then schema/function code; `lichess-eval`
  is the only Edge Function and must stay single-purpose.

## What We're NOT Doing

- No live engine bar during play, no precompute of whole games server-side (PRD: on-demand only).
- No client-side batch prefetch of all plies — evaluation requests follow the currently viewed position.
- No bundled Stockfish (post-MVP, PRD OQ-3); no provider beyond the two in the contract
  (stockfish.online stays the documented alternate, not implemented).
- No SAN generation — best move renders as UCI-style "e2→e4" text + board arrow; SAN arrives with S-04.
- No Nav3 scenes / list-detail (History next to Replay) — adaptive work is scoped to *inside*
  ReplayScreen; History and SignIn are untouched.
- No persistence of evals in the app (no Room; cloud cache + in-memory session cache only).
- No rate limiting of our own function, no observability stack (MVP posture).
- No changes to the `games` table, replay domain (`PgnParser`/`ReplayGame`), or navigation routes.

## Implementation Approach

Contract-first, then bottom-up along the §3.3 boundary: (1) amend `contract-surfaces.md` and
widen the deployed schema, (2) build and unit-prove the Edge Function against mocked providers,
(3) add `Position → FEN` and the app-side eval data layer behind a domain interface (fakes for
tests), (4) extend ReplayViewModel/Screen with the analysis state machine, presentation
(panel + bar + arrow), and the wide-window two-pane layout — the phase whose local-stack manual
gate is the first real app↔function integration, (5) deploy via CLI, set secrets (manual gate),
E2E on three surfaces, write decisions back.

## Critical Implementation Details

**Mate sign convention is unverified for Black** — smoke tests only covered White-to-move-mates.
Before finalizing provider mapping (Phase 2), curl both providers with a position where *Black*
mates and pin down the sign. Stored convention: `eval_cp` and `mate` are **White-POV signed**
(`mate < 0` = Black mates in |N|), mirroring the existing `eval_cp` contract note.

**Chess-API response quirks (smoke-tested 2026-06-12)** — `centipawns` arrives as a JSON
*string* (`"22"`) for normal evals but a *number* (`10000`) alongside `mate: "1"` for forced
mates; parse defensively. A terminal position returns `{"type":"error","error":"INVALID_INPUT"}`
— treat as provider-no-eval, not a 5xx. A FEN whose en passant field names a non-capturable
square returns `INVALID_FEN_VALIDATION_ERROR` — prevented app-side by the `toFen` en passant
rule (emit the square only when an enemy pawn can pseudo-legally capture; else `-`).

**FEN normalization happens in exactly one place** — the function (cache-key authority) zeroes
halfmove/fullmove (`… 0 1`) before cache lookup and upstream calls. `Position.toFen()` stays a
faithful general-purpose serializer (S-04 will reuse it); the en passant capturability rule is
the only deviation from naive emission, and it is general FEN hygiene, not eval-specific.

**Request lifecycle in the ViewModel** — switching ply cancels the in-flight eval request
(coroutine job cancel propagates to ktor); resolved evals land in a per-ply session cache so
revisiting a ply never refetches. Terminal positions (via `status(position)`) short-circuit to a
Terminal state without touching the repository.

**CORS on the function is load-bearing for web** — the wasm client sends `Authorization` +
`Content-Type` headers, triggering preflight; the function must answer `OPTIONS` (204 +
`Access-Control-Allow-Origin`, `Access-Control-Allow-Headers: authorization, x-client-info,
apikey, content-type`) and attach ACAO to every response. COOP/COEP on the host does not block
CORS fetches — no `_headers` change.

**Local JWT for manual function smoke** — `supabase functions serve` enforces `verify_jwt`; the
local **anon key** is itself a valid locally-signed JWT, so
`curl -H "Authorization: Bearer <local anon key>"` exercises the authenticated path.

## Phase 1: Contract & Schema Migration

### Overview

Amend the authoritative contract first (per `supabase/AGENTS.md`), then ship the append-only
widening migration with pgTAP coverage. No app code.

### Changes Required:

#### 1. Contract amendments

**File**: `docs/reference/contract-surfaces.md`

**Intent**: Record every interface decision this slice commits, before any code mirrors it.

**Contract**: §2.3 — add `mate integer NULL` column (White-POV signed mate distance; NULL when
not a forced mate) and update the migration note (the widening migration also adds `mate`).
§3.3 — 200 response gains `"mate"`; document FEN normalization (cache key + upstream calls use
counters zeroed to `0 1`); document CORS/preflight behavior; note `LICHESS_TOKEN` is set from
day one; note that terminal positions are a client-side short-circuit and the function treats
provider "cannot evaluate" answers as no-eval. §5.4 — eval-FEN derivation rules: mobile derives
a faithful FEN; en passant square emitted only when pseudo-legally capturable. Bump `updated`.

#### 2. PRD mirror note

**File**: `context/foundation/prd.md`

**Intent**: Change-control rule in `contract-surfaces.md` requires mirroring §2–4 edits into the
PRD's Implementation Decisions.

**Contract**: One dated bullet: eval responses/cache gain a mate-in-N representation; FEN cache
keys are normalized; no user-facing scope change to FR-017.

#### 3. Widening migration

**File**: `supabase/migrations/<timestamp>_position_evals_chess_api_and_mate.sql`

**Intent**: Bring the deployed table up to contract §2.3: accept the fallback provider's source
value and store mate distance. Append-only; the 20260531 migration is never edited.

**Contract**: Drop + recreate the `source` CHECK as `('lichess','chess-api','unknown')`; add
`mate integer` (nullable). Existing rows remain valid (additive column, widened constraint).

#### 4. pgTAP coverage

**File**: `supabase/tests/position_evals.test.sql`

**Intent**: Prove the schema invariants the function will rely on, in the same suite as
`games_rls.test.sql`.

**Contract**: Asserts — `source='chess-api'` row inserts (as table owner); a bogus source value
is rejected; `mate` column exists, integer, nullable; RLS is enabled with the read policy
present and no write policy for `authenticated`.

### Success Criteria:

#### Automated Verification:

- Local stack resets cleanly with the new migration: `supabase db reset` (from `supabase/`)
- pgTAP suite green incl. the new file: `supabase test db`

#### Manual Verification:

- Contract + PRD diffs read and approved (decisions match this plan)

**Implementation Note**: After completing this phase and all automated verification passes,
pause for manual confirmation before proceeding.

---

## Phase 2: Edge Function `lichess-eval`

### Overview

The project's first Edge Function (Deno 2 + TypeScript): the §3.3 eval chain, fully unit-proven
with injected fakes (no real egress in tests), CORS-complete for the web target.

### Changes Required:

#### 1. Function implementation

**Files**: `supabase/functions/lichess-eval/index.ts` (+ small modules, e.g. `fen.ts`,
`providers.ts`, `eval-chain.ts` — split for testability)

**Intent**: Implement the chain: validate FEN → normalize (counters → `0 1`) → cache lookup
(fresh: 30 days for `lichess`/`chess-api`, 24 h for `unknown`) → Lichess Cloud Eval (optional
`LICHESS_TOKEN` Bearer) → Chess-API.com fallback → upsert result / negative-cache → respond per
the §3.3 table (incl. `mate`). Cache writes via `service_role` (`SUPABASE_URL` +
`SUPABASE_SERVICE_ROLE_KEY` are platform-injected). JWT enforcement stays platform-level
(`verify_jwt`, the default).

**Contract**: Responses exactly per §3.3 with `mate` added:
`200 {fen, eval_cp, mate, best_move, depth, source: "cache"|"lichess"|"chess-api", fetched_at}`,
`200 {fen, source:"unknown"}`, `400 invalid_fen`, `429 {error, retry_after_seconds}`,
`502 upstream_unavailable`. Provider mapping: Lichess `pvs[0]` → `cp`/`mate` + first move of
`moves` as `best_move`; Chess-API `centipawns` (string or number) / `mate` (string) / `move`.
Chess-API `INVALID_INPUT` (terminal/unevaluable) counts as provider-no-eval. Both-provider
hard failure: `429` when rate-limited (propagate `retry_after_seconds` if available), else
`502`; neither is cached. FEN validation is structural (ranks/pieces/side/castling/ep shape) —
legality stays the providers' problem. Every response (incl. `OPTIONS` preflight 204) carries
CORS headers.

#### 2. Function tests

**Files**: `supabase/functions/lichess-eval/*_test.ts`

**Intent**: Prove the decision chain without egress (`supabase/AGENTS.md` convention) — fetch
and the cache client are injected fakes.

**Contract**: Scenarios — fresh cache hit → `source:"cache"`, no provider call; stale positive
row → refetched; stale `unknown` within 24 h → served, after 24 h → retried; Lichess hit →
upsert `lichess`; Lichess 404 → Chess-API hit → upsert `chess-api`; Lichess 404 + Chess-API
failure → upsert `unknown` + `200 unknown`; both rate-limited → `429`, nothing cached; both
5xx → `502`, nothing cached; malformed FEN → `400`; mate mapping from both provider shapes
(string `centipawns`, `mate` signs incl. Black-mates); FEN normalization (two FENs differing
only in counters share one cache key); `OPTIONS` → 204 + CORS headers.

#### 3. Local config

**File**: `supabase/config.toml`

**Intent**: Declare the function for local serve if needed (explicit `[functions.lichess-eval]`
with `verify_jwt = true`), keeping local behavior identical to hosted.

**Contract**: `supabase functions serve` boots the function with JWT verification on.

### Success Criteria:

#### Automated Verification:

- Function tests green: `deno test` (from `supabase/functions/lichess-eval/`)
- Schema suite still green: `supabase test db`

#### Manual Verification:

- Smoke against local serve with real egress: opening FEN → `lichess`; amateur middlegame FEN →
  `chess-api`; repeat call → `cache`; verify a row lands in local `position_evals`
- Mate sign convention verified with a Black-mates position against both providers; mapping
  matches the White-POV contract

**Implementation Note**: Pause for manual confirmation after automated checks pass.

---

## Phase 3: FEN Serialization & Eval Data Layer

### Overview

The app-side half of the §3.3 boundary, proven with fakes: `Position → FEN` in the domain, the
Functions plugin, and an `EvalRepository` the ViewModel can consume.

### Changes Required:

#### 1. FEN serializer

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/Fen.kt`

**Intent**: Faithful FEN emission from `Position` — the serializer S-04 will reuse. The en
passant field follows the capturability rule (Critical Details) so strict provider validators
accept every FEN the app can produce.

**Contract**: `fun Position.toFen(): String` — six fields; piece placement rank 8→1; castling
subset or `-`; en passant square only when an enemy pawn can pseudo-legally capture onto it,
else `-`; halfmove/fullmove as stored.

#### 2. FEN test corpus

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/domain/chess/FenTest.kt`

**Intent**: Prove the serializer on the standard corpus, including round-trips against the
existing test-only parser (`Perft.kt`), on all three targets (lessons.md: commonMain logic is
not green until Native passes).

**Contract**: Covers — start position exact string; positions after castling (partial rights),
after rights lost (`-`); en passant emitted when capturable, suppressed when not (both cases);
promotion-heavy position; counters; round-trip `fen(p.toFen()) == p` for the Perft reference
positions.

#### 3. Functions plugin

**Files**: `SmartChessboard/gradle/libs.versions.toml`, `SmartChessboard/shared/build.gradle.kts`,
`SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/supabase/SupabaseClientProvider.kt`

**Intent**: Enable Edge Function invocation in the shared client.

**Contract**: `functions-kt` via the existing supabase BOM (3.6.0); `install(Functions)` added
to the client builder. Not npm-backed, so no `yarn.lock` change expected — if the wasm build
complains, run `kotlinWasmUpgradeYarnLock` per lessons.md.

#### 4. Eval domain model + repository interface

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/eval/EvalRepository.kt` (+ `PositionEval.kt`)

**Intent**: Domain boundary the ViewModel and fakes implement; encodes the three user-meaningful
outcomes the UI distinguishes.

**Contract**: Signature other phases depend on:

```kotlin
interface EvalRepository {
    suspend fun evaluate(fen: String): EvalOutcome
}

sealed interface EvalOutcome {
    data class Evaluated(
        val evalCp: Int?,          // White POV
        val mate: Int?,            // White POV signed; null unless forced mate
        val bestMoveUci: String?,
        val source: String,
        val depth: Int?,
    ) : EvalOutcome
    data object NoEval : EvalOutcome                                  // source = unknown
    data class TemporarilyUnavailable(val retryAfterSeconds: Int?) : EvalOutcome  // 429/502/IO
}
```

Auth failures (401) throw — session expiry is handled by the global auth gate, not per-screen.

#### 5. Supabase implementation

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/eval/SupabaseEvalRepository.kt`

**Intent**: Thin adapter: `functions.invoke("lichess-eval")` with `{fen}`, map the §3.3 response
table (incl. `200 unknown`, `429` body's `retry_after_seconds`, `502`, network IO →
`TemporarilyUnavailable`) to `EvalOutcome`, following the DTO→domain conventions of
`SupabaseGamesRepository`.

**Contract**: Response DTO is `@Serializable`; mapping functions are pure and unit-testable
separately from the invoke call.

#### 6. DI registration + fakes

**Files**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/di/AppModules.kt`,
`SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/FakeEvalRepository.kt`

**Intent**: Register `single<EvalRepository>` in `dataModule`; provide the configurable fake
(scripted outcomes per FEN, call counter) mirroring `FakeGamesRepository`.

**Contract**: Fake supports per-call outcome scripting and records invocation counts — Phase 4
tests assert caching behavior through it.

### Success Criteria:

#### Automated Verification:

- Shared suite green on JVM host: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- Shared suite green on iOS simulator: `… :shared:iosSimulatorArm64Test …`
- Shared suite green on wasm: `… :shared:wasmJsTest …`
- ktlint clean: `ktlint` from `SmartChessboard/`

#### Manual Verification:

- None — real app↔function integration is Phase 4's manual gate

---

## Phase 4: Replay Analysis UI & Adaptive Layout

### Overview

The user-facing payoff: analysis toggle + eval presentation (panel, bar, board arrow) in
ReplayScreen, the analysis state machine in ReplayViewModel, and the wide-window two-pane
layout. Manual gate doubles as the first real end-to-end integration on the local stack.

### Changes Required:

#### 1. ReplayViewModel analysis state

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/replay/ReplayViewModel.kt`

**Intent**: Extend the existing MVVM state with the analysis machine: an explicit toggle
(FR-017 "request"), on-demand fetch for the viewed ply, per-ply session cache, cancellation,
terminal short-circuit.

**Contract**: Constructor gains `evalRepository: EvalRepository`. `Loaded` gains
`analysisEnabled: Boolean` and per-ply eval state; per-ply states: `Loading`,
`Evaluated(evalCp, mate, bestMoveUci, …)`, `NoEval`, `Unavailable(retryAfterSeconds)`,
`Terminal(status)`. New intents: `toggleAnalysis()`, `retryEval()`. Behavior: enabling analysis
fetches the current ply unless cached/terminal; ply navigation while enabled fetches uncached
plies and cancels the in-flight request; results cache per ply for the screen's lifetime;
`status(position)` checkmate/stalemate → `Terminal`, no repository call; toggle off stops
fetching (cache retained). The FEN sent is `position.toFen()`.

#### 2. Eval presentation components

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/replay/ReplayScreen.kt` (+ small composables, e.g. `EvalPanel`, `EvalBar`)

**Intent**: Render the per-ply eval state: bar for at-a-glance advantage, panel for precision,
distinct empty/error affordances per the interview decisions.

**Contract**: Toggle lives in the Replay UI (e.g. top-bar action). `EvalBar` — White-POV fill,
clamped scale (suggest ±1000 cp linear; forced mate = full bar). `EvalPanel` — score formatted
in pawns (`+0.22`) or mate (`M3` / `-M2`), best move as `e2→e4`, plus states: inline progress
(Loading), "No evaluation for this position" (NoEval, no retry), "Analysis temporarily
unavailable" + Retry button (Unavailable), "Checkmate"/"Stalemate" label (Terminal). Replay
controls stay usable in every eval state.

#### 3. Best-move arrow overlay

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/board/ChessBoardView.kt`

**Intent**: Optional render-only overlay so the board can show the suggested move without
breaking the S-04 reuse contract.

**Contract**: New optional parameter (default null) carrying a from→to square pair; the arrow
draws above pieces. UCI→square-index parsing is a small pure function with unit tests
(`"e2e4"` → 12→28; promotion suffix `e7e8q` parses, suffix ignored for the arrow). Existing
call sites compile unchanged.

#### 4. Two-pane adaptive layout

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/replay/ReplayScreen.kt`

**Intent**: The interview-decided multi-pane scope: wide windows lay ReplayScreen out as
board+bar | panel+move list; History/SignIn untouched; no Nav3 scenes.

**Contract**: Width breakpoint ≈ 840 dp (Material expanded) via `BoxWithConstraints` — no new
dependency. Narrow: existing single column with the eval panel between board and transport
controls. Wide: left pane board + eval bar (board keeps its 480 dp cap), right pane eval panel +
move list. State is shared — same ViewModel, no layout-specific behavior.

#### 5. ViewModel tests

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/ReplayViewModelTest.kt`

**Intent**: Prove the analysis machine with `FakeEvalRepository`, same dispatcher conventions as
the existing suite.

**Contract**: Covers — toggle on fetches current ply; ply change fetches uncached ply; revisited
ply served from session cache (call count unchanged); in-flight request canceled on ply change
(slow fake); `NoEval` and `Unavailable` map to their states; `retryEval()` refetches only the
current ply; terminal position yields `Terminal` with zero repository calls; toggle off stops
fetching; toggle re-enable reuses cache.

### Success Criteria:

#### Automated Verification:

- Shared suite green on JVM host: `… :shared:testAndroidHostTest …`
- Shared suite green on iOS simulator: `… :shared:iosSimulatorArm64Test …`
- Shared suite green on wasm: `… :shared:wasmJsTest …`
- ktlint clean: `ktlint` from `SmartChessboard/`

#### Manual Verification:

- Local stack E2E (Android emulator, `supabase start` + `supabase functions serve`): open a
  seeded game → toggle Analysis → eval bar/panel/arrow render; stepping fetches per ply;
  revisiting is instant; final position of a decisive seeded game shows the terminal label with
  no request; airplane mode → "temporarily unavailable" + Retry recovers
- Web (`:webApp:wasmJsBrowserDevelopmentRun`): same flow (CORS preflight works); desktop-width
  window lays out two panes; narrow window stays single-column; browser Back still pops to History
- Local `position_evals` shows rows with expected `source` values (and a `mate` row if exercised)

**Implementation Note**: Pause for manual confirmation after automated checks pass.

---

## Phase 5: Deploy, Cloud E2E & Write-backs

### Overview

Ship to the hosted project (agent-driven CLI, user-held secrets), verify the north star on all
three surfaces, record decisions.

### Changes Required:

#### 1. Hosted deploy (agent via CLI, user supervising)

**Action**: `supabase db push` (widening migration), then `supabase functions deploy
lichess-eval` (project already linked). No repo file changes.

**Intent**: Apply schema + function to production exactly as tested locally.

**Contract**: `db push` reports the single new migration; deploy succeeds with `verify_jwt` on.

#### 2. Secrets (manual gate — user only)

**Action**: User generates a Lichess API token (no special scopes needed for cloud-eval) and
runs `supabase secrets set LICHESS_TOKEN=<value>`. The value never appears in the conversation
or the repo.

**Intent**: Day-one token per interview decision; the function reads it conditionally, so the
order (secret before first use) is the only constraint.

#### 3. Decision write-backs

**Files**: `context/foundation/roadmap.md`, `context/foundation/lessons.md`,
`context/changes/post-game-evals-in-replay/change.md`

**Intent**: Sync S-03 status (north star reached); capture candidate lessons if they meet the
bar — strongest candidates: "eval providers validate FEN strictly (en passant capturability,
terminal positions)" and the Edge-Function-testing convention (injected fetch/cache, no egress).

**Contract**: Roadmap S-03 row + Streams note updated; lessons entries follow the four-bullet
format; `change.md` status maintained by the implement ritual.

### Success Criteria:

#### Automated Verification:

- Regression: all three per-target Gradle suites green (same commands as Phase 4)
- Backend regression: `deno test` + `supabase test db` green

#### Manual Verification:

- Migration + function live on hosted project (`db push` / `functions deploy` outputs reviewed)
- `LICHESS_TOKEN` secret set by the user
- Android device/emulator vs cloud: analysis works on a seeded game (bar, panel, arrow, caching)
- iOS simulator vs cloud: same flow
- Web (deployed shell or dev server vs cloud): same flow incl. two-pane at desktop width
- Hosted `position_evals` accumulates rows (`lichess` for openings, `chess-api` for middlegames;
  `unknown` only if provoked)
- Roadmap / lessons / change write-backs reviewed

---

## Testing Strategy

### Unit Tests:

- **Edge Function (`deno test`)**: full decision chain with injected fetch + cache fakes — cache
  fresh/stale paths, both TTLs, provider fallback order, negative cache, 400/429/502 mapping,
  mate mapping (both provider shapes, both signs), FEN normalization, CORS preflight.
- **FEN corpus**: start position, castling-rights subsets, en passant emit/suppress, counters,
  round-trip vs the Perft test parser — on JVM, iOS Native, wasm (lessons.md rule).
- **DTO mapping**: §3.3 response table → `EvalOutcome` (pure functions).
- **UCI parsing**: `"e2e4"`/`"e7e8q"` → square indices.
- **ReplayViewModel analysis machine**: fetch/cache/cancel/retry/terminal/toggle scenarios with
  `FakeEvalRepository`.

### Integration Tests:

- `supabase db reset` + `supabase test db` — migration + pgTAP invariants.
- Full `commonTest` suite on all three targets per phase gate.
- Phase 4 manual gate = real app ↔ `functions serve` ↔ real providers on the local stack.

### Manual Testing Steps:

1. Local stack: `supabase start`, `supabase functions serve`; Android emulator: seeded game →
   Analysis on → step through; verify bar/panel/arrow, instant revisits, terminal label.
2. Force failure paths: airplane mode (Unavailable + Retry), a position both providers won't
   know is hard to force — rely on unit tests for `unknown`.
3. Web dev server: same flow; resize across the 840 dp breakpoint; browser Back unaffected.
4. Cloud (Phase 5): repeat happy path on Android, iOS, web; inspect hosted `position_evals`.

## Performance Considerations

One request per newly-viewed ply while analysis is on (cancel-previous keeps bursts bounded);
two cache layers (Postgres global, per-session in-VM) make repeat views free. Chess-API can take
seconds under load — the panel's Loading state covers it and replay controls stay responsive
(fetch never blocks navigation). Eval requests are off the move-acceptance path entirely
(contract §6.4); the 500 ms NFR is untouched.

## Migration Notes

Append-only migration widening the `source` CHECK and adding nullable `mate` — both backward
compatible; existing rows and the deployed function-less state remain valid between Phase 1 and
Phase 5 (nothing reads the new column until the function ships). Rollback, if ever needed, is a
new reverting migration; the function can be deleted from the dashboard/CLI independently of the
schema.

## References

- Roadmap: `context/foundation/roadmap.md` → S-03 (north star; unknowns resolved here)
- PRD: FR-017, US-01, US-03, OQ-3 (`context/foundation/prd.md`)
- Contract: §2.3 `position_evals`, §3.3 `lichess-eval`, §5.4 FEN derivation, §6.4 outage
  behavior (`docs/reference/contract-surfaces.md`)
- Backend conventions: `supabase/AGENTS.md`
- Prior plan: `context/changes/replay-seeded-games/plan.md` (replay surfaces this slice extends)
- Provider smoke tests: this planning session, 2026-06-12 (Lichess hit/404 shapes; Chess-API
  string-centipawns, mate, INVALID_INPUT on terminal, en-passant FEN strictness)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Contract & Schema Migration

#### Automated

- [x] 1.1 `supabase db reset` applies the widening migration cleanly — e2bfa69
- [x] 1.2 `supabase test db` green incl. new `position_evals.test.sql` — e2bfa69

#### Manual

- [x] 1.3 Contract + PRD amendments read and approved — e2bfa69

### Phase 2: Edge Function `lichess-eval`

#### Automated

- [x] 2.1 `deno test` green (mocked providers, full chain) — 0b216ee
- [x] 2.2 `supabase test db` still green — 0b216ee

#### Manual

- [x] 2.3 Local-serve smoke with real egress: lichess / chess-api / cache paths observed; row lands in local `position_evals` — 0b216ee
- [x] 2.4 Mate sign convention verified for Black-mates on both providers; mapping matches White-POV contract — 0b216ee

### Phase 3: FEN Serialization & Eval Data Layer

#### Automated

- [x] 3.1 Shared suite green on JVM host (`:shared:testAndroidHostTest`) — 1c05b09
- [x] 3.2 Shared suite green on iOS simulator (`:shared:iosSimulatorArm64Test`) — 1c05b09
- [x] 3.3 Shared suite green on wasm (`:shared:wasmJsTest`) — 1c05b09
- [x] 3.4 ktlint clean — 1c05b09

### Phase 4: Replay Analysis UI & Adaptive Layout

#### Automated

- [x] 4.1 Shared suite green on JVM host (`:shared:testAndroidHostTest`) — 500d9aa
- [x] 4.2 Shared suite green on iOS simulator (`:shared:iosSimulatorArm64Test`) — 500d9aa
- [x] 4.3 Shared suite green on wasm (`:shared:wasmJsTest`) — 500d9aa
- [x] 4.4 ktlint clean — 500d9aa

#### Manual

- [x] 4.5 Android + local stack E2E: toggle, bar/panel/arrow, per-ply fetch, instant revisit, terminal label, airplane-mode Retry — 500d9aa
- [x] 4.6 Web: same flow; two panes at desktop width, single column narrow; browser Back unaffected — 500d9aa
- [x] 4.7 Local `position_evals` rows show expected `source` values — 500d9aa

### Phase 5: Deploy, Cloud E2E & Write-backs

#### Automated

- [x] 5.1 Regression: all three per-target Gradle suites green — fe5bd82
- [x] 5.2 Backend regression: `deno test` + `supabase test db` green — fe5bd82

#### Manual

- [x] 5.3 `supabase db push` + `supabase functions deploy lichess-eval` applied to hosted project — fe5bd82
- [x] 5.4 `LICHESS_TOKEN` secret set by user — fe5bd82
- [x] 5.5 Android vs cloud: analysis works on seeded game — fe5bd82
- [x] 5.6 iOS simulator vs cloud: same flow — fe5bd82
- [x] 5.7 Web vs cloud: same flow incl. two-pane desktop layout — fe5bd82
- [x] 5.8 Hosted `position_evals` accumulates rows with expected sources — fe5bd82
- [x] 5.9 Roadmap / lessons / change.md write-backs reviewed — fe5bd82
