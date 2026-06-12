<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Chess Rules Engine (F-01)

- **Plan**: context/changes/chess-rules-engine/plan.md
- **Scope**: Full plan — Phases 1–5 of 5
- **Date**: 2026-06-11
- **Verdict**: APPROVED
- **Findings**: 0 critical, 2 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS — 14/14 planned contracts MATCH; zero drift, zero missing coverage |
| Scope Discipline | PASS — no EXTRA files; FEN helper is test-only as planned |
| Safety & Quality | WARNING — F1 (consolidated trusted-input boundary) |
| Architecture | PASS — pure domain layer; only non-default import is kotlin.math.abs |
| Pattern Consistency | WARNING — F2 (GameStatus name collision) |
| Success Criteria | PASS — host/iOS/Wasm green, ktlint clean, perft counts match CPW |

Success-criteria evidence: `:shared:testAndroidHostTest` green (re-run post-commit),
`:shared:iosSimulatorArm64Test` and `:shared:wasmJsTest` green (same session, code identical
at bb83ff1), ktlint exit 0. Perft assertions independently verified against Chess Programming
Wiki values: start 20 / 400 / 8 902 / 197 281 (opt-in 4 865 609), Kiwipete 48 / 2 039 / 97 862.
All Manual Progress rows carry SHAs and were confirmed at their phase gates.

## Findings

### F1 — Position trusted-input boundary is undefined (3 related latent gaps)

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: Position.kt:22, Attacks.kt:39, MoveGenerator.kt:145
- **Detail**: Position invariants are enforced inconsistently — partly in `Position.init`
  (board size, en-passant square validity), partly via a deep `require` (`isInCheck` throws
  when a king is missing), partly not at all: (a) `Position` stores the caller's list
  reference, so a caller holding a `MutableList` can mutate the "immutable" position;
  (b) a kingless position is constructible and makes public `legalMoves`/`validate`/`status`
  throw `IllegalArgumentException` instead of returning a structured outcome; (c) castling
  trusts `CastlingRights` without checking the rook sits on its home square — with stale
  rights (common in sloppy FENs) `applyMove` teleports the king and loses the rook. All
  CURRENT construction paths (`Position.start()` + `applyMove`, test `positionOf`) are safe —
  these are latent gaps that become real exactly when positions arrive from outside: FEN load
  (S-02/S-03), persistence, BLE (S-06). Point (c) is guardrail-adjacent: stale rights +
  `validate` = a saved illegal move.
- **Fix A ⭐ Recommended**: Record the contract now, harden in the FEN slice — lessons.md
  entry ("positions entering the engine from outside must be validated: both kings present,
  castling rights consistent with rooks, board list not retained by the caller") + KDoc
  preconditions on `legalMoves`/`validate`/`status`.
  - Strength: Zero code in F-01 (scope stays clean); the rule lands in the file read by
    /10x-plan and /10x-implement, so the FEN slice inherits it.
  - Tradeoff: The gap stays in code until the FEN slice; discipline protects, not the compiler.
  - Confidence: HIGH — today the only construction paths are internal (verified by both
    review sub-agents).
  - Blind spot: An S-04 consumer could hand-build a Position before the FEN slice, bypassing
    the lesson.
- **Fix B**: Harden now — `Position.init` validates both kings present, `castlingCandidates`
  checks the rook on its home square, board defensively copied.
  - Strength: Invariants enforced by the type, not by convention; engine robust to sloppy
    FENs from day one.
  - Tradeoff: ~3 edits beyond F-01 plan scope + perft re-run; a defensive board copy doubles
    allocations in `applyMove` (perft will feel it, though depth budgets have headroom).
  - Confidence: MED — simple changes, but scope creep on a slice that just closed cleanly.
  - Blind spot: Defensive-copy cost in perft(4) not measured.
- **Decision**: PENDING

### F2 — Name collision: two GameStatus types in sibling domain packages

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Pattern Consistency
- **Location**: domain/chess/GameStatus.kt:8 vs domain/games/GameSummary.kt:5
- **Detail**: `domain/chess.GameStatus` (Ongoing/Check/Checkmate/Stalemate) vs
  `domain/games.GameStatus` (IN_PROGRESS/FINISHED) — two semantically different types with
  the same simple name. The live-game screen (S-04/S-05) will plausibly need both in one
  file → import aliases and wrong-import bugs (especially from agents). The repo maintains a
  load-bearing-names registry (docs/reference/contract-surfaces.md), which makes an avoidable
  collision more notable. Cheapest to resolve before either name calcifies.
- **Fix A ⭐ Recommended**: Rename the engine's fresh type, e.g. `chess.GameStatus` →
  `chess.PositionStatus`.
  - Strength: Zero consumers outside the engine and its tests (verified) — mechanical rename;
    `games.GameStatus` is already wired into the S-01 data layer.
  - Tradeoff: Deviates from the name recorded in the F-01 plan (FR-007 terminology — the
    Checkmate/Stalemate variant names stay; only the container renames).
  - Confidence: HIGH — grep confirms usages only in domain/chess + tests.
  - Blind spot: None significant.
- **Fix B**: Rename `games.GameStatus` → `GameLifecycle`.
  - Strength: Engine keeps the planned name; "lifecycle" better fits IN_PROGRESS/FINISHED.
  - Tradeoff: Touches the closed S-01 slice (data layer, Supabase mapping) — wider blast
    radius.
  - Confidence: MED — not all usages in data/ audited.
  - Blind spot: Possible string-level status mappings in Supabase.
- **Decision**: PENDING

### F3 — validate applies the winning move twice; status materializes the full move list

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; no action for this slice
- **Dimension**: Safety & Quality (performance)
- **Location**: ChessRules.kt:29, ChessRules.kt:56
- **Detail**: `validate` materializes the full legal set (each candidate = a 64-entry board
  copy + check scan) then re-applies the matched move; `status` only needs "any legal move
  exists" but builds the whole list. Microseconds for an interactive board (500 ms NFR met by
  orders of magnitude); perft cost already budgeted per target. Recorded so future reviews
  don't re-derive it.
- **Fix**: None — deliberate plan choice ("readable over fast").
- **Decision**: PENDING

### F4 — Near-tautological test: PROMOTION_TARGETS compared to its own definition

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency (test quality)
- **Location**: PositionTest.kt:86
- **Detail**: The test restates the constant's definition from Pieces.kt:15 literally — it
  pins the contract against accidental edits but verifies no behavior. Behavioral coverage
  exists in MoveGeneratorTest (promotion expansion) and ChessRulesTest
  (promotionOffersExactlyTheFourTargetPieces).
- **Fix**: Optionally drop the test; keeping it as a contract pin is also fine.
- **Decision**: PENDING

## Sub-agent evidence summary

- **Drift agent**: 14/14 contracts MATCH across all five phases; public API surface exactly
  as planned (extras limited to `PROMOTION_TARGETS`, `SQUARE_COUNT`, square helpers — all
  planned-adjacent); no planned test coverage absent; perft reference data correct vs CPW.
  Two zero-impact notes: redundant `|| isEnPassant` term in ChessRules.kt:123 (en passant
  implies pawn); PerftTest's fen round-trip uses internal `applyMove` (consistent with plan
  intent — Perft.kt is test plumbing).
- **Safety agent**: no CRITICAL findings; layer purity clean (no framework/platform imports);
  public-vs-internal tiering deliberate (free functions justified vs repository-port style of
  domain/games and domain/auth); test quality clean (no assertion-free tests, set-based
  assertions where order could bite, per-target perft depths match their expect KDoc).
