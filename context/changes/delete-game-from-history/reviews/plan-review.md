<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Delete Game from History Implementation Plan

- **Plan**: `context/changes/delete-game-from-history/plan.md`
- **Mode**: Deep
- **Date**: 2026-07-02
- **Verdict**: SOUND
- **Findings**: 0 critical, 1 warning, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | PASS |
| Plan Completeness | WARNING |

## Grounding

11/11 file paths verified to exist. Symbols verified via sub-agent code
inspection: `GameAutoSaver.sync`/`reconcile` ordering, `_changes` emission
sites in `SupabaseGamesRepository`, `HistoryViewModel`'s `changes` collector,
`GameJournal.clear` key removal, `GamesRepository` implementors (exactly two:
`SupabaseGamesRepository`, `FakeGamesRepository`), `GameRow` structure,
`GameSummary` type. Brief↔plan consistency confirmed. Progress↔Phase
mechanical check passed (one `## Progress` heading, all phase/checkbox
titles match, no stray checkboxes in phase bodies).

## Findings

### F1 — Contract amendment skips its own change-control mirror into prd.md

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1, change #7 (Contract amendment)
- **Detail**: `contract-surfaces.md`'s own "Change control" section requires
  mirroring any interface change into `prd.md`'s Implementation Decisions
  with a dated one-line rationale ("Sections 2-4 (backend) → prd.md"). Every
  prior edit to this file has a matching dated bullet in `prd.md` —
  including purely internal wire-level clarifications (e.g. the 2026-06-16
  snapshot bit-packing bullet, added "only to satisfy the contract's §1
  change-control mirror"). Phase 1 change #7 flips §3.4's delete bullet from
  the currently-documented queued/offline-capable design to online-only — a
  real behavior reversal — but originally only touched
  `contract-surfaces.md`. The existing 2026-07-02 "CRUD completion" bullet
  in `prd.md` documents the high-level FR-021 promotion and cites §3.4, but
  predates this reversal and didn't name "online-only, no queue" as its own
  decision.
- **Fix**: Add a one-line dated clause to `prd.md`'s Implementation
  Decisions (folded into the existing 2026-07-02 CRUD-completion bullet)
  stating the online-only delete decision, as part of Phase 1 change #7.
- **Decision**: FIXED (Fix in plan) — `plan.md` change #7 now touches both
  `contract-surfaces.md` and `prd.md`; `plan-brief.md` scope line updated to
  match.

### F2 — GameRow needs Column→Row restructuring, not a pure addition

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Plan Completeness
- **Location**: Phase 2, change #2 (Row affordance + confirmation dialog)
- **Detail**: `GameRow` (`HistoryScreen.kt:130-164`) is currently a single
  clickable `Column` with `Text`/`Spacer` children — no `Row` wrapper.
  Adding a trailing kebab `IconButton` needs restructuring into a `Row`
  (weighted text content + trailing `IconButton`), not a pure addition.
  Compose's nested-clickable hit-testing already satisfies the plan's own
  caveat ("the kebab must not trigger the row's own click") once
  restructured — no new mechanism needed. Flagged only so the implementer
  isn't surprised it's a layout change.
- **Fix**: Name the Column→Row restructuring explicitly in change #2's
  Intent.
- **Decision**: FIXED (Fix in plan) — change #2's Intent in `plan.md` now
  spells out the Column→Row restructuring and why nested-clickable dispatch
  resolves the caveat.
