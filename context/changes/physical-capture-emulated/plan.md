# Physical-Mode Capture Against the Emulator (S-06) Implementation Plan

## Overview

Add a physical-mode game flow to the Smart Chessboard mobile app: the player creates a
`mode='physical'` game and plays it by lifting and placing pieces on the (emulated) reed-switch
board, confirming each move with the side button. A new **sequence interpreter** turns the recorded
lift/place stream into exactly one legal move — captures, castling, and en passant read from the
full sequence, not a final snapshot — which is then validated by the F-01 engine and auto-saved into
the **same canonical PGN record** the digital flow uses. Promotions are blocked in-app until the
player picks a piece.

This is roadmap slice **S-06** (PRD FR-005, FR-006, FR-008, FR-009, US-02) and the project's hardest
open bet: that a magnet-only occupancy stream resolves to one unambiguous move. It is proven
end-to-end against the F-02 emulator with **no hardware**; interactive play on the real board over
BLE is deferred to S-09.

## Current State Analysis

The three prerequisites are complete and mostly reusable:

- **F-02 emulator** (`impl_reviewed`) ships the consumable board contract in `commonMain`:
  `BoardConnection` (`events: SharedFlow<BoardEvent>` — hot, `replay=0`; `connectionState`;
  `suspend send(BoardCommand)`), the `BoardEvent` hierarchy (`SquareEvent(square, LIFT|PLACE)`,
  `ButtonEvent(WHITE|BLACK)`, `BoardSnapshot(occupancy: Long)`, `DeviceStatus`), and commands
  (`SetMode`, `RequestSnapshot`, `RequestStatus`). The `EmulatedBoard` + scenario DSL live in
  `commonTest` and were explicitly designed to "promote to `commonMain` unchanged once S-06 wires a
  production consumer." No `BoardConnection` is registered in Koin yet — **S-06 is the first
  consumer.**
- **F-01 rules engine** (done) exposes everything the interpreter needs: `legalMoves(position)`,
  `validate(position, move): MoveOutcome`, `status(position)`, immutable `Position` (`pieceAt`,
  `Position.start()`), and `Move(from, to, promoteTo?)`. Critically, `Move` carries **no**
  capture/castle/en-passant flags — those are derived from the `Position` (the derivations already
  exist in `SanWriter`/`PgnParser`). `legalMoves` expands a promotion to four `Move`s sharing
  from/to, emits castling as one two-file king move, and en passant as one diagonal pawn move with
  `to == enPassantTarget`.
- **S-04 digital play** (done) provides the entire durable-record back half, which is
  **mode-agnostic and reused verbatim**: `GameAutoSaver.acceptMove/finishGame/sync/reconcile`,
  `GameJournal`, `writePgn`/`sanForMove`, and the §6.2 acceptance ordering realized in
  `PlayViewModel.acceptMove`. The model is already mode-aware end-to-end (`GameMode.{DIGITAL,
  PHYSICAL}`, History shows a mode label, PGN writes `[Mode]`). Only **two seams hardcode digital**:
  `GamesRepository.createGame` (no `mode` param; literal `"digital"` in the Supabase impl) and the
  `NewGameScreen` form (no mode picker).

What is missing: the sequence interpreter, a physical-play screen + its state machine, the
`createGame(mode)` parameter, a web-exclusion mechanism, and the production wiring that binds the
emulator as the `BoardConnection`.

### Key Discoveries:

- Board contract is typed end-to-end; consumers never touch bytes — `domain/board/BoardConnection.kt`,
  `domain/board/BoardEvents.kt`, `domain/board/BoardCommand.kt`.
- `events` is hot with `replay=0`: **subscribe before driving/connecting** or the on-connect
  snapshot+status burst is missed (`BoardConnection.kt` doc; emulator E2E test
  `EmulatedBoardEndToEndTest.kt`).
- The §6.2 acceptance gate is the synchronous journal write — `PlayViewModel.acceptMove`
  (`presentation/play/PlayViewModel.kt:273-308`): `validate → sanForMove → writePgn →
  autoSaver.acceptMove → advance UI → launch sync`.
- `sanForMove(position, move)` **throws** on an illegal move (`pgn/SanWriter.kt:25-37`) — the
  interpreter must only ever hand it a `legalMoves`-sourced move.
- No FEN **parser** ships in production (`domain/chess/Fen.kt` has `toFen()` only). S-06 needs none —
  the expected position is always the in-memory `positions.last()`, and start/snapshot checks compare
  occupancy bitmaps, not FEN.
- History routing already branches on mode — `App.kt:77-83`: in-progress **digital** → `PlayKey`,
  else → `ReplayKey`. The route graph is shared in `commonMain` (no per-target App), so keeping
  physical off web needs an **active** gate, not passive non-reachability
  (`presentation/navigation/Routes.kt`, `lessons.md` "Web target is digital-only").
