<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Physical-Mode Capture Against the Emulator (S-06)

- **Plan**: context/changes/physical-capture-emulated/plan.md
- **Scope**: Phase 1 of 5 (Sequence Interpreter — pure domain)
- **Date**: 2026-06-19
- **Commit reviewed**: 3dbf1cf
- **Verdict**: APPROVED
- **Findings**: 0 critical · 1 warning · 2 observations
- **Triage**: all 3 findings FIXED (comment-only / doc changes; behaviour unchanged)

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING (F1 — one explicit instruction not literally followed; minor, documented) |
| Scope Discipline | PASS (Resolution.kt file-split is the documented adaptation; "NOT doing" boundaries respected) |
| Safety & Quality | PASS (1 observation, F3 — safe today) |
| Architecture | PASS (pure commonMain domain; no IO/boundary violation) |
| Pattern Consistency | PASS (sealed-interface + KDoc, requireNotNull style, test style all match siblings) |
| Success Criteria | PASS (automated 4/4 re-verified green; 1 observation, F2; manual 1.5/1.6 deferred to end-of-slice) |

### Automated verification (re-run during this review, commit 3dbf1cf + triage edits)

- `:shared:testAndroidHostTest` — BUILD SUCCESSFUL
- `:shared:iosSimulatorArm64Test` — BUILD SUCCESSFUL (Native target — mandated by lessons.md "JVM-pass proves nothing about iOS")
- `:shared:wasmJsTest` — BUILD SUCCESSFUL
- ktlint — clean on all changed/edited files

Manual items 1.5 (corpus coverage) and 1.6 (interpreter returns only `legalMoves`-sourced moves) are unchecked in Progress, deferred to `manual-verification.md` (end-of-slice). This review substantively confirms both: the corpus covers every move shape, both capture orders, all three castling orders, en passant (both orders), promotion push/capture, j'adoube, and the incomplete/illegal rejections; and `resolvePhysicalMove` returns only `moves.single()` drawn from `legalMoves(position)` (SequenceInterpreter.kt:42→60) — no hand-built `Move`.

## Scope reconciliation

- **In plan AND in diff**: `SequenceInterpreter.kt`, `Occupancy.kt`, `SequenceInterpreterTest.kt` — all present, implemented as intended.
- **In diff, not literally in plan**: `Resolution.kt` — documented adaptation (sealed interface split into its own file for the ktlint `standard:filename` rule; resolver stays in `SequenceInterpreter.kt`). Benign, not scope creep.
- **In plan, not in diff**: none.

## Findings

### F1 — Footprint derivations re-implemented, not reused

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Adherence
- **Location**: SequenceInterpreter.kt:133-170 (`footprintOf`) ↔ duplicates ChessRules.kt:79-96 (`applyMove`)
- **Detail**: The plan instructed reusing the capture/castle/en-passant derivations in `pgn/SanWriter.kt` and `pgn/PgnParser.kt` rather than re-deriving them. `footprintOf` re-derives castle rook-square mapping (:141-150), en-passant captured square (:154-159), and capture detection (:162) — a third copy of the geometry whose canonical source is `applyMove` (ChessRules.kt:79-96). Both reviewers traced the copies: the arithmetic is identical today, so behaviour is correct; the cost is a drift risk if `applyMove`'s geometry ever changes. Literal reuse is not possible against the current API — `SanWriter`/`PgnParser` helpers are `private` and return SAN strings / Booleans, and `applyMove` is `internal` and returns a whole `Position`, none of which is a square-set footprint. The re-derivation was already attributed in-code (Footprint KDoc :115-120, inline comments :153, :165).
- **Fix A ⭐ (chosen)**: Bind the copies + record the deviation — add SYNC cross-reference comments tying `footprintOf` ↔ `applyMove` (edit-together) and note the deviation as a Phase-5 lesson candidate.
  - Strength: Preserves correct, tested work; literal reuse genuinely blocked by visibility + return-type mismatch; makes the duplication visible and bound.
  - Tradeoff: Geometry still lives in three places; safety rests on the comment keeping them in sync.
  - Confidence: HIGH — arithmetic verified identical to `applyMove`; this repo records deviations via change.md/lessons routinely.
  - Blind spot: None significant — copies are byte-equivalent today.
- **Fix B (not chosen)**: Extract `internal fun squaresTouched(position, move): {vacated, arrived, captureDest}` in `domain/chess`, consumed by both `applyMove` and `footprintOf`. Eliminates the third copy but is a non-trivial engine refactor mid-slice (applyMove returns a Position, not a footprint) with its own regression risk.
- **Decision**: FIXED via Fix A — added SYNC comments in SequenceInterpreter.kt (Footprint KDoc) and ChessRules.kt (above the en-passant/castle geometry); recorded the deviation + Phase-5 lesson candidate in change.md.

### F2 — "Ambiguous" corpus case substituted (documented)

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: SequenceInterpreterTest.kt (`twoKnightsToTheSameSquareResolveByTheLiftedOriginNotAmbiguous`) + Resolution.kt:31-36
- **Detail**: The plan's Phase 1 test contract enumerates "a sequence matching two legal moves → Ambiguous". The corpus asserts no `Resolution.Ambiguous` anywhere; instead it documents Ambiguous as unreachable (no two distinct legal moves share a footprint under a full lift/place stream) and proves the near-ambiguous case (two knights both reaching d5) resolves uniquely by the lifted origin. Defensible — arguably better than asserting a branch only reachable by faking an impossible event stream. Already flagged in change.md as a Phase-5 lesson candidate.
- **Fix**: Add a one-line test comment marking the two-knights test as the substitute for the plan's enumerated Ambiguous case.
- **Decision**: FIXED — comment added at the two-knights test.

### F3 — Sign-bit shift invariant is unwritten

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: Occupancy.kt:21 ; SequenceInterpreter.kt:87, :103
- **Detail**: `1L shl 63` (square h8) is `Long.MIN_VALUE` — the sign bit. Safe today because every occupancy test uses `... and bit != 0L`, never `> 0`. No bug. The invariant was unwritten: a future `> 0` comparison would silently misread exactly one square (h8) and pass on the other 63.
- **Fix**: One-line invariant comment — test these masks with `and … != 0L`, never `> 0`; bit 63 is the sign bit.
- **Decision**: FIXED — invariant recorded in the `toOccupancy()` KDoc.

## Notes

- Status of `change.md` intentionally left at `implementing` (not flipped to `impl_reviewed`): this is a single-phase review of phase 1 of 5; phases 2–5 are not yet implemented.
- All triage edits are comment-only / documentation; no production logic changed, so the three-target green state from 3dbf1cf holds without a re-run.
