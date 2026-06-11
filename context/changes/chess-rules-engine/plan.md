# Chess Rules Engine (F-01) Implementation Plan

## Overview

Build a pure-logic chess rules engine in the mobile app's `domain/` layer that provides
**full move legality** (check, pinned pieces, king safety, castling, en passant, promotion)
and **checkmate/stalemate detection** — the foundation every later play channel validates
through. It runs identically on Android, iOS, and WasmJS because it lives entirely in
`commonMain` with zero platform or framework dependencies.

This is roadmap item **F-01**, satisfying PRD **FR-005** (full-legality validation),
**FR-007** (mate/stalemate detection), and the Guardrail *"the product never saves an
illegal chess move."* It unlocks S-04 (digital play), S-06 (physical capture), and S-05
(game end), and is a prerequisite for the "never save an illegal move" verification path.

## Current State Analysis

- **Clean Architecture by layer is live.** `domain/auth/` and `domain/games/` exist;
  `domain/chess/` is greenfield. The layer's rule (pure Kotlin, no Compose/Supabase/Room/BLE)
  fits a rules engine exactly — see [GameSummary.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameSummary.kt).
- **`domain/games/GameSummary`** is high-level game *metadata* (id, mode, status, result,
  labels) — it does **not** model board state. The chess engine is the lower-level layer that
  GameSummary's lifecycle (in_progress → finished) will eventually be driven by, but there is
  no overlap or conflict today.
- **Test discipline is settled** (from S-01): `commonTest` with `kotlin-test` +
  `kotlinx-coroutines-test`, **hand-written fakes, no mocking library** for domain
  boundaries. The engine is pure synchronous logic, so it needs neither coroutines nor fakes —
  just `kotlin-test` assertions. Per-target verification: `:shared:testAndroidHostTest`,
  `:shared:wasmJsTest`, `:shared:iosSimulatorArm64Test`.
- **DI = Koin, pattern = MVVM** are already committed (S-01). The engine is stateless pure
  functions / immutable values; it needs **no** Koin wiring (no lifecycle, no interfaces to
  inject for MVP — consumers call it directly). This keeps F-01 free of DI churn.
- **No new dependencies required.** `kotlinx-serialization-json` and `kotlinx-coroutines-core`
  are present; the engine uses neither. Avoiding new libs means no `yarn.lock` actualization
  step on the wasm target.

### Contract constraints (from `docs/reference/contract-surfaces.md`)

- **PGN is the durable source of truth** (§5.1); FEN is *derived on demand*, never stored per
  move; the eval cache is keyed by FEN (§5.4). The engine therefore must hold **FEN-complete
  state** so a consuming slice can serialize an exact FEN, but the serialization itself is out
  of scope here (see "What We're NOT Doing").
- **Square indexing is fixed by the BLE layer** (§1.3): `index = file + 8 * rank`, with
  file a–h = 0–7 and rank 1–8 = 0–7, so a1 = 0, h1 = 7, a8 = 56, h8 = 63. The engine adopts
  this convention internally to remove any translation layer at the physical boundary (S-06).
- **Promotion** (§1.5, FR-006): the player *must* choose the promoted piece in the UI before a
  move is saved; the engine never auto-promotes.
- **Terminal scope** (FR-007): checkmate and stalemate only. Threefold repetition, 50-move,
  and insufficient material are **not** auto-detected (manual end-of-game, FR-018) — out of
  scope for F-01.

## Desired End State

A `org.rurbaniak.smartchessboard.domain.chess` package exposes an immutable `Position`, a
`Move`, a legal-move generator, a move applicator returning a sealed `Legal/Illegal` outcome,
and a `status(position)` terminal-state function — all verified by a perft suite plus curated
edge-case tests that run green on Android-host, iOS-sim, and WasmJS targets.

**Verification of the end state:** the three per-target test tasks pass, the perft node counts
for the standard reference positions (start position, Kiwipete) match published values to the
chosen depth, and a downstream consumer (S-04) can, using only the public API, (a) enumerate
the legal moves of any position, (b) attempt a move and receive either the resulting position
or a structured rejection reason, and (c) ask whether a position is checkmate or stalemate.

