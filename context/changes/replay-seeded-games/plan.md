# Replay Seeded Games (S-02) Implementation Plan

## Overview

Deliver FR-016 / US-03: a signed-in player opens a saved game from their history and replays it
with start / back / forward / end controls on a new, shared chessboard view. The first increment
runs over **seeded snapshot game records** (no play mode yet) inserted into the existing `games`
table. The slice also introduces the app's first push navigation (Navigation 3) and the board
view + PGN parsing that play mode (S-04) and evaluations (S-03) will reuse.

## Current State Analysis

- **History surface exists (S-01)**: `HistoryScreen` renders `GameRow`s from
  `GamesRepository.listMyGames()` (metadata only — no `pgn` column fetched). `GameRow` has no
  click handler yet (`presentation/history/HistoryScreen.kt`).
- **Navigation is a session-state switch**: `App.kt` renders Restoring / SignIn / History off
  `SessionState` — no nav library, no back stack. S-01 deliberately deferred the navigation
  choice to this slice.
- **Rules engine (F-01) is implemented and perft-verified**: `domain/chess/` exposes immutable
  `Position`, `legalMoves`, `validate`, `applyMove`, `status` (`ChessRules.kt`). **FEN/SAN/PGN
  are explicitly out of F-01 scope** — its plan assigns PGN/SAN to consuming slices; a FEN parser
  exists only as a test helper (`commonTest/…/chess/Perft.kt`).
- **Backend is ready**: `games` table with `pgn text not null default ''`, RLS (owner-scoped
  CRUD), index, trigger — deployed (`supabase/migrations/20260611103324_games.sql` +
  `…110401_games_rls_hardening.sql`). Contract §3.2 already specifies "Get one game"
  (`SELECT … WHERE id = $1`). `config.toml` declares `[db.seed] sql_paths = ["./seed.sql"]` but
  `seed.sql` does not exist.
- **No board UI anywhere**: no chessboard composable, no piece assets in
  `shared/src/commonMain/composeResources/drawable/`.
- **Versions**: Compose Multiplatform 1.11.1 (satisfies Nav3-multiplatform's ≥ 1.10
  requirement), Kotlin 2.4.0, Koin 4.2.1, kotlinx-serialization plugin already available in the
  version catalog.

## Desired End State

A signed-in player taps a game in their history; a Replay screen opens showing the chessboard at
the starting position, the SAN move list, and start/back/forward/end controls. Stepping through
the game updates the board and highlights the current move; tapping a move jumps to it; system
back returns to History. Seeded games (local `seed.sql` + one-off cloud script) make this real on
Android, iOS, and web with no play mode. Verify: per-target test suites green; manual E2E —
replay a seeded famous game to its known final position on all three surfaces.

### Key Discoveries:

- F-01's plan brief assigns SAN/PGN explicitly to consuming slices ("SAN/FEN-serialization/PGN
  belong to consuming slices (S-04/S-02/S-03)") — the parser built here is S-02's largest domain
  block, but move *resolution* is nearly free on top of `legalMoves(position)`.
- Contract §5.4 is binding: "FEN is **not** stored per move" — replay must derive positions from
  PGN at open time; no schema change is allowed or needed.
- RLS `games_insert_own` would allow an authenticated user to insert their own rows, but the
  chosen seeding path avoids app-side tooling: local `seed.sql` (already wired in `config.toml`)
  plus a one-off cloud SQL script (same pattern as `supabase/tests/games_rls.test.sql`, which
  inserts `auth.users` then `games` as the table owner).
