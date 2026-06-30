<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Real Board over BLE (S-09) — Phases 6 & 7

- **Plan**: context/changes/real-board-over-ble/plan.md
- **Scope**: Phases 6 & 7 of 8 (reviewed out of order — pure `commonMain` logic + UI, no collision with the not-yet-built phases)
- **Branch / worktree**: `impl/s09-phase6-reconnect-reconcile` @ `.claude/worktrees/funny-einstein-604879`
- **Commits**: `0e604ca` (p6 reconnect-reconcile flag), `c7d6132` (p7 live matrix overlay)
- **Date**: 2026-06-30
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS (automated green; manual deferred to the Phase 8 hardware gate by design) |

## What was verified

### Phase 6 — `reconnectReconciling` gate (FR-012), commit `0e604ca`

- New `val reconnectReconciling: Boolean = false` on `Playing` (`PhysicalPlayContract.kt`), added at **every** seam its sibling flags use: `acceptanceBlocked`, the `accumulate()` guard, the `confirm()` gate, the `commit()` reset, and the shared `SnapshotReceived` at-rest-match clear (computed parallel to `resumeVerified`). Nothing missing relative to `recovering` / `awaitingResumeConfirm`.
- Armed in the `BoardConnected` arm alongside the existing `Send(RequestSnapshot)`; kept a distinct field so the UI can label "reconnecting" vs "resuming" vs "rejected".
- No stuck-set path: cleared on a matching snapshot and on `commit`; deliberately held across a disconnect, re-armed on the next connect.

### Phase 7 — live sensed-occupancy overlay, commit `c7d6132`

- New display-only `sensedOccupancy: Long?` — reset to the snapshot occupancy on `SnapshotReceived`, bit-cleared on `SquareLifted`, bit-set on `SquarePlaced` via the h8-safe `sensedAfter()` helper (`1L shl square`; square 63 = sign bit).
- Display-only confirmed: read only by the reducer fold and `PhysicalPlayScreen` overlay; never in `acceptanceBlocked` / `confirm` / `commit` / `accumulate` (grep-pinned + a dedicated "never blocks acceptance" test). Stays separate from `latestOccupancy`.
- New optional `occupancyDots: Long? = null` param on `ChessBoardView`; the `null` default keeps Replay/Play/web call sites unchanged. Reuses the shared `isOccupied` (from `ReedDiagnosticsGrid`) for the bit read and the shared `squareAt` placement; plain neutral dot with no mismatch tint (scope guardrail respected).
- "Sensor dots" toggle defaults on (`rememberSaveable`), display-only — flipping it never touches game state.

### Tests / gates

- `testAndroidHostTest`, `iosSimulatorArm64Test`, `wasmJsTest` — all `BUILD SUCCESSFUL`; `ktlint` clean on changed files.
- New `PhysicalReconnectEndToEndTest` obeys the `lessons.md` connect-burst rule: injects per-scenario occupancy (`disconnect → setOccupancy → connect`) and settles the reconnect burst with `runCurrent()`, never `advanceUntilIdle()` while the ~10 Hz diagnostic stream is armed.
- Manual success criteria 6.5 / 7.5 are intentionally deferred to the Phase 8 on-hardware gate (not rubber-stamped — they remain `[ ]`).

## Findings

### F1 — Existing recover E2E was modified, not just extended

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalRecoverEndToEndTest.kt
- **Detail**: Phase 6 re-routed the INCONSISTENT-category E2E from a disconnect→reconnect injection onto a continuously-connected path, because the new reconnect gate now pre-empts the old injection. The reasoning is documented in-test and the pure INCONSISTENT fork stays pinned in `PhysicalPlayReducerTest`, so coverage is preserved, not weakened. Flagged only so the modification (vs. pure addition) is on the record.
- **Fix**: None required — confirm the in-test rationale reads clearly; coverage already preserved in the reducer suite.
- **Decision**: PENDING

### F2 — Occupancy-dot colour is a placeholder for the hardware gate

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — informational; tied to Phase 8
- **Dimension**: Pattern Consistency
- **Location**: SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/theme/ChessColors.kt:41
- **Detail**: `OccupancyDot = Color(0xCC1C2530)` is explicitly commented as "tuned on real reeds in the Phase 8 hardware gate." Intentional, tracked deferral — re-check legibility on both wood hues (cream + brown) during the on-hardware pass.
- **Fix**: None now — verify/tune the hue during the Phase 8 acceptance run.
- **Decision**: PENDING

### F3 — "Sensor dots" toggle state is screen-local

- **Severity**: 🔍 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; matches the plan as written
- **Dimension**: Scope Discipline
- **Location**: SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayScreen.kt:180
- **Detail**: `rememberSaveable { mutableStateOf(true) }` survives config change / process death but resets when the user navigates away and back. The plan only asked for "a toggle defaulting to on", so this satisfies it. Decide later whether the preference should persist cross-session (via `multiplatform-settings`, like the remembered board id) — out of scope for now.
- **Fix**: None required — persist via `multiplatform-settings` only if cross-session memory is desired post-MVP.
- **Decision**: PENDING