### Key Discoveries:

- Engine belongs in `commonMain/.../domain/chess/`, parallel to `domain/games/` — pure Kotlin,
  no DI, no new deps ([AppModules.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/di/AppModules.kt) shows the existing module wiring the engine does **not** need to join).
- Square convention `index = file + 8 * rank`, a1 = 0 (contract §1.3) is binding for cross-layer
  consistency.
- Test pattern: `commonTest`, `kotlin-test` only, no mocking lib (tech-stack "fakes-first";
  engine needs neither). Gradle form: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:<task> --console=plain --no-daemon`.
- Mate/stalemate fall out of legal-move generation for free (FR-007) — `status` is a thin
  function over `legalMoves` + `isInCheck`.

## What We're NOT Doing

- **No SAN generation.** Turning a `Move` into `e4` / `O-O` / `exd5` / `e8=Q+` / `#` notation is
  deferred to the consuming slice that writes PGN (S-04). The engine returns structured `Move`
  values, not algebraic strings.
- **No FEN serialization or parsing.** `Position` *holds* every field a FEN needs, but
  `Position → "rnbq…"` and `"rnbq…" → Position` are not built here. (FEN serialization lands
  with the eval/replay slices that need it; the FEN-complete state guarantees no Position rework
  when they do.)
- **No PGN parsing / round-trip / movetext.** Reading a saved game back into positions is
  replay's job (S-02).
- **No draw-by-rule detection.** Threefold repetition, 50-move, insufficient material — manual
  per FR-018. (The `halfmoveClock` counter is *maintained* for FEN correctness and future use,
  but never *claimed* as a draw.)
- **No engine search / evaluation.** Position scoring is the external Lichess / Chess-API.com
  chain (S-03); the engine does no minimax, no perft-as-search-feature beyond the test harness.
- **No fine-grained illegal-move diagnostics.** `validate` reports only `PROMOTION_PIECE_REQUIRED`
  and `NO_SUCH_MOVE` for MVP. Classifying *why* a move is illegal (not-your-piece, blocked,
  leaves-king-in-check, illegal-castle, bad-en-passant) is a separate classify-the-rejection pass
  deferred to S-07 diagnostics.
- **No DI / Koin wiring, no ViewModel, no UI.** Consumers call the engine directly.
- **No board representation optimization.** A readable 8×8 mailbox, not bitboards — there is no
  search loop to make hot.

## Implementation Approach

A classic, readable pipeline kept deliberately simple because nothing here is performance-bound
(no engine search; NFR is a 500 ms interaction budget, met trivially):

1. **Immutable value model** — `Position` is a `data class` holding a 64-entry mailbox plus
   side-to-move, castling rights, en-passant target, and the two move counters. `applyMove`
   returns a *new* `Position`; the old one is untouched. This makes replay (S-02) a list of
   positions and makes every test a pure input→output assertion.
2. **Pseudo-legal generation, then king-safety filter** — generate every move a piece could
   make ignoring whether it leaves the king in check (Phase 2), then drop any move that leaves
   the mover's own king attacked (Phase 3). This single filter handles pins, check evasion, and
   illegal castling-through-check uniformly, which is simpler and less bug-prone than tracking
   pins explicitly.
3. **Attack detection as the shared primitive** — `isSquareAttacked(position, square, byColor)`
   underpins check detection, castling legality, and the king-safety filter. One correct
   attack function is the spine of the whole engine.
4. **Terminal state derived, not stored** — `status(position)` = (no legal moves ? (in check ?
   Checkmate : Stalemate) : (in check ? Check : Ongoing)). A pure function of `Position`, so it
   works on a freshly-applied move *and* on a position loaded from elsewhere later.
5. **Correctness proven by perft + curated edges** — perft (counting leaf nodes of the legal
   move tree to a fixed depth) from known reference positions is the gold-standard catch for
   whole classes of generation bugs; curated edge cases document per-rule intent and pin the
   mate/stalemate API.

## Critical Implementation Details