- Navigation 3 has a JetBrains multiplatform port since CMP 1.10.0; the **stable**
  `org.jetbrains.androidx.navigation3:navigation3-ui:1.1.1` covers Android/iOS/WasmJS (verified
  2026-06-12). The companion ViewModel wrapper
  `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` is part of the
  `org.jetbrains.androidx.lifecycle` group and must ride the project's existing lifecycle pin
  (`androidx-lifecycle = 2.11.0-beta01`) so the whole lifecycle group stays in lockstep — the
  project already runs that beta, so this adds no new beta surface. Browser-history integration on
  web landed in the base library at **1.1.0** (so 1.1.1 has it) via the browser-navigation binding
  (terrakok integration, `wasmJsMain`, reflects the route in the URL fragment); **this slice wires
  it** so the browser Back/Forward buttons map to the nav stack (Phase 4 §9) — not deferred.
  `NavKey`s must still be `@Serializable` with explicit polymorphic registration — that is the
  no-reflection-off-JVM constraint (iOS/wasm), not an alpha artifact.
- `domain/games/GameStatus` (enum) and `domain/chess/GameStatus` (sealed) collide by simple name;
  `ReplayViewModel` touches both packages — imports need aliasing where they meet.

## What We're NOT Doing

- No play mode or interactive board input — `ChessBoardView` is render-only; gestures arrive in
  S-04.
- No position evaluations or FEN **serialization** (S-03 needs `Position → FEN` for eval
  requests; not built here).