- `GameAutoSaver` already implements the terminal-flush keep-retrying behavior that `lessons.md`
  scopes to "S-06 physical finish included."
- The emulator's `EmulatedBoard` takes an injectable `CoroutineScope` and uses virtual time in tests
  (`kotlinx-coroutines-test`); the scenario DSL (`quietMove`, `capture(order)`, `castle(order)`,
  `enPassant`, `promotionPush`, `adjust`) is chess-agnostic and never presses the confirm button.

## Desired End State

On **Android and iOS**, the player taps "New game", picks **Physical**, and lands on a physical-play
screen. The screen connects to the (emulated) board, verifies the opening position, and shows the
canonical board (White-bottom + flip). As the player lifts/places pieces, lifted squares are
highlighted; pressing the **correct side's** button resolves the sequence into one legal move,
validates it, and durably saves it into the same PGN record as digital play — captures, castling, and
en passant included. A promotion push raises the in-app picker and blocks confirmation until a piece
is chosen. Mate/stalemate auto-closes the game (S-05 path reused); manual end works too. The finished
record replays identically in S-02's Replay view.

On **web**, none of the above exists: the mode picker offers no Physical option, and a physical game
synced from mobile opens in **Replay** (read-only), never a board screen.

The hardest bet is proven by an **emulator-driven end-to-end test** green on all three KMP targets:
a scripted physical game (quiet moves, captures in both orders, castling incl. interleaved, en
passant, a promotion, a confirm-before-pick rejection) yields a canonical PGN that round-trips
through `parsePgn`.

**Verification of the end state:** `:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`,
and `:shared:wasmJsTest` all green (incl. the new interpreter corpus, the MVI reducer tests, and the
emulator E2E); the app builds on Android/iOS/web; manual three-surface check of picker gating +
History routing of a physical row (board screen on mobile, Replay on web).

## What We're NOT Doing

- **No real BLE / hardware** (S-09). The only `BoardConnection` impl bound is the promoted emulator.
- **No diagnostics / guided recovery** (S-07): no live reed-switch grid (FR-011), no
  `SetMode(DIAGNOSTIC)` UI, no step-by-step restore flow. Rejection is minimal (message + no save).
- **No reconnect reconcile** (S-07) and **no resume-after-restart** (S-08): S-06 only **pauses move
  acceptance** while `connectionState == DISCONNECTED`.
- **No GUI board simulator** (PRD OQ-1): the emulator is driven by tests, not by a clickable
  on-screen simulator. There is no in-app affordance to move pieces on the emulator.
- **No DB migration**: `games.mode` already accepts `'physical'`; RLS unchanged.
- **No new eval / SAN / FEN-parser / takeback** work. No web physical mode (per `lessons.md`).
- **No changes to `GameJournal` / `GameAutoSaver` internals** — reused unchanged.

## Implementation Approach

Bottom-up along reuse boundaries, mirroring S-04:

1. **Pure interpreter first** (Phase 1) — the hardest bet, isolated and exhaustively tested before any
   UI or persistence exists.
2. **Thin data/platform seams** (Phase 2) — `createGame(mode)`, the capability flag, and the emulator
   promotion, each independently testable.
3. **Headless MVI state machine** (Phase 3) — the physical-play logic as a pure `reduce(State, Msg)`
   + effects, driven by the board stream and the interpreter, provable without Compose.
4. **UI + navigation + DI** (Phase 4) — compose the screen from existing components, gate it off web,
   wire routing and per-platform DI.
5. **Emulator-driven E2E + write-backs** (Phase 5) — prove the whole stack against scripted games on
   three targets; update roadmap/lessons/contract.

The interpreter and MVI core live in `commonMain` (pure logic, all targets); the physical screen,
board binding, and VM registration are gated to Android/iOS via the capability flag and per-platform
Koin modules.

## Critical Implementation Details

- **Resolve from the full sequence, not a snapshot.** The interpreter builds a signature from the
  accumulated `SquareEvent`s — net-vacated squares, net-arrived squares, and squares that were
  *lifted then re-occupied* (capture destinations) — and matches it against the footprint of each
  move in `legalMoves(position)`. A bare before/after snapshot cannot distinguish a capture (its
  destination shows no net occupancy change) from a quiet move; the lift event on the destination is
  the discriminator. Castling (two pieces, possibly interleaved/airborne) and en passant (captured
  pawn not on the landing square) are likewise only resolvable from the event stream. Reuse the
  capture/castle/en-passant derivations that already exist in `pgn/SanWriter.kt` and
  `pgn/PgnParser.kt:257-266` rather than re-deriving them.