- **Square convention is load-bearing and easy to get subtly wrong.** `index = file + 8 * rank`
  with rank 0 = white's first rank means white pawns advance `+8`, black pawns `-8`; white
  promotes on rank 7 (indices 56–63), black on rank 0 (indices 0–7); white castles on rank 0,
  black on rank 7. Every direction offset and rank check must be derived from this, and the
  Phase 1 round-trip test (`squareOf(file, rank)` ↔ `fileOf/rankOf`) exists specifically to
  freeze it before any movement logic depends on it.
- **En passant target lifecycle.** `enPassantTarget` is set **only** by a double pawn push and
  is valid for **exactly one** ply — `applyMove` must clear it on every other move. A stale
  en-passant target is a classic source of illegal-move and perft-mismatch bugs.
- **Castling rights are revoked, never restored.** Moving a king or rook, or a rook being
  captured on its home square, permanently clears the corresponding right within a line of play
  (immutability means "within this Position chain"). Castling legality also requires the king
  not currently in check, not passing through an attacked square, and not landing on one —
  three distinct `isSquareAttacked` checks, not one.

## Phase 1: Domain model & conventions

### Overview

Establish the immutable value types and the square-indexing convention every later phase builds
on. No move logic yet — this phase exists to freeze the public data contract that S-02/S-04/
S-05/S-06 will depend on, so it never has to be reworked.

### Changes Required:

#### 1. Core value types

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/Pieces.kt`

**Intent**: Define the piece vocabulary — colors and piece types — as exhaustive enums the rest
of the engine matches on.

**Contract**: `enum class Color { WHITE, BLACK }` with an `opposite` accessor;
`enum class PieceType { PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING }`; a `Piece(color, type)`
value type. Promotion targets are the four non-pawn, non-king types (KNIGHT/BISHOP/ROOK/QUEEN).

#### 2. Square indexing

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/Square.kt`

**Intent**: Encode the contract §1.3 square convention as the single authority for converting
between (file, rank) and the 0–63 index, so no other file open-codes the arithmetic.

**Contract**: `index = file + 8 * rank`, file a–h = 0–7, rank 1–8 = 0–7 ⇒ a1 = 0, h8 = 63.
Provide `squareOf(file, rank)`, `fileOf(index)`, `rankOf(index)`, and validity checks/offsetting
helpers used by sliding-piece generation. This convention is binding — document the §1.3 source
inline.

#### 3. Move and outcome types

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/Move.kt`

**Intent**: Model a move attempt and the engine's structured answer to applying one.

**Contract**: `Move(from: Int, to: Int, promoteTo: PieceType? = null)`. A pawn move onto the
last rank with `promoteTo == null` is an **incomplete** promotion (rejected in Phase 3, FR-006/
§1.5). `sealed interface MoveOutcome { data class Legal(val position: Position) : MoveOutcome;
data class Illegal(val reason: IllegalReason) : MoveOutcome }`. For MVP, `enum class
IllegalReason` carries only the two causes the legal-set-membership mechanism can actually
produce: `PROMOTION_PIECE_REQUIRED` (incomplete promotion) and `NO_SUCH_MOVE` (the attempt is
not in `legalMoves`). Finer-grained rejection reasons (`NOT_YOUR_PIECE`, `BLOCKED`,
`LEAVES_KING_IN_CHECK`, `ILLEGAL_CASTLE`, `BAD_EN_PASSANT`, …) require a separate
classify-why-rejected pass and are **deferred to S-07 diagnostics** — they are explicitly NOT
built in F-01 (see "What We're NOT Doing"), so the enum and the `validate` mechanism stay
consistent.

#### 4. Position (FEN-complete immutable state)

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/Position.kt`

**Intent**: The immutable board state, carrying every field an exact FEN needs, plus the
standard starting position constructor.

**Contract**: `data class Position(board: List<Piece?> /* size 64, index per Square */,
sideToMove: Color, castlingRights: CastlingRights, enPassantTarget: Int?, halfmoveClock: Int,
fullmoveNumber: Int)`. `CastlingRights` models the four booleans (white/black × king/queen
side). A `Position.start()` factory returns the initial chess position. `pieceAt(square)`
accessor. Immutable: no mutating methods; state transitions come only from `applyMove`
(Phase 3).