- No SAN **generation** (S-04 writes SAN when recording moves; here we only parse).
- No schema changes, no per-move FEN snapshots, no new contract-surfaces edits (§3.2 "Get one
  game" already covers the read).
- No local persistence / offline-first (Room arrives with S-04 per contract §3.4).
- No board flip, autoplay, or move annotations UI (kept out per interview decision).
- No PGN variations support — a `(` in movetext triggers the truncation path (our writer never
  produces variations).
- No *designed* URL/deep-link scheme on web beyond what Nav3's browser-navigation binding gives
  for free. Browser Back/Forward IS wired this slice (Phase 4 §9) so it maps to the nav stack
  (Replay → History), and reopening a Replay URL on load is a cheap bonus if it falls out of the
  binding — but a hand-designed, shareable URL scheme (pretty paths, per-ply deep links) is not.
- No game deletion or history management.

## Implementation Approach

Bottom-up along the reuse boundaries: (1) pure-domain PGN parsing that turns `pgn` text into an
in-memory `ReplayGame` (positions derived via the F-01 engine — contract §5.4 stays intact),
(2) seeded records + a single-game read in the data layer, (3) the shared render-only board view
with bundled vector piece assets, (4) Navigation 3 + the Replay screen wiring it all together,
(5) cloud seeding + E2E + decision write-backs. Each phase is independently verifiable; UI phases
land only after the domain corpus is green on all three targets.

## Critical Implementation Details

**Nav3 serialization on iOS/wasm** — there is no reflection off the JVM: every `NavKey` route
must be `@Serializable` and registered explicitly (polymorphic registration via the saved-state
configuration), or the back stack crashes on state save/restore on iOS/wasm. This is a
permanent multiplatform constraint, not a stability caveat. Pin `navigation3-ui` to the stable
`1.1.1`; pin the companion `lifecycle-viewmodel-navigation3` via the existing `androidx-lifecycle`
version ref (`2.11.0-beta01`) so the `org.jetbrains.androidx.lifecycle` group resolves to one
version (mixing lifecycle artifact versions is unsupported).

**Seed ordering & environments** — local `seed.sql` must insert `auth.users` rows *before*
`games` (FK), mirroring `supabase/tests/games_rls.test.sql`. The cloud script must **not** touch
`auth.users` — real users already exist; it only inserts `games` rows for a real UUID obtained
after first sign-in.

**Empty PGN is a valid game** — `games.pgn` defaults to `''` and in-progress games may carry
movetext ending mid-pair. The parser must return a single-position (or partial) `ReplayGame`, not
an error; truncation is reserved for unresolvable tokens.

**Name collision** — `domain/games/GameStatus` (enum) vs `domain/chess/GameStatus` (sealed
interface) meet in the replay feature; alias one import where both are needed.

## Phase 1: PGN Replay Domain

### Overview

A pure-Kotlin PGN parser in `commonMain` that resolves SAN movetext against the F-01 engine and
produces the in-memory replay model. Largest logic block of the slice; zero UI, zero deps.

### Changes Required:

#### 1. Replay domain model

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/pgn/ReplayGame.kt`

**Intent**: The model the replay UI consumes: derived positions, the SAN tokens that produced
them, parsed headers, and an optional truncation marker.

**Contract**: `ReplayGame(headers: PgnHeaders, sanMoves: List<String>, positions: List<Position>, truncation: PgnTruncation?)`
with the invariant `positions.size == sanMoves.size + 1` (index 0 = start position; ply *n* is
reached at `positions[n]`). `PgnHeaders` carries at least White/Black/Result/Date as optional
strings (tags beyond those are preserved in a map). `PgnTruncation(plyIndex: Int, reason)` marks
where parsing stopped; a `ReplayGame` with `truncation != null` still exposes every position up
to the failure.

#### 2. PGN parser

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/pgn/PgnParser.kt`

**Intent**: Parse header tags and SAN movetext into a `ReplayGame`, resolving each SAN token by
filtering `legalMoves(position)` of the current position — the engine is the single source of
legality; no separate SAN grammar beyond token shape.

**Contract**: `parsePgn(pgn: String): ReplayGame`. Behavior: header section = `[Tag "value"]`
lines; movetext tokenization strips move numbers, `+`/`#`/`!`/`?` suffixes, NAGs (`$n`), and
`{…}` comments; the result token (`1-0`, `0-1`, `1/2-1/2`, `*`) ends movetext. Castling tokens
`O-O`/`O-O-O` map to the king's castling move. Any token that resolves to zero or multiple legal
moves — or an unsupported construct like a `(` variation — stops parsing with a `PgnTruncation`
at that ply (replay-up-to-error decision). Empty/blank movetext yields a valid single-position
game. SAN resolution matches on piece type, target square, optional file/rank disambiguation,
capture marker, and promotion piece (`=Q|R|B|N` → `Move.promoteTo`).

#### 3. Test corpus

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/domain/chess/pgn/PgnParserTest.kt` (+ fixtures file)

**Intent**: Prove the parser on real games and hostile inputs; the same curated PGNs later go
into `seed.sql`, so fixture parity makes seeds provably replayable.

**Contract**: Corpus covers — two famous complete games (e.g. the Opera Game and the Immortal
Game) asserting final position and move count; kingside + queenside castling; en passant; a
promotion with `=Q`; a disambiguation case (`Nbd2`-style); check/mate suffix handling; headers
parsed and surfaced; headers-only / empty-string input → single-position game; an illegal move
mid-game → `truncation.plyIndex` at the right ply with all prior positions intact; garbage tokens
→ truncation, not exception; `(` variation → truncation.

### Success Criteria:

#### Automated Verification:

- Parser suite green on JVM host: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- Parser suite green on iOS simulator: `… :shared:iosSimulatorArm64Test …`
- Parser suite green on wasm: `… :shared:wasmJsTest …`
- ktlint clean: `ktlint` from `SmartChessboard/`

---

## Phase 2: Seeded Records & Single-Game Read

### Overview

Seeded game rows (local + documented cloud path) and the data-layer read that fetches one game
with its PGN.

### Changes Required:

#### 1. Domain read model + repository method

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameRecord.kt`, `GamesRepository.kt`

**Intent**: Replay needs the full record (summary fields + `pgn`); the list stays lightweight, so
this is a separate model and read, not a fattened `GameSummary`.

**Contract**: `GameRecord` = `GameSummary` fields + `pgn: String`. `GamesRepository` gains
`suspend fun getGame(id: String): GameRecord` (RLS scopes access; caller never passes user id —
same doc comment convention as `listMyGames`).

#### 2. Supabase implementation

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/games/SupabaseGamesRepository.kt`

**Intent**: Implement `getGame` as a single-row Postgrest select by `id` including the `pgn`
column, following the existing DTO→domain mapping pattern in this file (contract §3.2 "Get one
game").

**Contract**: Selecting a missing/foreign id (RLS-filtered) surfaces as a thrown error the
ViewModel maps to its Error state — same failure convention as `listMyGames`.

#### 3. Local seeds

**File**: `supabase/seed.sql`

**Intent**: Make `supabase db reset` produce a working local dataset: test users + games whose
movetext mirrors the Phase 1 fixtures (provably replayable), plus edge-case rows.

**Contract**: Inserts `auth.users` rows first (pattern from `supabase/tests/games_rls.test.sql`,
fixed UUIDs), then `games`: 3–4 finished games with curated PGN (headers per contract §5.2), one
in-progress game (partial movetext, `result` NULL), and one empty-PGN game. Seeds for two distinct
users so RLS scoping stays visible in dev. **No corrupted-PGN seed row**: the truncation banner is
verified via a ReplayScreen preview fed a hand-built corrupted `ReplayGame` (Phase 4), and the
parser's truncation semantics are unit-proven in Phase 1 — a seeded corrupted row would have no
consumer (a locally-signed-in OAuth user can't own the fixed-UUID seed rows anyway).

#### 4. Cloud seed script

**File**: `supabase/cloud-seed-replay-games.sql`

**Intent**: The documented one-off script for the hosted project — the manual gate of this slice
(same spirit as S-01's OAuth console step).

**Contract**: Header comment documents the procedure: sign in once on the device, look up your
UUID in Auth → Users, replace the `:user_id` placeholder, run in the SQL editor. Inserts **only**
`games` rows (no `auth.users` writes) — the same finished games as `seed.sql`. Idempotence note:
running twice duplicates rows; acceptable, documented (delete-and-rerun guidance included).

### Success Criteria:

#### Automated Verification:

- Local stack resets cleanly with seeds applied: `supabase db reset` (from `supabase/`)
- Existing pgTAP suite still green: `supabase test db`
- Shared tests (updated `FakeGamesRepository`) green on JVM host: `… :shared:testAndroidHostTest …`

#### Manual Verification:

- After `supabase db reset`, seeded rows visible for both test users (local Studio or psql),
  PGNs identical to Phase 1 fixtures

---

## Phase 3: Chessboard View & Piece Assets

### Overview

The shared, render-only chessboard composable and the bundled vector piece set — the visual
contract play mode reuses later.

### Changes Required:

#### 1. Piece vector assets

**Files**: `SmartChessboard/shared/src/commonMain/composeResources/drawable/piece_{w,b}{k,q,r,b,n,p}.xml` (12 files), `docs/attributions.md`

**Intent**: Bundle the standard open-licensed piece set (Cburnett, the set known from
lichess/Wikipedia) converted from SVG to Compose vector drawables; record the CC-BY-SA
attribution.

**Contract**: 12 drawables named `piece_<color><type>.xml`; `docs/attributions.md` lists source,
author, and license. Conversion preserves viewport proportions so pieces scale crisply on all
targets.

#### 2. Board composable

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/board/ChessBoardView.kt`

**Intent**: Stateless 8×8 board rendering a `Position` — the reuse contract for S-04: it knows
nothing about games, navigation, or input.

**Contract**: `@Composable fun ChessBoardView(position: Position, modifier: Modifier = Modifier)`.
Orientation fixed white-at-bottom (MVP constant); square coloring derived from the a1-dark
convention; piece lookup maps `Position.board` (a1=0, `index = file + 8*rank` per contract §1.3)
to grid cells. The index→cell mapping lives in a small pure function so it is unit-testable
without UI. **Size-driven (adaptive guardrail):** the board renders into whatever box the caller's
`modifier` gives it — keep it square via `aspectRatio(1f)` and scale to the available size; **no
hardcoded phone dp**. This keeps the reuse contract size-agnostic so a later app-wide adaptive pass
(tablet/web multi-pane) needs no rewrite here. Full responsive layout is out of scope this slice.

### Success Criteria:

#### Automated Verification:

- Square-mapping unit tests green on JVM host: `… :shared:testAndroidHostTest …`
- All targets still compile: `… :androidApp:assembleDebug …` and `… :shared:wasmJsBrowserDistribution …`
- ktlint clean: `ktlint` from `SmartChessboard/`

#### Manual Verification:

- Board renders the start position correctly on Android (temporary preview/host is fine): a1
  dark, white at bottom, pieces crisp at phone size
- Same board renders crisp on web (`:webApp:wasmJsBrowserDevelopmentRun`) — a cheap second-target
  check that the SVG→Compose vector conversion isn't distorted, caught at asset-creation time
  rather than deferred to the Phase 5 three-surface E2E

---

## Phase 4: Navigation 3 & Replay Screen

### Overview

First push navigation in the app (Navigation 3 multiplatform) plus the Replay feature:
ViewModel, screen, history wiring, DI.

### Changes Required:

#### 1. Navigation 3 dependencies

**File**: `SmartChessboard/gradle/libs.versions.toml`, `SmartChessboard/shared/build.gradle.kts`

**Intent**: Add the JetBrains Nav3 multiplatform artifacts to `commonMain`.

**Contract**: `org.jetbrains.androidx.navigation3:navigation3-ui:1.1.1` (stable; pin via a new
`navigation3 = "1.1.1"` catalog version; `navigation3-common` arrives transitively) and
`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` pinned via the **existing**
`androidx-lifecycle` version ref (`2.11.0-beta01`) — it shares the `org.jetbrains.androidx.lifecycle`
group with `lifecycle-viewmodel-compose`/`lifecycle-runtime-compose`, and lifecycle artifacts must
resolve to one version. (The stable `2.10.0` wrapper would split the group against the project's
`2.11.0-beta01` lifecycle pin; matching the existing pin is cleaner and adds no new beta surface.)
Nav3 is not npm-backed, so no `yarn.lock` change is expected — but if the wasm build complains, run
`./gradlew kotlinWasmUpgradeYarnLock` per `lessons.md`.

#### 2. Routes

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/navigation/Routes.kt`

**Intent**: The app's typed back-stack keys.

**Contract**: `@Serializable data object HistoryKey : NavKey` and
`@Serializable data class ReplayKey(val gameId: String) : NavKey`, with explicit polymorphic
registration so state save/restore works on iOS/wasm (no reflection — see Critical Details).

#### 3. Root navigation

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt`

**Intent**: Keep the session-state gate (Restoring/SignedOut unchanged); the SignedIn branch now
hosts a Nav3 `NavDisplay` whose back stack starts at `HistoryKey`.

**Contract**: History entry renders `HistoryScreen` (now with `onGameClick: (String) -> Unit`
pushing `ReplayKey(gameId)`); Replay entry renders `ReplayScreen`; pop returns to History; sign-out
still collapses the whole branch to the gate. Android system back pops the stack via Nav3's back
handling; iOS start-edge pan works by default.

#### 4. History wiring

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/history/HistoryScreen.kt`

**Intent**: Make `GameRow` tappable, surfacing the game id to the navigation layer.

**Contract**: `HistoryScreen` gains `onGameClick: (String) -> Unit`; `GameRow` becomes clickable.
No ViewModel change.

#### 5. ReplayViewModel

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/replay/ReplayViewModel.kt`

**Intent**: MVVM per `lessons.md` (no MVI justification — replay is a simple
load-then-navigate-an-index screen): load the record, parse it once, expose replay state and
navigation intents.

**Contract**: Constructor `(gameId: String, gamesRepository: GamesRepository)`, Koin-resolved
with a `gameId` parameter. `StateFlow<ReplayUiState>` — sealed: `Loading`,
`Error` (retryable, same convention as History), `Loaded(game: ReplayGame, currentPly: Int)`
where the current position is `game.positions[currentPly]` and a truncation notice derives from
`game.truncation != null`. Intents as methods: `goToStart()`, `stepBack()`, `stepForward()`,
`goToEnd()`, `jumpTo(ply: Int)` — all clamped to `0..sanMoves.size`; opens at ply 0 (start
position, per interview decision).

#### 6. ReplayScreen

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/replay/ReplayScreen.kt`

**Intent**: The replay UI: board, transport controls, SAN move list with current-move highlight
and tap-to-jump, truncation banner, player labels/result from headers.

**Contract**: Renders `ChessBoardView(position = …)`; controls `|< < > >|` with enabled-state at
the bounds; move list shows numbered move pairs, highlights the move that produced the current
position, taps call `jumpTo`; when truncation is present, a non-blocking banner says the record
contains an invalid move and replay is shortened. Loading/Error states mirror History's
patterns. **Wide-screen guardrail (adaptive):** layout stays a single column (MVP), but cap the
board's max width and centre it (e.g. `widthIn(max = …)` + centre alignment) so on web/desktop the
board doesn't stretch edge-to-edge. This is the only responsive concession this slice; board +
move-list side-by-side multi-pane is deferred to the app-wide adaptive follow-up.

#### 7. DI registration

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/di/AppModules.kt`

**Intent**: Register `ReplayViewModel` in the presentation module (parameterized by `gameId`),
per the committed Koin pattern.

**Contract**: Resolved at the Replay nav entry via the Koin ViewModel API with
`parametersOf(gameId)`.

#### 8. ViewModel tests

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/ReplayViewModelTest.kt` (+ `FakeGamesRepository` extension)

**Intent**: Prove the load/parse/navigate state machine with fakes, mirroring
`HistoryViewModelTest` conventions (StandardTestDispatcher, no mocking lib).

**Contract**: Covers — load success → `Loaded` at ply 0; repository failure → `Error` + retry;
step/jump clamping at both bounds; `goToEnd` lands on the final position; truncated game exposes
the notice and clamps navigation to the truncated range; empty-PGN game is `Loaded` with a single
position and disabled forward controls.

#### 9. Web browser-history binding (wasmJs)

**File**: `SmartChessboard/shared/src/wasmJsMain/kotlin/org/rurbaniak/smartchessboard/presentation/navigation/BrowserNavigation.wasmJs.kt` (+ `expect` in `commonMain` Routes/navigation, no-op `actual` in `androidMain`/`iosMain`)

**Intent**: On web, map the Nav3 back stack to browser history so the browser Back/Forward buttons
move through the app's stack (Replay → History) instead of leaving the site — the limitation S-01's
web sliver lived with.

**Contract**: Use Nav3 1.1.x's browser-navigation binding (terrakok integration merged into the
base library; `wasmJsMain`, reflects the current route in the URL fragment after `#`). Expose it as
`expect fun bindBrowserNavigation(backStack: NavBackStack)` — the real binding lives in `wasmJsMain`,
a no-op `actual` on Android/iOS — called once where `NavDisplay` is hosted (App.kt SignedIn branch).
**Verify-first**: confirm the exact binding API (name/signature) in `navigation3` 1.1.1 from the
1.1.x docs / terrakok PR as the first sub-step — version intel here is snapshot-level. Reopening a
Replay URL on load (deep link → `ReplayKey(gameId)`) is a cheap bonus if the binding's lambda
surfaces it; a designed URL scheme is out of scope (see "What We're NOT Doing"). Android/iOS paths
are untouched (no-op actual).

### Success Criteria:

#### Automated Verification:

- Full shared suite green on JVM host: `… :shared:testAndroidHostTest …`
- Full shared suite green on iOS simulator: `… :shared:iosSimulatorArm64Test …`
- Full shared suite green on wasm: `… :shared:wasmJsTest …`
- ktlint clean: `ktlint` from `SmartChessboard/`

#### Manual Verification:

- Android (local stack or cloud): tap a seeded game in History → Replay opens at the start
  position; forward/back/start/end behave; move list highlights and tap-jumps; system back
  returns to History; sign-out from History still works
- A ReplayScreen preview/host fed a hand-built corrupted `ReplayGame` shows the truncation
  banner and clamps navigation to the truncated range (truncation semantics themselves are
  unit-proven in Phase 1 and the ReplayViewModel test — this step checks the banner *renders*)
- Web (local dev server, `:webApp:wasmJsBrowserDevelopmentRun`): browser Back from Replay returns
  to History (not off-site); Forward re-enters Replay — confirms the §9 browser-navigation binding

---

## Phase 5: Cloud Seeding, E2E & Decision Write-backs

### Overview

Make the slice real on the hosted project, verify end-to-end on all three surfaces, and record
the decisions this slice committed.

### Changes Required:

#### 1. Cloud seeding (manual gate)

**Action** (no repo file beyond Phase 2's script): User signs in on a device, retrieves their
UUID, runs `supabase/cloud-seed-replay-games.sql` in the hosted SQL editor.

**Intent**: Seeded games appear in the real, RLS-scoped history of the actual account — the only
non-agent-drivable step of the slice.

**Contract**: After running, the account's History shows the seeded games on every surface.

#### 2. Decision write-backs

**Files**: `context/foundation/lessons.md`, `context/foundation/roadmap.md`, `context/changes/replay-seeded-games/change.md`

**Intent**: Record the Navigation 3 commitment as a lessons rule (the navigation analogue of the
S-01 Koin/MVVM entries) and sync statuses.

**Contract**: lessons.md entry states: Navigation 3 multiplatform is the committed navigation
library — `org.jetbrains.androidx.navigation3:navigation3-ui` pinned at the **stable** `1.1.1`,
with the companion `lifecycle-viewmodel-navigation3` pinned via the shared `androidx-lifecycle`
version ref (lifecycle artifacts must resolve to one version); routes are `@Serializable` NavKeys
with explicit polymorphic registration (iOS/wasm have no reflection — a permanent constraint, not
a stability caveat); web browser-history is **wired** via Nav3's browser-navigation binding
(`wasmJsMain`, route ↔ URL fragment) so browser Back/Forward maps to the nav stack — a designed
URL/deep-link scheme is the only web-routing piece left out; do not introduce a second navigation
mechanism. Roadmap S-02 row/status updated; `change.md` status maintained by the implement ritual.

### Success Criteria:

#### Automated Verification:

- Regression: all three per-target test tasks green (same three Gradle commands as Phase 4)

#### Manual Verification:

- Android device/emulator against the cloud project: sign in → seeded games listed → replay a
  famous game to its known final position
- iOS simulator: same flow
- Web (local dev server or deployed shell): same flow, and browser Back pops Replay → History
  (not off-site) with the route reflected in the URL fragment — the §9 browser-navigation binding
- lessons.md / roadmap.md / attributions reviewed and consistent with what shipped

---

## Testing Strategy

### Unit Tests:

- PGN parser corpus (Phase 1) — famous games end-state assertions, special moves (castling both
  sides, en passant, promotion, disambiguation), headers, empty/blank movetext, truncation
  semantics (right ply, prior positions intact), garbage input never throws.
- Square→cell mapping for the board view (orientation correctness without UI).
- `ReplayViewModelTest` — load/error/retry, ply clamping, jump, truncated and empty games.

### Integration Tests:

- `supabase db reset` + existing pgTAP RLS suite — seeds apply and privacy invariants hold.
- Full `commonTest` suite on all three targets (host JVM, iOS simulator, wasm headless browser).

### Manual Testing Steps:

1. Local: `supabase db reset`; run the app against the local stack (or use cloud after Phase 5
   seeding); sign in.
2. Open a seeded famous game; verify the final position matches the known game; exercise all
   four controls and tap-to-jump.
3. Render a ReplayScreen preview with a hand-built corrupted `ReplayGame`: truncation banner
   shows; navigation clamps at the last valid position.
4. Open the empty-PGN game: board shows the start position; forward controls disabled.
5. System back (Android), edge-pan (iOS): Replay → History; sign-out still gates to SignIn.
6. Repeat the happy path on iOS simulator and web.

## Performance Considerations

Parsing happens once per game open: ~80 plies × one `legalMoves` enumeration each — milliseconds
on all targets (the engine is perft-tested at far higher volumes). Replay navigation is an index
move over the in-memory positions list — instant; the 500 ms NFR is not in play. Position list
memory (~100 × 64-entry boards) is negligible.

## Migration Notes

No schema migrations. Seeds are additive data: local seeds re-applied on every `supabase db
reset`; the cloud script is a documented one-off (re-running duplicates rows — delete-and-rerun
guidance lives in the script header). Rollback = delete seeded rows; no code dependency on their
existence.

## References

- Roadmap item: `context/foundation/roadmap.md` → S-02 (incl. the stored-record-shape unknown
  resolved here)
- PRD: FR-016, FR-014, US-03 (`context/foundation/prd.md`)
- Contract: §2.2 `games`, §3.2 "Get one game", §5 PGN/FEN model (`docs/reference/contract-surfaces.md`)
- Upstream plans: `context/changes/chess-rules-engine/plan-brief.md` (engine API, PGN explicitly
  deferred to S-02), `context/changes/google-signin-own-history/plan-brief.md` (navigation
  deferral, MVVM/Koin commitments)
- Nav3 multiplatform status (verified 2026-06-12): kotlinlang.org/docs/multiplatform/compose-navigation-3.html,
  CMP 1.10.0 release notes

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: PGN Replay Domain

#### Automated

- [x] 1.1 Parser suite green on JVM host (`:shared:testAndroidHostTest`) — 3b007e6
- [x] 1.2 Parser suite green on iOS simulator (`:shared:iosSimulatorArm64Test`) — 3b007e6
- [x] 1.3 Parser suite green on wasm (`:shared:wasmJsTest`) — 3b007e6
- [x] 1.4 ktlint clean — 3b007e6

### Phase 2: Seeded Records & Single-Game Read

#### Automated

- [x] 2.1 `supabase db reset` applies migrations + seeds cleanly — 1701d68
- [x] 2.2 Existing pgTAP suite still green (`supabase test db`) — 1701d68
- [x] 2.3 Shared tests green on JVM host (`:shared:testAndroidHostTest`) — 1701d68

#### Manual

- [x] 2.4 Seeded rows visible for both test users locally; PGNs match Phase 1 fixtures — 1701d68

### Phase 3: Chessboard View & Piece Assets

#### Automated

- [x] 3.1 Square-mapping unit tests green on JVM host
- [x] 3.2 All targets compile (`:androidApp:assembleDebug`, `:shared:wasmJsBrowserDistribution`)
- [x] 3.3 ktlint clean

#### Manual

- [x] 3.4 Start position renders correctly on Android (a1 dark, white at bottom, crisp pieces)
- [x] 3.5 Same board renders crisp on web (`:webApp:wasmJsBrowserDevelopmentRun`) — vector conversion undistorted

### Phase 4: Navigation 3 & Replay Screen

#### Automated

- [ ] 4.1 Full shared suite green on JVM host (`:shared:testAndroidHostTest`)
- [ ] 4.2 Full shared suite green on iOS simulator (`:shared:iosSimulatorArm64Test`)
- [ ] 4.3 Full shared suite green on wasm (`:shared:wasmJsTest`)
- [ ] 4.4 ktlint clean

#### Manual

- [ ] 4.5 Android: history tap → replay with working controls, move list, system back
- [ ] 4.6 ReplayScreen preview with a hand-built corrupted ReplayGame shows the truncation banner; nav clamps to truncated range
- [ ] 4.7 Web (local dev server): browser Back/Forward maps to the nav stack (Replay ↔ History), not off-site

### Phase 5: Cloud Seeding, E2E & Decision Write-backs

#### Automated

- [ ] 5.1 Regression: all three per-target test tasks green

#### Manual

- [ ] 5.2 Android vs cloud: seeded games listed and replay to known final position
- [ ] 5.3 iOS simulator: same flow
- [ ] 5.4 Web: same flow; browser Back pops Replay → History (not off-site), route in URL fragment
- [ ] 5.5 lessons.md / roadmap.md / attributions write-backs reviewed
