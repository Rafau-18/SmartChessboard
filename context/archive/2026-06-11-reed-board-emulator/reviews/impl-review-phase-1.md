<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Reed-Switch Board Emulator (F-02)

- **Plan**: context/changes/reed-board-emulator/plan.md
- **Scope**: Phase 1 of 4
- **Date**: 2026-06-16
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Automated success criteria re-run at review time: `:shared:compileKotlinWasmJs` +
`compileKotlinIosSimulatorArm64` + `compileAndroidMain` → BUILD SUCCESSFUL; `ktlint` on
`domain/board/*.kt` → no violations. Manual items 1.3/1.4 confirmed by the user with observable
1:1 §1.3/§1.4 mapping in the diff.

## Findings

### F1 — isOccupied() documents a 0..63 precondition it doesn't enforce

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/BoardEvents.kt:36
- **Detail**: `isOccupied(square)`'s KDoc says "(0..63)" but there is no guard. `ushr` masks the
  shift amount to its low 6 bits, so `square == 64` silently behaves like `0` — a
  wrong-but-non-crashing result. Sibling `Square.kt` (lines 16-18) consistently uses
  `require(... in 0..7)` for its index helpers. The bit math itself is correct for all valid
  squares 0..63 incl. 63 (`ushr` zero-fills the sign bit cleanly, Kotlin/Native-safe). Tension:
  root CLAUDE.md discourages validation for scenarios that can't happen on trusted internal
  callers; weighed against the local `Square.kt` `require` precedent.
- **Fix**: Add `require(isValidSquare(square))` (reusing Square.kt) at the top of `isOccupied`,
  matching the Square.kt index-helper convention.
- **Decision**: FIXED — added `require(isValidSquare(square))` to `isOccupied`; recompiled green on all three targets, ktlint clean. Folded into the phase-1 commit via amend (8c16c1a).