#### 5. GameStatus type

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/GameStatus.kt`

**Intent**: The terminal/ongoing classification returned by `status(position)` (populated in
Phase 4; type defined here so the contract is complete up front).

**Contract**: `sealed interface GameStatus { Ongoing; Check; Checkmate; Stalemate }` (data
objects). Naming aligns with FR-007 terminology.

### Success Criteria:

#### Automated Verification:

- Module compiles on all three targets: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:compileKotlinWasmJs :shared:compileKotlinIosSimulatorArm64 :shared:compileAndroidMain --console=plain --no-daemon`
- Square round-trip + start-position invariant tests pass: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- Formatting clean: `cd SmartChessboard && ktlint -F` reports no violations

#### Manual Verification:

- Human review confirms the public type signatures (`Position`, `Move`, `MoveOutcome`,
  `GameStatus`) read as a contract a downstream slice (S-04/S-06) can consume without rework.
- The a1 = 0 convention in `Square.kt` matches contract §1.3 by eye.

**Implementation Note**: After completing this phase and all automated verification passes,
pause for manual confirmation before proceeding.

---

## Phase 2: Pseudo-legal move generation & attack detection

### Overview

Generate every move each piece *could* make ignoring king safety, and build the
`isSquareAttacked` / `isInCheck` primitive that the legality filter and terminal detection both
depend on. Splitting pseudo-legal generation from the king-safety filter keeps each piece's
movement rules readable and independently testable.

### Changes Required:

#### 1. Per-piece pseudo-legal generation

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/MoveGenerator.kt`

**Intent**: Produce the candidate moves for the side to move, covering all piece-specific
geometry, *before* king-safety is considered.

**Contract**: An internal `pseudoLegalMoves(position): List<Move>` covering: pawn single/double
push (double only from the home rank, onto an empty path), pawn diagonal captures, en-passant
capture (when `enPassantTarget` matches), and **promotion expansion** (a pawn reaching the last
rank yields four `Move`s, one per `promoteTo` of QUEEN/ROOK/BISHOP/KNIGHT); knight jumps; sliding
bishop/rook/queen rays (stop at first blocker, capture if enemy); king single-step; and
**castling candidates** (king two-step) gated on castling rights + empty intervening squares
(the *attack* conditions are applied in Phase 3). Direction offsets derive from the §1.3
convention (Phase 1).

#### 2. Attack detection

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/Attacks.kt`

**Intent**: Answer "is square S attacked by color C in this position?" — the shared primitive
for check detection, castling legality, and the king-safety filter.

**Contract**: `isSquareAttacked(position, square, byColor): Boolean` and
`isInCheck(position, color): Boolean` (the latter = the color's king square is attacked by the
opposite color). Attack logic mirrors piece geometry (note: pawn *attack* squares are the
diagonals only, distinct from pawn *moves*).

### Success Criteria:

#### Automated Verification:

- Per-piece generation + attack tests pass: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- Formatting clean: `cd SmartChessboard && ktlint -F`

#### Manual Verification:

- Review confirms pawn-attack vs pawn-move asymmetry is handled (a common bug source).
- Spot-check a hand-set position: the generated pseudo-legal move list for a tricky piece
  (e.g. a pinned rook — still pseudo-legal here) matches expectation.

**Implementation Note**: Pause for manual confirmation after automated verification passes.

---

## Phase 3: Full legality & applyMove

### Overview

Turn pseudo-legal moves into **legal** moves by filtering out any that leave the mover's king in
check, complete the castling and en-passant legality rules, and implement `applyMove` with
correct state transitions (castling-rights revocation, en-passant lifecycle, move counters).
This phase produces the engine's public API.

### Changes Required:

