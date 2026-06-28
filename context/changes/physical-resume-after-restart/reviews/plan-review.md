<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Physical Resume After Restart (S-08 / FR-013)

- **Plan**: `context/changes/physical-resume-after-restart/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-21
- **Verdict**: REVISE → SOUND (all findings fixed in triage)
- **Findings**: 0 critical · 3 warnings · 2 observations (all FIXED)

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | WARNING (F4) |
| Architectural Fitness | WARNING (F1) |
| Blind Spots | WARNING (F2) |
| Plan Completeness | WARNING (F3, F5) |

## Grounding

7/7 modified paths ✓ · symbols ✓ · brief↔plan ✓. Note: `ReedDiagnosticsGrid.kt` lives in
`presentation/board/`, not `presentation/physical/` as the plan & research cite — reuse-only
(not modified), so harmless, but the `file:line` reference is wrong.

## Findings

### F1 — Explicit SetMode(GAME) on resume-match contradicts effectsForModeChange

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architectural Fitness
- **Location**: Approach; Phase 1 §2 Contract; Success Criteria 1.4 / Progress 1.4
- **Detail**: The reducer already emits SetMode on the `diagnosticsVisible` edge via
  `effectsForModeChange` (`PhysicalPlayReducer.kt:387`); `restoreVerified` relies on that edge and
  emits no explicit SetMode. The plan's explicit "emit SetMode(GAME) on match" double-fires on the
  restore path (2×) and fires spuriously on a clean match (board already in GAME), making criterion
  1.4 ("exactly once") unsatisfiable across both paths (clean match wants 0, restore wants 1).
- **Fix**: Mirror `restoreVerified` — clear `awaitingResumeConfirm`, let `effectsForModeChange` drive
  the mode via the diagnostics edge, drop the explicit SetMode; reword criterion 1.4 to "GAME mode via
  the edge: one SetMode(GAME) on the restore path, none on a clean match".
- **Decision**: FIXED (Fix in plan)

### F2 — Load-time gate changes existing load-then-act tests; not audited

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 1 §3; "reject-recovery behaviour is unchanged"
- **Detail**: Making `Loaded` set `awaitingResumeConfirm=true` + request a snapshot changes load
  behaviour for every test that loads then acts. `PhysicalRecoverEndToEndTest` assumes "no stream yet"
  post-load (`:168`) and hand-drives occupancy; `PhysicalCaptureEndToEndTest` and
  `PhysicalPlayViewModelTest` also load (`pgn=""`) then act. The plan only said "extend the reducer
  suite" and asserted recovery is unchanged — it never listed auditing these three files.
- **Fix**: Added Phase-1 §4 "Regression: existing physical tests under the new load-time gate" — audit
  the three files so a matching start-position snapshot clears the gate before the first action, and
  account for the new post-load `RequestSnapshot` in their `advanceUntilIdle()` windows (verified under
  criteria 1.1 / 2.1, no separate Progress item).
- **Decision**: FIXED (Fix in plan)

### F3 — "Evaluate against already-observed occupancy" is dead on first load

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness
- **Location**: Critical Implementation Details; Phase 1 §2 Contract (`Loaded` arm)
- **Detail**: `reduceLoading` drops every non-`Loaded`/`LoadFailed` message (`:64-68`), and `Loaded`
  builds a fresh `Playing` with `latestOccupancy=null` — so no occupancy is ever stored at the `Loaded`
  transition. The plan's branch (b) "evaluate against any already-observed occupancy" can't fire from
  the load path; only "request a snapshot when connected" is viable. Branch (b) is real only for the
  FR-012/`BoardConnected` reuse.
- **Fix**: Simplified the `Loaded` contract to set-flag + `Send(RequestSnapshot)` when `msg.connected`
  (recovers the dropped `BoardConnected`); relocated the observed-occupancy branch to the FR-012 reuse
  note; the match runs only in `SnapshotReceived`.
- **Decision**: FIXED (Fix in plan)

### F4 — The "shared transition" already exists as the SnapshotReceived arm

- **Severity**: 🔎 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Lean Execution
- **Location**: Overview; Key Discoveries; Phase 1 §2
- **Detail**: Resume (`Loaded`→`RequestSnapshot`) and reconnect (`BoardConnected`→`RequestSnapshot`)
  both already funnel into `SnapshotReceived`, which already computes `matchesExpected`. S-08 just adds
  a `resumeVerified` clause there — nothing to factor out.
- **Fix**: Reworded Overview + Key Discoveries to "extend the existing `SnapshotReceived` arm" (the
  shared seam both triggers already funnel through), not "factor into a new shared transition".
- **Decision**: FIXED (Fix in plan)

### F5 — Finished-game predicate unpinned; `paused` mischaracterized

- **Severity**: 🔎 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Critical Implementation Details ("Gate applies only to in-progress physical")
- **Detail**: (1) The finished-game guard didn't name a reducer signal; a manually-finished game
  (FR-018) is `result != null` without a terminal `GameStatus`, so a terminal-only test misses it.
  (2) The plan called `paused` "in-memory and reset on kill"; it's a computed getter
  `paused = connectionState == DISCONNECTED` (`PhysicalPlayContract.kt:89`).
- **Fix**: Pinned the in-progress predicate `result == null && !terminal`; corrected the `paused`
  description to "computed from connectionState — pre-connect window already gated".
- **Decision**: FIXED (Fix in plan)
