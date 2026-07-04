<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Game End and Result (S-05)

- **Plan**: context/changes/game-end-and-result/plan.md
- **Scope**: Phase 3 of 5 (PlayViewModel finalization — auto + manual)
- **Date**: 2026-06-16
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 2 observations
- **Reviewed commit**: 18df392

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Both review sub-agents (plan-drift and safety/quality/pattern) found the phase clean: all
four planned changes (#1 finished+pending state, #2 auto-finish in acceptMove, #3 manual end
intents, #4 finished-on-load) verified MATCH; offline-safe finish ordering, single-fire guard,
and pure-function winner inference all match the plan's critical-detail priors; no scope creep
against the "What We're NOT Doing" list; `presentation → domain` layering preserved; MVVM with
no MVI ceremony; tests assert result enum + exact serialized PGN token + `finishGame` flush +
journal clear. One positive EXTRA (not a finding): the `endGamePrompt != null` guard added to
`onSquareTap` correctly freezes the board while the end-game dialog is open.

Automated success criteria re-run at review time: `:shared:testAndroidHostTest`,
`:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest` all green; ktlint clean. Phase 3 has no
manual progress items (deferred to Phase 4 per the plan).

## Findings

### F1 — onResultPick guard wider than the state it models

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: PlayViewModel.kt:327
- **Detail**: onResultPick advanced to Confirming when `endGamePrompt != null` rather than
  `endGamePrompt is EndGamePrompt.Picking`. Calling onResultPick(X) while already in
  Confirming(Y) would overwrite to Confirming(X) — harmless (the UI only exposes the picker
  during Picking), but the guard was broader than the Picking→Confirming transition it models.
- **Fix**: Tighten the guard to `state.endGamePrompt is EndGamePrompt.Picking`.
  - Strength: Makes the transition strictly Picking→Confirming; no behaviour change on the happy path.
  - Tradeoff: None — one-line, doesn't break existing tests.
  - Confidence: HIGH — narrow guard change, re-verified on all three targets.
  - Blind spot: None significant.
- **Decision**: FIXED (Fix now)

### F2 — Prompt-guard no-op branches not directly tested

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: PlayViewModelTest.kt (missing case)
- **Detail**: onConfirmEndGame's `prompt !is Confirming → return` and onResultPick's prompt
  guard had no direct test. The stray onConfirmEndGame() in
  autoFinishFiresExactlyOnceAndBlocksFurtherInput hits the `result != null` guard on an
  already-finished game, not the no-prompt branch on an in-progress game.
- **Fix**: Add a test calling onResultPick + onConfirmEndGame on a fresh in-progress game with
  no prompt open, asserting finishGameCalls is empty and state is unchanged.
  - Strength: Pins both no-op guards, including F1's tightened guard.
  - Tradeoff: None — pure test addition.
  - Confidence: HIGH — green on all three targets.
  - Blind spot: None significant.
- **Decision**: FIXED (Fix now) — added `resultPickAndConfirmAreNoOpsWhenNoPromptIsOpen`

## Triage summary

- Fixed: F1 (Fix now), F2 (Fix now)
- Skipped: none
- Accepted: none

Both fixes applied to `PlayViewModel.kt` / `PlayViewModelTest.kt` and re-verified green on
Android host, iOS/Native, and web; ktlint clean.