#### 1. Legal move filter & public enumeration

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/ChessRules.kt`

**Intent**: Expose the public `legalMoves` primitive and a `validate` derived from it, with the
king-safety filter that handles pins and check evasion uniformly.

**Contract**: `legalMoves(position): List<Move>` = pseudo-legal moves with each one applied to a
trial board and rejected if `isInCheck(trial, mover)` holds. Castling additionally requires: king
not currently in check, and neither the transit square nor the destination square attacked by the
opponent (three `isSquareAttacked` checks). `validate(position, move): MoveOutcome` resolves the
attempt against the legal set, returning `Illegal(PROMOTION_PIECE_REQUIRED)` for an incomplete
promotion, `Illegal(NO_SUCH_MOVE)` when the attempt is not in `legalMoves`, or
`Legal(applyMove(...))`. (Finer rejection reasons are deferred to S-07 — see Phase 1 §3.)

#### 2. applyMove state transitions

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/ChessRules.kt` (same file)

**Intent**: Produce the next `Position` from a legal move, updating every FEN-relevant field.

**Contract**: `applyMove(position, move): Position` performs: piece relocation; capture removal
(including the en-passant captured pawn on a different square than `to`); rook relocation on
castling; pawn replacement by `promoteTo` on promotion; `sideToMove` flip; `castlingRights`
revocation (king move, rook move, or rook captured on its home square); `enPassantTarget` set
**only** on a double push, cleared otherwise; `halfmoveClock` reset on pawn move or capture, else
incremented; `fullmoveNumber` incremented after Black moves. `applyMove` is internal/assumes a
legal move (the public path is `validate`).

### Success Criteria:

#### Automated Verification:

- Legality + transition tests pass on host: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- Formatting clean: `cd SmartChessboard && ktlint -F`

#### Manual Verification:

- Review the explicit edge-case tests and confirm they cover: an absolute pin (moving the pinned
  piece is rejected), castling blocked because the king transits an attacked square, en-passant
  legal *and* the en-passant-discovers-check rejection, and incomplete-promotion rejection.
- Confirm `castlingRights` / `enPassantTarget` / counters update correctly by reading two or
  three applied-move assertions.

**Implementation Note**: Pause for manual confirmation after automated verification passes.

---

## Phase 4: Terminal-state detection

### Overview

Add `status(position)` deriving Ongoing / Check / Checkmate / Stalemate from legal-move
generation and check detection — satisfying FR-007. Thin, but its own phase to give a clean
checkpoint for the guardrail-adjacent terminal logic that S-05 (game end) depends on.

### Changes Required:

#### 1. status function

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/ChessRules.kt` (same file)

**Intent**: Classify a position's terminal/ongoing state as a pure function, usable on any
position (freshly applied or loaded later), not only as a side effect of a move.

**Contract**: `status(position): GameStatus` = if `legalMoves(position)` is empty then
(`isInCheck(position, sideToMove)` ? `Checkmate` : `Stalemate`) else
(`isInCheck(...)` ? `Check` : `Ongoing`). Pure; no Position mutation.

### Success Criteria:

#### Automated Verification:

- Terminal-state tests pass: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- Formatting clean: `cd SmartChessboard && ktlint -F`

#### Manual Verification:

- Confirm tests include a known checkmate (e.g. fool's mate / back-rank mate), a known stalemate
  (king-and-pawn or queen-confinement stalemate), and a plain check vs ongoing — and that
  `status` returns the FR-007-correct value for each.

**Implementation Note**: Pause for manual confirmation after automated verification passes.

---

## Phase 5: Perft corpus & edge-case suite

### Overview

Prove correctness with a perft harness over standard reference positions plus the curated
edge-case suite, and confirm the whole engine runs green on **all three** KMP targets. This is
the guardrail proof: "the product never saves an illegal move" rests on this phase.

### Changes Required:

#### 1. Perft harness

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/domain/chess/Perft.kt`

**Intent**: A test-only recursive leaf-counter over the legal-move tree, used to compare against
published reference node counts.

**Contract**: `perft(position, depth): Long` = for depth 0 returns 1, else sums `perft(applyMove(
position, m), depth - 1)` over `legalMoves(position)`. Test-source only; not shipped in main.