- **j'adoube / sensor noise tolerance.** A piece lifted and replaced on the *same* square with no
  linked move (the `adjust` primitive — byte-identical to a transient reed blip) must not break
  resolution: a lifted-then-re-occupied square is treated as a capture destination *only if* a legal
  move lands there from a net-vacated origin; otherwise it is ignored.
- **§6.2 acceptance gate is preserved through the MVI effect, not the reducer.** Confirmation is
  two-step: `ConfirmPressed` → an **effect** that runs `resolve → validate → sanForMove → writePgn →
  GameAutoSaver.acceptMove` (the synchronous journal write) → feeds back `MoveCommitted(nextPosition,
  san)` or `MoveRejected(reason)`. The reducer performs **no IO**, so a failed save can never leave
  the state showing an accepted move.
- **Wrong-side button is a no-op**, not a rejection: if `button != position.sideToMove`, ignore it
  and keep waiting (decision: chess-clock semantics).
- **Promotion is detected on the place, not the confirm.** When the accumulated sequence lands a pawn
  on the last rank, raise the picker immediately and **block** the next `ConfirmPressed` until a piece
  is chosen (contract §1.5: "blocking acceptance of the next BUTTON_EVENT until the user picks"). A
  confirm pressed before picking shows a "Pick promotion piece" reminder and saves nothing.
- **Hot, no-replay stream.** The VM must begin collecting `board.events` **before** calling
  `connect()`/driving, or it misses the on-connect snapshot+status burst.
- **Web exclusion is active.** `supportsPhysicalBoard` (expect/actual: true on Android/iOS, false on
  wasm) gates the picker option and History→physical routing; the `BoardConnection` and
  `PhysicalPlayViewModel` are registered only in the Android/iOS platform Koin modules. A physical row
  on web routes to Replay.
- **No FEN parsing.** Start-position and (future) snapshot checks compare a `Position.toOccupancy():
  Long` bitmap against `BoardSnapshot.occupancy`; the expected position is always the in-memory
  `positions.last()`.

---

## Phase 1: Sequence Interpreter (pure domain)

### Overview

Implement the heart of the slice — a pure, deterministic function that resolves a recorded lift/place
sequence into exactly one legal move (or a typed rejection) — and prove it exhaustively on all three
targets before any UI or persistence exists.

### Changes Required:

#### 1. Sequence interpreter

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/SequenceInterpreter.kt`

**Intent**: Given the confirmed `Position` and the `SquareEvent`s recorded since the last
confirmation, resolve the move by matching the observed occupancy signature against the footprints of
`legalMoves(position)`. Tolerate j'adoube/noise; surface promotion as a distinct outcome; reject
ambiguous/illegal/incomplete sequences without ever fabricating a move.

**Contract**: New public, pure API in `commonMain`:

```kotlin
sealed interface Resolution {
    data class Resolved(val move: Move) : Resolution            // exactly one legal match
    data class NeedsPromotion(val from: Int, val to: Int) : Resolution  // 4 promo moves share from/to
    data object Ambiguous : Resolution                          // >1 non-promotion legal match
    data object Illegal : Resolution                            // matches no legal move
    data object Incomplete : Resolution                         // piece(s) in hand / no resolvable move
}

fun resolvePhysicalMove(position: Position, events: List<SquareEvent>): Resolution
```

Matching derives each candidate move's footprint (vacated / arrived / captured-in-place / en-passant
captured square / castling rook squares) from `position` using the existing
`SanWriter`/`PgnParser` derivations. `NeedsPromotion` is returned when the unique footprint match is a
promoting pawn push/capture (the four promotion `Move`s collapse to one from/to). Resolution must be
order-independent (capture orders, castling `KING_FIRST`/`ROOK_FIRST`/`INTERLEAVED`).

#### 2. Occupancy helper

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/Occupancy.kt`

**Intent**: Convert a `Position` to the board's occupancy bitmap so the opening position (Phase 3) and
any snapshot can be compared without FEN.

**Contract**: `fun Position.toOccupancy(): Long` — bit `N` set iff `position.pieceAt(N) != null`, using
the a1=0 convention (`Square.kt`). Inverse-compatible with `BoardSnapshot.isOccupied`.

