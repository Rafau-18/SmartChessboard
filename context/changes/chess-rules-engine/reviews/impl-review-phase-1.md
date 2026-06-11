<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Chess Rules Engine (F-01)

- **Plan**: context/changes/chess-rules-engine/plan.md
- **Scope**: Phase 1 of 5 (Domain model & conventions)
- **Date**: 2026-06-11
- **Verdict**: APPROVED
- **Findings**: 0 critical · 2 warnings · 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

Automated success criteria all verified green: `:shared:testAndroidHostTest`
passes; `:shared:compileKotlinWasmJs` + `:shared:compileKotlinIosSimulatorArm64` +
`:shared:compileAndroidMain` compile; `ktlint` reports no violations on the chess
package and its tests. All five planned files match their Phase 1 contracts 1:1; no
move logic, DI, or extra files leaked in (scope respected). Manual checkboxes 1.4/1.5
have real test evidence (corner-square + no-wrap offset tests pin the a1=0 §1.3
convention).

## Findings

### F1 — `GameStatus` name collides with existing `domain.games.GameStatus`

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: domain/chess/GameStatus.kt:8 (vs domain/games/GameSummary.kt:5)
- **Detail**: The domain layer now has two public types named `GameStatus` —
  `domain.chess.GameStatus` (sealed interface: Ongoing/Check/Checkmate/Stalemate) and
  the pre-existing `domain.games.GameStatus` (enum: IN_PROGRESS/FINISHED). No compile
  conflict (different packages), but S-05 ("game end") is the slice that touches both
  the game lifecycle (`GameSummary.status`) and the chess terminal state, so a single
  file will import both and must fully-qualify or alias one. The plan explicitly chose
  this name (Phase 1 §5) — a flagged trade-off, not a deviation.
- **Fix**: Decide before S-05/S-04 depend on the name. Either keep `GameStatus` and
  accept import aliasing downstream, or rename the chess type to a collision-free name
  (e.g. `TerminalState` / `ChessStatus`) while the package has zero consumers and the
  rename is free.
  - Strength: Renaming is costless today; removes friction in the one slice bridging
    both packages.
  - Tradeoff: Diverges from the plan-sanctioned name (minor — plan is the source of
    truth and can be updated).
  - Confidence: HIGH — collision is concrete; both types are public in the domain layer.
  - Blind spot: None significant.
- **Decision**: PENDING

### F2 — `Position.board` is not defensively copied

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: domain/chess/Position.kt:20-40
- **Detail**: `Position` is documented as immutable and is meant to be the frozen
  contract S-02/S-04/S-05/S-06 depend on, but the constructor accepts
  `board: List<Piece?>` — a read-only *interface*, not an immutable list. A caller
  passing a `MutableList` (as `start()` does internally via upcast) retains a live
  handle that can mutate the Position after construction. No live bug today (only
  `start()` constructs it and drops its reference), but Phase 3's `applyMove` and any
  downstream builder will construct `Position` directly, where the hole becomes real.
- **Fix**: In the `init` block, freeze the input — keep the size `require`, and store
  `board.toList()` so the held reference can't be mutated through an external alias.
  - Strength: Hardens the immutability guarantee before any direct constructor caller
    (Phase 3) exists; one-line change.
  - Tradeoff: One extra list copy per Position construction — negligible (no search loop).
  - Confidence: HIGH — `List` interface does not guarantee immutability in Kotlin.
  - Blind spot: None significant.
- **Decision**: PENDING