#### 2. Perft reference tests

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/domain/chess/PerftTest.kt`

**Intent**: Assert perft counts match published values for positions chosen to exercise every
special rule.

**Contract**: Target perft depths are **fixed up front, per target** (a recorded choice, not a
reactive "if slow"):
- **Host (`testAndroidHostTest`)** — start position perft(4) = 197 281 and Kiwipete perft(3)
  = 97 862. (perft(5) = 4 865 609 only as an opt-in/manual run, never in the default suite, to
  keep the dev/CI loop fast.)
- **iOS sim & WasmJS** — start position perft(3) = 8 902 and Kiwipete perft(2) = 2 039. The
  curated edge-case suite (which also runs on all three targets) is the **primary** cross-target
  proof of the special rules; the reduced perft depth on the slower targets is a smoke check, not
  the rule-coverage proof, and this split is documented in-comment.

Kiwipete (`r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -`) deliberately
stresses castling, en passant, and pins. Reference counts are documented inline with their source (Chess
Programming Wiki). Construct these positions via a **test-only** `fen(s: String): Position`
helper living in `commonTest` (e.g. in `Perft.kt`) — NOT in `main`, so the "No FEN parsing"
scope (which governs the shipped engine API) is preserved. The helper lets Kiwipete and the
start position be written as their canonical FEN strings 1:1 from the Chess Programming Wiki,
removing hand-placed-board transcription error as a source of perft mismatch. The helper itself
gets 2–3 round-trip/spot tests (e.g. `fen(startpos)` equals `Position.start()`) so it cannot
silently lie.

#### 3. Curated edge-case suite

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/domain/chess/ChessRulesTest.kt`

**Intent**: Document per-rule intent with hand-built positions mapping directly to FR-005/FR-007
clauses (complementing the statistical perft proof).

**Contract**: Named tests for: absolute pin rejection; castling all four sides legal; castling
rejected through/into/while-in check; castling rights revoked after king/rook move; en passant
legal capture; en passant rejected when it discovers check; promotion produces four moves and
incomplete promotion is rejected; checkmate, stalemate, and check classification. Each asserts
against the public API only.

#### 4. Cross-target green

**Intent**: Confirm the engine and its tests pass on iOS-sim and WasmJS, not just the host JVM —
the engine ships to all three.

**Contract**: No code change; a verification step running `iosSimulatorArm64Test` and
`wasmJsTest`. Perft depth on the slower targets is the reduced, pre-fixed depth from §2
(start perft(3), Kiwipete perft(2)), documented in-comment — a recorded choice, not silent
truncation. Cross-target rule coverage rests on the curated edge-case suite, which runs in full
on all three targets.

### Success Criteria:

#### Automated Verification:

- Full suite green on host: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- Green on iOS sim: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:iosSimulatorArm64Test --console=plain --no-daemon`
- Green on WasmJS: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:wasmJsTest --console=plain --no-daemon`
- Formatting clean: `cd SmartChessboard && ktlint -F`

#### Manual Verification:

- Human confirms the perft node counts asserted in `PerftTest.kt` match published reference
  values (start position, Kiwipete) — a green test with a wrong expected number proves nothing.
- Confirm any per-target perft-depth reduction is documented in-comment.
- Final read of the public API surface as a downstream consumer (S-04): enumerate, attempt,
  classify — all reachable without reaching into internals.

**Implementation Note**: This is the final phase; after it passes, F-01 is complete. Pause for
manual confirmation.

---

## Testing Strategy

### Unit Tests:

- **Phase 1**: square round-trip (`squareOf` ↔ `fileOf`/`rankOf`), start-position invariants
  (piece placement, side to move, full castling rights, no en-passant target).
- **Phase 2**: per-piece pseudo-legal generation on hand-built positions; attack maps; pawn
  attack-vs-move asymmetry.
- **Phase 3**: pins, castling legality (all conditions), en passant (legal + discovers-check),
  incomplete-promotion rejection, and `applyMove` state transitions (rights, en-passant
  lifecycle, counters).
- **Phase 4**: checkmate, stalemate, check, ongoing classification.

### Integration Tests:

- **Perft** is the engine's integration test — it composes generation + apply + legality across
  full move trees and catches interaction bugs no single-rule test would.
- Reference positions: start position and Kiwipete (the latter specifically stresses castling /
  en passant / pins together).