#### 3. Interpreter test corpus

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/domain/board/SequenceInterpreterTest.kt`

**Intent**: Lock the resolution contract against every move shape and failure mode, on all targets.

**Contract**: Cases — quiet move; capture (`CAPTURED_FIRST` and `MOVER_FIRST`); castling
(`KING_FIRST`, `ROOK_FIRST`, `INTERLEAVED`, both sides); en passant (both orders); promotion push and
promotion capture → `NeedsPromotion`; j'adoube before a real move (ignored); a lone lift then nothing
→ `Incomplete`; an off-board/illegal landing → `Illegal`; a sequence matching two legal moves →
`Ambiguous`. Build event lists with the existing `BoardScenarios` DSL where convenient.

### Success Criteria:

#### Automated Verification:

- Interpreter suite passes on JVM host: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- Passes on iOS sim: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:iosSimulatorArm64Test --console=plain --no-daemon`
- Passes on web: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:wasmJsTest --console=plain --no-daemon`
- ktlint clean: `cd SmartChessboard && ktlint -F`

#### Manual Verification:

- Review the corpus for coverage of all move types in both capture orders and all three castling
  orders, plus each rejection outcome.
- Confirm the interpreter only ever returns a `Move` sourced from `legalMoves` (never hand-built).

**Implementation Note**: After automated verification passes, pause for manual confirmation before
Phase 2.

---

## Phase 2: Data & Platform Seams

### Overview

Add the small, independently-testable seams the rest of the slice depends on: a `mode` parameter on
game creation, the per-platform physical-board capability flag, and the promotion of `EmulatedBoard`
to `commonMain` so it can be bound as a production `BoardConnection`.

### Changes Required:

#### 1. `createGame(mode)` across the repository contract

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GamesRepository.kt`

**Intent**: Let callers create either a digital or a physical game; remove the hardcoded digital
assumption from the contract.

