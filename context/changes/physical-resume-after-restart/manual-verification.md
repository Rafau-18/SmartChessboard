# Manual verification — physical-resume-after-restart (S-08 / FR-013)

Deferred manual checks collected for an end-of-slice pass, per the team's
"defer code-read manual items" rule. Automated criteria are flipped in
`plan.md` `## Progress`; the rows below stay `- [ ]` there until confirmed.

## Phase 1 — Resume gating core

### 1.4 Code-read (deferred to end-of-slice)

- [ ] `acceptanceBlocked` truly blocks acceptance on resume until a matching
  snapshot clears `awaitingResumeConfirm`, and the resume-match path leaves the
  board in GAME mode via the existing `effectsForModeChange` edge —
  `SetMode(GAME)` emitted once when diagnostics had been open (restore path),
  and **not at all** on a clean match where the board never left GAME mode.

**Code-read evidence gathered during implementation** (to confirm at the pass):

- `acceptanceBlocked = paused || recovering || awaitingResumeConfirm`
  (`PhysicalPlayContract.kt`). On load of an in-progress physical game the
  `Loaded` arm sets `awaitingResumeConfirm = true`, so acceptance is blocked
  even before the board connects (also `paused` during the pre-connect window).
- `confirm()` (`PhysicalPlayReducer.kt`) now returns a no-op + `RequestSnapshot`
  while `recovering || awaitingResumeConfirm`, so a confirm cannot advance the
  game until the board is verified; it re-pulls a snapshot so the at-rest
  board-match can clear the gate without the grid.
- `SnapshotReceived` clears the gate via the shared at-rest board-match
  (`resumeVerified = awaitingResumeConfirm && matchesExpected`). No explicit
  `SetMode` is emitted; mode tracking is left to `effectsForModeChange` on the
  `diagnosticsVisible` edge:
  - **clean match** (board never mismatched) → `setupMismatch` stays `false`,
    no diagnostics edge → **zero** `SetMode` (board already in GAME).
  - **restore after mismatch** → `setupMismatch` goes `true→false`, the
    shown→hidden edge emits exactly **one** `SetMode(GAME)`.
- Reducer unit tests assert both shapes
  (`aMatchingSnapshotOnResumeClearsTheGateWithNoDiagnosticsAndNoSetMode`,
  `restoringTheBoardAfterAResumeMismatchClearsTheGateWithExactlyOneSetModeGame`).

## Phase 2 — device/on-device manual checks (added when Phase 2 runs)

<!-- 2.4 / 2.5 / 2.6 from plan.md will be recorded here at Phase 2 implementation. -->