### Manual Testing Steps:

1. Verify the asserted perft numbers against the Chess Programming Wiki reference values.
2. Read the public API as if writing S-04: confirm a from→to→(promotion) attempt yields either a
   new `Position` or a structured `IllegalReason`.
3. Confirm cross-target runs are genuinely green (not skipped) on iOS sim and WasmJS.

## Performance Considerations

None material. There is no engine search; the heaviest operation is perft in tests. A readable
8×8 mailbox is chosen over bitboards deliberately — the 500 ms NFR is met by orders of magnitude,
and clarity matters more than speed for a rules engine with a large edge-case surface. Perft is
the only heavy operation and its depths are fixed per target up front (Phase 5 §2): host runs
start perft(4) / Kiwipete perft(3); the slower iOS-sim and WasmJS targets run start perft(3) /
Kiwipete perft(2). This caps test cost as a recorded choice rather than reactively, and avoids
optimizing the representation.

## Migration Notes

None. Purely additive — a new `domain/chess/` package with no changes to existing files, no
schema, no dependency additions, no DI wiring.

## References

- Roadmap item F-01: `context/foundation/roadmap.md`
- Change identity: `context/changes/chess-rules-engine/change.md`
- PRD FR-005 / FR-007 / Guardrails: `context/foundation/prd.md`
- Square convention §1.3, PGN/FEN model §5, promotion §1.5: `docs/reference/contract-surfaces.md`
- Architecture layering & test discipline: `context/foundation/tech-stack.md`
- Existing domain pattern: [GameSummary.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameSummary.kt)
- Perft reference values: Chess Programming Wiki (Perft Results, Kiwipete)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Domain model & conventions

#### Automated

- [x] 1.1 Module compiles on all three targets: `:shared:compileKotlinWasmJs` + `compileKotlinIosSimulatorArm64` + `compileAndroidMain`
- [x] 1.2 Square round-trip + start-position invariant tests pass: `:shared:testAndroidHostTest`
- [x] 1.3 Formatting clean: `ktlint -F`

#### Manual

- [x] 1.4 Public type signatures reviewed as a consumable contract (S-04/S-06)
- [x] 1.5 a1 = 0 convention matches contract §1.3 by eye

### Phase 2: Pseudo-legal move generation & attack detection

#### Automated

- [ ] 2.1 Per-piece generation + attack tests pass: `:shared:testAndroidHostTest`
- [ ] 2.2 Formatting clean: `ktlint -F`

#### Manual

- [ ] 2.3 Pawn attack-vs-move asymmetry handled
- [ ] 2.4 Spot-checked pseudo-legal list for a tricky position matches expectation

### Phase 3: Full legality & applyMove

#### Automated

- [ ] 3.1 Legality + transition tests pass: `:shared:testAndroidHostTest`
- [ ] 3.2 Formatting clean: `ktlint -F`

#### Manual

- [ ] 3.3 Edge cases reviewed: pin, castle-through-check, en passant (+discovers-check), incomplete promotion
- [ ] 3.4 castlingRights / enPassantTarget / counters update correctly

### Phase 4: Terminal-state detection

#### Automated

- [ ] 4.1 Terminal-state tests pass: `:shared:testAndroidHostTest`
- [ ] 4.2 Formatting clean: `ktlint -F`

#### Manual

- [ ] 4.3 Tests include known checkmate, stalemate, check, ongoing — each FR-007-correct

### Phase 5: Perft corpus & edge-case suite

#### Automated

- [ ] 5.1 Full suite green on host: `:shared:testAndroidHostTest`
- [ ] 5.2 Green on iOS sim: `:shared:iosSimulatorArm64Test`
- [ ] 5.3 Green on WasmJS: `:shared:wasmJsTest`
- [ ] 5.4 Formatting clean: `ktlint -F`

#### Manual

- [ ] 5.5 Asserted perft counts match published reference values (start position, Kiwipete)
- [ ] 5.6 Any per-target perft-depth reduction documented in-comment
- [ ] 5.7 Public API surface read as a downstream consumer (enumerate / attempt / classify)