**Contract**: `suspend fun createGame(whiteLabel: String, blackLabel: String, mode: GameMode):
GameRecord` (replaces the two-arg form). Update the interface KDoc (currently "Creates a digital…
game").

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/games/SupabaseGamesRepository.kt`

**Intent**: Serialize the chosen mode instead of the `"digital"` literal.

**Contract**: `createGame` passes `NewGameDto(mode = mode.toModeColumn(), …)` where `toModeColumn()`
is the inverse of the existing `parseMode` (`DIGITAL→"digital"`, `PHYSICAL→"physical"`). No migration;
`changes` still emits on create.

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/FakeGamesRepository.kt`

**Intent**: Mirror the new signature in the test fake.

**Contract**: `createGame(white, black, mode)` records `mode` on the produced `GameRecord`. Update all
existing call sites in tests (pass `GameMode.DIGITAL`).

#### 2. Platform capability flag

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/platform/PlatformCapabilities.kt`

**Intent**: One per-platform truth for "can this target drive a physical board," consumed by the
picker and by navigation routing.

**Contract**: `expect val supportsPhysicalBoard: Boolean`. Actuals: `androidMain` + `iosMain` → `true`;
`wasmJsMain` → `false`.

#### 3. Promote the emulator to `commonMain`

**File**: move `…/commonTest/…/data/board/emulator/EmulatedBoard.kt` → `…/commonMain/…/data/board/emulator/EmulatedBoard.kt`

**Intent**: Make the emulator a bindable production `BoardConnection` (the F-02-intended promotion);
keep the chess-agnostic scenario DSL test-only.

**Contract**: `EmulatedBoard` (and its `EmulatedDeviceStatus` config) move to `commonMain` unchanged;
`BoardScenarios.kt` and all emulator tests **stay** in `commonTest`. No behavior change — existing
emulator tests must stay green.

#### 4. Contract write-back

**File**: `docs/reference/contract-surfaces.md` (and a dated one-line note in `context/foundation/prd.md` Implementation Decisions)

**Intent**: Record that `createGame` now carries `mode`.

**Contract**: Amend §3.2's create operation note (`mode` is now an explicit client argument; RLS and
`auth.uid()` default unchanged). Bump `updated` frontmatter.

### Success Criteria:

#### Automated Verification:

- All three target suites pass (repository create-physical test; emulator tests still green after the
  move): `:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest` (full commands as in Phase 1).
- A test asserts `createGame(..., PHYSICAL)` produces a `GameRecord` with `mode == PHYSICAL` and that
  the Supabase DTO column is `"physical"`.
- ktlint clean.

#### Manual Verification:

- Confirm `supportsPhysicalBoard` actuals: `true` on Android/iOS source sets, `false` on wasm.
- Confirm `BoardScenarios` and emulator tests remain in `commonTest` (no test DSL shipped in
  `commonMain`).

**Implementation Note**: Pause for manual confirmation before Phase 3.

---

## Phase 3: Physical Play State Machine (MVI core, headless)

### Overview

Implement the physical-play logic as a pure MVI core — `reduce(State, Msg)` + explicit effects —
driven by the board event stream and the Phase-1 interpreter, reusing the S-04 durable back half.
Provable headlessly (no Compose) on all three targets.

**MVI justification (required by `lessons.md`):** MVI is used **only** for `PhysicalPlayViewModel`;
the digital `PlayViewModel` stays MVVM — a deliberate, screen-specific exception, not a codebase-wide
switch. `lessons.md` permits MVI for genuinely complex, event-heavy state machines and explicitly
names "the live game board" and "BLE connection/pairing flows" as candidates — this screen becomes
both by S-09. It merges an inbound board-event stream (lift/place, button, snapshot, device status,
connection state) with a sequence-accumulation state machine that grows monotonically across S-07
(paused / diagnostic / restoring states + outbound `SetMode`/`RequestSnapshot` commands) and S-09
(real BLE pairing / lifecycle). MVVM would scatter these transitions across the event collector and
intent methods; MVI centralizes every `(state, message)` transition in one pure, exhaustively
testable reducer — the same test shape S-07/S-08 reuse. A failed save can never show an accepted move
because the reducer performs no IO (see Critical Implementation Details).

### Changes Required:

#### 1. MVI message, state, and effect types

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayContract.kt`

**Intent**: Funnel board events and user intents into one `Msg` type, model the screen as one
immutable `State`, and name the IO the reducer may request as `Effect`s.

**Contract** (shape; exact fields finalized in code):

```kotlin
sealed interface PhysicalMsg {
    // board-origin
    data object BoardConnected : PhysicalMsg
    data object BoardDisconnected : PhysicalMsg
    data class SnapshotReceived(val occupancy: Long) : PhysicalMsg
    data class SquareLifted(val square: Int) : PhysicalMsg
    data class SquarePlaced(val square: Int) : PhysicalMsg
    data class ConfirmPressed(val button: BoardButton) : PhysicalMsg
    // user-origin
    data class PromotionPicked(val piece: PieceType) : PhysicalMsg
    data object PromotionDismissed : PhysicalMsg
    data object EndGameRequested : PhysicalMsg
    data class ResultPicked(val result: GameResult) : PhysicalMsg
    data object EndGameConfirmed : PhysicalMsg
    data object EndGameDismissed : PhysicalMsg
    data object FlipBoard : PhysicalMsg
    data object Retry : PhysicalMsg
    // effect-result
    data class Loaded(/* positions, sanMoves, labels, meta, status, result */) : PhysicalMsg
    data object LoadFailed : PhysicalMsg
    data class MoveCommitted(val nextPosition: Position, val san: String) : PhysicalMsg
    data class MoveRejected(val reason: Resolution /* or a reason enum */) : PhysicalMsg
}

sealed interface PhysicalEffect {
    data object Connect : PhysicalEffect
    data class LoadGame(val gameId: String) : PhysicalEffect
    data class CommitMove(val confirmed: Position, val sanSoFar: List<String>, val move: Move) : PhysicalEffect
    data class FinishGame(val result: GameResult, val pgn: String) : PhysicalEffect
    data class Send(val command: BoardCommand) : PhysicalEffect
}

data class ReduceResult(val state: PhysicalPlayState, val effects: List<PhysicalEffect>)
fun reduce(state: PhysicalPlayState, msg: PhysicalMsg): ReduceResult
```

`PhysicalPlayState` is `Loading | Error | Playing(...)`. `Playing` carries the S-04 play fields
(`positions`, `sanMoves`, `status`, `orientation`, `syncPending`, white/black labels, `result`,
`endGamePrompt`, `pendingPromotion`) plus physical-specific fields: `connectionState`,
`liftedSquares: Set<Int>` (for highlight, decision 8), `eventsSinceConfirm`, `paused: Boolean`
(disconnect), `setupMismatch: Boolean` (start verification), and a transient `rejection` message.

#### 2. The reducer

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt` (or co-located with the contract)

**Intent**: Encode every transition purely, with no IO.

**Contract**: Key transitions —
- `SnapshotReceived` while loading-after-connect → compare `occupancy` to `positions.last()
  .toOccupancy()`; mismatch sets `setupMismatch=true` (show "set up the board"); match clears it.
- `SquareLifted`/`SquarePlaced` → append to `eventsSinceConfirm`, recompute `liftedSquares`; if the
  accumulated sequence lands a pawn on the last rank, set `pendingPromotion` (raise picker, decision 9).
- `ConfirmPressed(button)` → if `button != position.sideToMove`, **no-op**; if `pendingPromotion` and
  no piece chosen, set the "pick promotion" reminder and **no-op**; otherwise emit
  `CommitMove(confirmed, sanMoves, resolvedMove)` (the effect runs resolve+validate+SAN+writePgn+
  journal write).
- `MoveCommitted` → advance `positions`/`sanMoves`, clear `eventsSinceConfirm`/`liftedSquares`/
  `pendingPromotion`, recompute `status`; if mate/stalemate, emit `FinishGame` (S-05 auto-close).
- `MoveRejected` → set transient `rejection`, clear the in-progress sequence (minimal-reject; player
  physically restores and retries).
- `BoardDisconnected` → `paused=true` (stop accepting confirms); `BoardConnected` → `paused=false`.
- End-game intents mirror `PlayViewModel`'s pick→confirm→finish.

#### 3. The ViewModel (effect interpreter + stream collector)

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayViewModel.kt`

**Intent**: Own the impure shell: collect `boardConnection.events` (mapped to `Msg`) **before**
connecting, run effects against the reused `GamesRepository`/`GameAutoSaver`, and expose
`StateFlow<PhysicalPlayState>`.

**Contract**: Constructor `(gameId, gamesRepository, autoSaver, boardConnection, parseDispatcher =
Default)`. Subscribes to `events` in `viewModelScope` first, then emits `Connect`. `CommitMove` effect
runs `validate → sanForMove → writePgn(meta, sanSoFar + san) → autoSaver.acceptMove(gameId, pgn)` then
posts `MoveCommitted`; on any non-`Legal`/throw posts `MoveRejected`. `FinishGame` effect calls
`autoSaver.finishGame`. `meta`/`reconcile`/`load` mirror `PlayViewModel` (`metaFor` already writes
`[Mode "physical"]`). Reuses `GameAutoSaver` unchanged (incl. terminal keep-retry flush).

#### 4. Headless reducer + VM tests

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducerTest.kt` and `PhysicalPlayViewModelTest.kt`

**Intent**: Prove transitions purely, and prove the VM lands moves in the canonical record by driving
an `EmulatedBoard` (subscribe-before-connect) and a `FakeGamesRepository`/real `GameAutoSaver` over a
fake journal.

**Contract**: Reducer tests assert `(state, msg) → (state', effects)` for every transition above
(wrong-button no-op, promotion-block, disconnect-pause, reject path, auto-close). VM tests drive a
scripted game and assert the journal/repository PGN advances exactly once per confirm, with
`[Mode "physical"]`. All three targets.

### Success Criteria:

#### Automated Verification:

- Reducer + VM suites pass on all three targets (`:shared:testAndroidHostTest`,
  `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest`).
- A test proves the §6.2 gate: a forced journal-write failure yields `MoveRejected` and the state does
  **not** advance.
- A test proves wrong-side button is a no-op and confirm-before-promotion-pick saves nothing.
- ktlint clean.

#### Manual Verification:

- Review the reducer for IO-freedom (no repository/journal/board calls inside `reduce`).
- Confirm the MVI justification is captured in this plan (it is) for the impl-review.

**Implementation Note**: Pause for manual confirmation before Phase 4.

---

## Phase 4: Physical Play UI, Navigation & DI

### Overview

Compose the physical-play screen from existing components, add the New-game mode picker, wire
navigation and per-platform DI — all gated so web never reaches physical mode.

### Changes Required:

#### 1. Display-only highlight on `ChessBoardView`

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/board/ChessBoardView.kt`

**Intent**: Allow highlighting squares (lifted pieces) without enabling tap input (today `BoardInteraction`
couples highlights to a tap handler).

**Contract**: Add an optional `highlightedSquares: Set<Int> = emptySet()` param rendered as a tint
overlay; unaffected when empty (Replay/Play unchanged). No tap handler involved.

#### 2. Physical play screen

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayScreen.kt`

**Intent**: Render the physical game by reusing the digital components, adding a board-connection
indicator and the "set up the board" / paused / rejection messages.

**Contract**: `fun PhysicalPlayScreen(gameId, onBack, onReviewGame, onBackToHistory)`. Resolves
`PhysicalPlayViewModel` via `koinViewModel(key = "physical-$gameId") { parametersOf(gameId) }`.
Renders `ChessBoardView(position, orientation, interaction = null, highlightedSquares = liftedSquares)`,
`StatusBanner`, `MoveList`, `SyncIndicator`, `EndGameSection`/`EndGamePicker`, `PromotionPicker`
(reused), plus a connection/setup/paused/rejection surface. Flip action reused.

#### 3. New-game mode picker (gated)

**Files**: `presentation/newgame/NewGameViewModel.kt`, `presentation/newgame/NewGameScreen.kt`

**Intent**: Offer Digital/Physical selection on capable platforms; thread the choice through creation;
route physical creations to the physical screen.

**Contract**: `NewGameViewModel.create(white, black, mode)` → `createGame(white, black, mode)`. Screen
shows a Digital/Physical toggle **only when `supportsPhysicalBoard`** (web shows none, defaults
digital). On success, the screen needs to know the created mode to navigate (`onGameCreated(gameId,
mode)` or expose `mode` in state).

#### 4. Route + navigation gating

**Files**: `presentation/navigation/Routes.kt`, `App.kt`, `presentation/navigation/BrowserNavigation.wasmJs.kt`

**Intent**: Add a physical-play route, branch History to it for in-progress physical games on capable
platforms, and keep web on Replay.

**Contract**: New `@Serializable data class PhysicalPlayKey(val gameId: String) : NavKey`, registered
in `navSavedStateConfiguration` (iOS/wasm need explicit polymorphic registration). `App.kt` History
routing becomes: `IN_PROGRESS && mode == PHYSICAL && supportsPhysicalBoard → PhysicalPlayKey`;
`IN_PROGRESS && mode == DIGITAL → PlayKey`; else `ReplayKey` (so a physical row on web → Replay).
`entry<NewGameKey>` routes physical creations to `PhysicalPlayKey`. `entry<PhysicalPlayKey>` renders
`PhysicalPlayScreen`. Add a browser fragment mapping for completeness (web never reaches it).

#### 5. Per-platform DI

**Files**: `di/PlatformModule.android.kt`, `di/PlatformModule.ios.kt` (and leave `PlatformModule.wasmJs.kt` untouched)

**Intent**: Bind the emulator as the `BoardConnection` and register the physical VM **only** on
Android/iOS.

**Contract**: Android/iOS platform modules add `single<BoardConnection> { EmulatedBoard(scope = <long-lived
app scope>) }` and `viewModel { (gameId: String) -> PhysicalPlayViewModel(gameId, get(), get(), get()) }`.
The wasm module registers neither. `GameAutoSaver` factory reused as-is.

### Success Criteria:

#### Automated Verification:

- All three target suites still green; `NewGameViewModel` test covers `create(..., PHYSICAL)`.
- The app compiles on every target (Android, iOS framework, wasm), e.g.
  `./gradlew :shared:compileKotlinWasmJs :shared:assemble` and the per-app build tasks.
- ktlint clean.

#### Manual Verification:

- **Android/iOS**: New game → picker shows Physical → create → land on `PhysicalPlayScreen`; the
  emulator connects, the opening position verifies, the board shows "ready" (no driver in-app — move
  input is the emulator/test or S-09's real board).
- **Web**: New game shows **no** Physical option; a physical game synced from mobile opens in
  **Replay**, not a board screen; browser Back behaves.
- Confirm no `BoardConnection`/physical VM is resolvable on wasm (DI gating holds).

**Implementation Note**: Pause for manual confirmation before Phase 5.

---

## Phase 5: Emulator-Driven End-to-End & Write-Backs

### Overview

Prove the whole stack against scripted physical games on three targets, then update the foundation
docs.

### Changes Required:

#### 1. End-to-end test

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalCaptureEndToEndTest.kt`

**Intent**: Drive an `EmulatedBoard` through the real `PhysicalPlayViewModel` + reused
journal/auto-saver, asserting moves land in the canonical PGN and the finished record replays.

**Contract**: Create a `PHYSICAL` game; subscribe-before-connect; script a game with the
`BoardScenarios` DSL covering a quiet move, a capture (each order), castling (incl. `INTERLEAVED`), en
passant, and a promotion (push → picker → pick → confirm), plus a confirm-before-pick rejection and a
wrong-side-button no-op. Assert: each correct confirm appends exactly one SAN; the stored PGN carries
`[Mode "physical"]`; the game auto-closes on a mate scenario (and/or manual end records the result);
`parsePgn(storedPgn)` round-trips to the same positions S-02 would replay. Green on all three targets.

#### 2. Roadmap, lessons, and status write-backs

**Files**: `context/foundation/roadmap.md`, `context/foundation/lessons.md`, `context/changes/physical-capture-emulated/change.md`

**Intent**: Record completion and capture any new recurring rule.

**Contract**: Flip S-06 status to `implemented` in the roadmap (At-a-glance + Slices + Stream C note).
Add a `lessons.md` entry if warranted (candidate: "occupancy-only capture/j'adoube disambiguation must
read the lift event on the destination, not the net snapshot"). Set `change.md` `status: implemented`,
`updated`.

### Success Criteria:

#### Automated Verification:

- The E2E test is green on `:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`, and
  `:shared:wasmJsTest`.
- The full `:shared` suite (engine, board, games, presentation) is green on all three targets.
- ktlint clean.

#### Manual Verification:

- Review the stored PGN from the E2E test: legal moves only, `[Mode "physical"]`, correct result tag.
- Confirm the roadmap/lessons write-backs are accurate.
- Spot-check on a device that a created physical game appears in History with the Physical label and
  (on mobile) opens the physical screen.

**Implementation Note**: After this phase, the slice is code-complete and emulator-verified;
interactive real-board play remains S-09.

---

## Testing Strategy

### Unit Tests:

- **Interpreter** (Phase 1): every move shape × capture/castling orders; promotion; j'adoube/noise;
  ambiguous/illegal/incomplete rejections.
- **Reducer** (Phase 3): pure `(state, msg)` transitions — wrong-button no-op, promotion-block,
  disconnect-pause, reject, auto-close; IO-freedom.
- **Repository/fake** (Phase 2): `createGame(..., PHYSICAL)` mode round-trip.

### Integration Tests:

- **VM + emulator + journal** (Phase 3): scripted game drives the VM; PGN advances once per confirm.
- **End-to-end** (Phase 5): create → script full game (capture/castle/ep/promotion) → confirms →
  canonical PGN → mate/manual end → `parsePgn` round-trip. All on three targets.

### Manual Testing Steps:

1. Android/iOS: create a physical game from the picker; confirm the screen connects and verifies the
   opening position.
2. Web: confirm no Physical option; a synced physical game opens in Replay.
3. Review E2E PGN output for legality and `[Mode "physical"]`.

## Performance Considerations

Resolution is `O(legalMoves)` (~20–40 candidates) over a short event list — trivially inside the
500 ms accepted-move NFR. The board stream is virtual-time in tests; production uses an injected
long-lived scope.

## Migration Notes

None. `games.mode` already accepts `'physical'` (CHECK constraint in place since the schema's
creation); RLS and the `user_id` default are unchanged. The only schema-adjacent change is the
`createGame` client signature, documented as a contract write-back, not a DB migration.

## References

- Roadmap slice S-06: `context/foundation/roadmap.md`
- Contracts: `docs/reference/contract-surfaces.md` §1.3–§1.7 (board events, promotion §1.5), §2.2/§3.2
  (games), §5 (PGN/FEN), §6.2 (acceptance ordering)
- Prerequisite plans: `context/changes/reed-board-emulator/plan.md`,
  `context/changes/chess-rules-engine/plan.md`, `context/changes/digital-pass-and-play/plan.md`
- Acceptance reference pattern: `presentation/play/PlayViewModel.kt:273-308` (`acceptMove`),
  `EmulatedBoardEndToEndTest.kt` (subscribe-before-drive)
- Rules: `context/foundation/lessons.md` (MVVM/MVI default, web-is-digital-only, Native-target test
  rule, wasm `Throwable` rule, terminal-flush retry)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Sequence Interpreter (pure domain)

#### Automated

- [x] 1.1 Interpreter suite passes on JVM host (`:shared:testAndroidHostTest`) — b969215
- [x] 1.2 Interpreter suite passes on iOS sim (`:shared:iosSimulatorArm64Test`) — b969215
- [x] 1.3 Interpreter suite passes on web (`:shared:wasmJsTest`) — b969215
- [x] 1.4 ktlint clean — b969215

#### Manual

- [ ] 1.5 Corpus reviewed for all move types, both capture orders, all castling orders, every rejection outcome
- [ ] 1.6 Confirmed the interpreter only returns `legalMoves`-sourced moves

### Phase 2: Data & Platform Seams

#### Automated

- [x] 2.1 Three target suites pass (incl. emulator tests still green after the move) — d76337a
- [x] 2.2 Test asserts `createGame(..., PHYSICAL)` → `GameRecord.mode == PHYSICAL` and DTO column `"physical"` — d76337a
- [x] 2.3 ktlint clean — d76337a

#### Manual

- [ ] 2.4 `supportsPhysicalBoard` actuals confirmed (true Android/iOS, false wasm)
- [ ] 2.5 `BoardScenarios` + emulator tests confirmed still in `commonTest`

### Phase 3: Physical Play State Machine (MVI core, headless)

#### Automated

- [x] 3.1 Reducer + VM suites pass on all three targets — 0b3db69
- [x] 3.2 §6.2 gate test: forced journal-write failure → `MoveRejected`, state does not advance — 0b3db69
- [x] 3.3 Wrong-side button no-op and confirm-before-promotion-pick save nothing (tested) — 0b3db69
- [x] 3.4 ktlint clean — 0b3db69

#### Manual

- [ ] 3.5 Reducer reviewed for IO-freedom
- [ ] 3.6 MVI justification confirmed captured in the plan

### Phase 4: Physical Play UI, Navigation & DI

#### Automated

- [x] 4.1 Three target suites green; `NewGameViewModel` covers `create(..., PHYSICAL)`
- [x] 4.2 App compiles on Android, iOS, and wasm
- [x] 4.3 ktlint clean

#### Manual

- [ ] 4.4 Android/iOS: picker shows Physical → create → physical screen connects + verifies opening position
- [ ] 4.5 Web: no Physical option; synced physical game opens in Replay; browser Back behaves
- [ ] 4.6 Confirmed no `BoardConnection`/physical VM resolvable on wasm

### Phase 5: Emulator-Driven End-to-End & Write-Backs

#### Automated

- [ ] 5.1 E2E test green on all three targets
- [ ] 5.2 Full `:shared` suite green on all three targets
- [ ] 5.3 ktlint clean

#### Manual

- [ ] 5.4 Stored E2E PGN reviewed (legal moves, `[Mode "physical"]`, correct result tag)
- [ ] 5.5 Roadmap/lessons write-backs reviewed for accuracy
- [ ] 5.6 Device spot-check: physical game in History with Physical label, opens physical screen on mobile
