# Physical Resume After Restart (S-08 / FR-013) Implementation Plan

## Overview

Let a player restart the app mid physical-mode game on the same device and continue with no accepted move lost. The expected position already rebuilds from the durable PGN on screen entry; what is missing is the **board-confirmation gate**: on resume we must block move acceptance until the physical board is verified against the expected position — auto-resuming when the board matches, and routing to the existing diagnostics→restore loop when it does not.

This is a wiring + gating slice. Every primitive it needs (durable journal, expected-position rebuild, `Position.toOccupancy()` board-match compare, the per-square diagnostics grid, the `acceptanceBlocked` gate, the restore-on-match transition) already ships and is test-proven by S-06 and S-07. The slice introduces one new state flag (`awaitingResumeConfirm`), wires it into the load path, and extends the existing `SnapshotReceived` board-confirm arm — the shared seam both resume and reconnect already funnel through — so FR-012/S-09 can fire the identical reconcile on BLE reconnect.

## Current State Analysis

The physical screen is a headless MVI core: a pure `reduce(state, msg)` over `Loading | Error | Playing`, with IO confined to effects interpreted by `PhysicalPlayViewModel`. Today, opening an in-progress physical game already works end to end except for the gate:

- **Durability is already guaranteed.** Every accepted move is journaled synchronously (`commit = true`) before the UI advances ([`GameAutoSaver.kt:32-38`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt:32); enforced in the physical path at [`PhysicalPlayViewModel.kt:158-177`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayViewModel.kt:158)). The last accepted move = the last journaled PGN = the resume point. **S-08 cannot lose a move regardless of what it does.**
- **The expected position rebuilds for free.** `load()` does `getGame → reconcile → parsePgn → Loaded(positions=…)` ([`PhysicalPlayViewModel.kt:122-149`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayViewModel.kt:122)); `Playing.position = positions.last()`. The S-04 force-quit E2E proved this PGN-rebuild path survives a kill on all three targets.
- **The board-match primitive is h8-safe and shipped.** `Position.toOccupancy(): Long` ([`Occupancy.kt:21`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/Occupancy.kt:21)); the compare `snapshot.occupancy == state.position.toOccupancy()` drives `setupMismatch`/`restoreVerified` ([`PhysicalPlayReducer.kt:106`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:106)); the per-square diff renders in `ReedDiagnosticsGrid` ([`ReedDiagnosticsGrid.kt:42`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/ReedDiagnosticsGrid.kt:42)).
- **The blocking gate and the re-enable transition exist** — but only for reject-recovery. `acceptanceBlocked = paused || recovering` ([`PhysicalPlayContract.kt:92`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayContract.kt:92)); the **only** thing that clears `recovering` is an exact occupancy match in `SnapshotReceived` (`restoreVerified = recovering && matchesExpected`, [`PhysicalPlayReducer.kt:101-121`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:101)).
- **On (re)connect the board is re-snapshotted automatically.** The `BoardConnected` arm emits `Send(RequestSnapshot)` and re-arms `SetMode(DIAGNOSTIC)` if the grid is open ([`PhysicalPlayReducer.kt:82-95`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:82)).

**The gap (confirmed in live code):** the `Loaded` arm ([`PhysicalPlayReducer.kt:40-58`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:40)) renders `positions.last()`, sets `setupMismatch = false`, and **immediately allows play** — it never blocks acceptance pending board confirmation. A resume/connect mismatch sets only `setupMismatch` (which auto-opens the grid via `diagnosticsVisible`) but does **not** set `recovering`, so acceptance stays open. FR-013's "confirm the board matches before re-enabling acceptance" is therefore enforced today only after a *rejected move*, never on *resume*.

## Desired End State

After this plan, cold-starting an in-progress physical game and tapping it from History (the resume offer) lands on `PhysicalPlay` with the expected position rendered and **move acceptance blocked** until the board is confirmed:

- **Board matches** → the gate clears automatically, no extra tap, normal play resumes (auto-resume-on-match).
- **Board mismatches** → the diagnostics grid is open, acceptance stays blocked, and the moment the player physically restores the board to the expected position the gate clears and play resumes — reusing the exact S-07 restore loop.
- **No accepted move is lost** across the restart (already guaranteed; verified by the resume E2E).

Verification: a fault-injected resume E2E (matching → auto-resume; mismatched → diagnostics → restore → resume; promotion-pending-at-kill edge) green on Android host and iOS simulator, plus a manual on-device pass.

### Key Discoveries:

- The resume confirmation is a **transition, not a screen**: `awaitingResumeConfirm` ORed into `acceptanceBlocked`, cleared by the same board-match check that clears `recovering` ([`PhysicalPlayReducer.kt:101-121`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:101)).
- The board adapter re-establishes itself on cold start for free (Koin re-bind → VM resubscribe → `BoardConnected` auto-`RequestSnapshot`, [`PhysicalPlayViewModel.kt:60-79`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayViewModel.kt:60)). There is **no special re-init path to build** — just the gate.
- The emulator reports **start-position** occupancy on a fresh connect ([`EmulatedBoard.kt:85-93`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoard.kt:85)), so a mid-game resume on the emulator's default *always* mismatches — the E2E must **inject** the occupancy it wants (match or mismatch), per the `PhysicalRecoverEndToEndTest` fault-injection pattern.
- FR-012 (BLE reconnect-reconcile) is the **same** reconcile, specified word-for-word identically in `contract-surfaces.md` §1.7 and §6.3. The shared seam already exists: both resume (`Loaded`→`RequestSnapshot`) and reconnect (`BoardConnected`→`RequestSnapshot`) funnel into the `SnapshotReceived` arm, which already computes `matchesExpected`. S-08 extends that one arm with a `resumeVerified` clause; S-09/FR-012 reuses it by adding its own gate flag set in `BoardConnected`. Nothing new to factor out — and the match never runs inline in the load path.

## What We're NOT Doing

- **No offline cold-start discovery** (G2 minimal). Resume detection/open stays cloud-backed (`listMyGames` / `getGame`); offline-at-launch degrades to the existing `LoadFailed`/empty state. No local index of in-progress games, no journal-only open, no new data-layer surface. The "no accepted move lost" invariant holds regardless of connectivity.
- **No cross-device handoff.** FR-013 is same-device only (PRD OQ-4). No work toward resuming an active physical game on a second device.
- **No BLE `BoardConnected` wiring / no auto-reconnect transport.** S-08 builds the reusable confirm transition only; firing it on reconnect and the BLE central auto-reconnect adapter are S-09/FR-012.
- **No auto-navigation or startup prompt.** The resume offer is the existing History tap (plus a light "Resume" affordance), not a forced jump or a startup dialog.
- **No web work.** Physical mode is mobile-only (FR-020 / lessons.md); nothing lands in `wasmJsMain`.
- **No new persistence schema.** The journal keeps storing only PGN/`dirty`/`result`; no new keys, no migration.

## Implementation Approach

Introduce a single new boolean on `Playing` — `awaitingResumeConfirm` — ORed into `acceptanceBlocked`. Set it when the `Loaded` arm renders an **in-progress physical** game (the resume entry). Extend the existing `SnapshotReceived` arm (the board-match check `incomingOccupancy == position.toOccupancy()` already lives there) so that both reject-recovery (`recovering`) and resume (`awaitingResumeConfirm`) clear through it. On a resume match, just clear `awaitingResumeConfirm` and let the existing `effectsForModeChange` diagnostics-visibility edge emit `SetMode(GAME)` — exactly as `restoreVerified` already does. Do **not** emit an explicit `SetMode(GAME)`: a clean auto-resume-on-match leaves the board in GAME mode untouched (zero `SetMode`), and a restore-after-mismatch emits one `SetMode(GAME)` on the shown→hidden edge — adding an explicit send double-fires the restore path. On mismatch the resume path opens diagnostics and stays blocked, re-checking each fresh occupancy until it matches — structurally identical to the S-07 restore loop, so the player restores the board the same way after a restart as after a rejected move. The History screen gets a light "Resume" affordance on in-progress physical rows; everything else (durability, expected-position rebuild, diagnostics UI, board re-snapshot) is reused unchanged.

## Critical Implementation Details

- **Snapshot-vs-`Loaded` ordering (state sequencing).** The VM subscribes to `events`/`connectionState` *before* `load()` ([`PhysicalPlayViewModel.kt:60-79`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayViewModel.kt:60)), and `reduceLoading` **drops every message except `Loaded`/`LoadFailed`** ([`PhysicalPlayReducer.kt:64-68`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:64): "events that arrive before the game has loaded have nowhere to land"). So a `BoardConnected`/`SnapshotReceived` that fires during `Loading` is discarded, and `Loaded` always builds a fresh `Playing` with `latestOccupancy = null` — **there is never an already-observed occupancy at the `Loaded` transition**. The `Loaded` arm therefore (a) sets `awaitingResumeConfirm`, and (b) when `msg.connected`, emits `Send(RequestSnapshot)` — this re-requests the snapshot whose `BoardConnected` trigger was dropped during `Loading`; when not connected, the existing `BoardConnected` arm's request covers it. The match never runs inline in `Loaded` — only in `SnapshotReceived`. (Evaluating against an already-observed `latestOccupancy` is meaningful only on the FR-012/`BoardConnected` reuse path, where the state is already `Playing` and a prior snapshot may be on file.)
- **Emulator default masks the mismatch case (testing/observability).** A fresh `EmulatedBoard` reports start-position occupancy, so a mid-game resume against the emulator's default always mismatches. The resume E2E must inject the occupancy under test (match → expected occupancy of `positions.last()`; mismatch → any other) rather than rely on the emulator default — reuse the `PhysicalRecoverEndToEndTest` injection harness.
- **Gate applies only to in-progress physical.** Set `awaitingResumeConfirm` only when the loaded game is in progress — predicate `msg.result == null && !terminal` (a manually-finished game per FR-018 is `result != null` *without* a terminal `GameStatus`, so a `terminal`-only test would miss it and wrongly gate a finished game). In practice History routes only `IN_PROGRESS` physical rows to `PhysicalPlay`, so this is defensive — but pin the predicate. Note `paused` is the computed getter `paused = connectionState == BoardConnectionState.DISCONNECTED` ([`PhysicalPlayContract.kt:89`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayContract.kt:89)) — not an in-memory flag — so a cold resume is already gated by `paused` during the pre-connect window, and the resume confirm clears only once the board is connected and a matching snapshot arrives.

## Phase 1: Resume gating core

### Overview

Add the `awaitingResumeConfirm` flag and the shared board-confirm transition entirely within the headless MVI core (contract + reducer), proven by reducer unit tests. No UI, no E2E, no IO changes — this is the pure-logic heart of the slice and the FR-012-reusable foundation.

### Changes Required:

#### 1. Acceptance gate + resume flag

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayContract.kt`

**Intent**: Add a distinct resume-confirmation flag to `Playing` and fold it into the acceptance gate so a resume blocks move acceptance separately from reject-recovery — keeping the UI able to distinguish "confirming board on resume" from "your move was rejected" (G3).

**Contract**: New field `awaitingResumeConfirm: Boolean = false` on `Playing`; the gate becomes `acceptanceBlocked get() = paused || recovering || awaitingResumeConfirm`. `diagnosticsVisible` is unchanged (mismatch on resume still flows through `setupMismatch`/`manualDiagnostics`).

#### 2. Resume entry in the `Loaded` arm + shared board-confirm transition

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt`

**Intent**: On loading an in-progress physical game, enter the resume gate instead of allowing play immediately; verify the board via a single shared transition that both the resume path and the existing reject-recovery path clear through, so FR-012/S-09 can fire the same transition from `BoardConnected`.

**Contract**:
- `Loaded` arm ([`:40-58`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:40)): for an in-progress physical game set `awaitingResumeConfirm = true`; when `msg.connected`, emit `Send(RequestSnapshot)` (pre-load snapshots are dropped by `reduceLoading`, so this recovers the `BoardConnected` consumed during `Loading`); when not connected, the existing `BoardConnected` arm's request covers it. Do **not** compare occupancy inline and do **not** leave acceptance open — the match runs only in `SnapshotReceived`.
- Extend the existing `SnapshotReceived` arm (which already computes `matchesExpected` and `restoreVerified`) with a `resumeVerified = awaitingResumeConfirm && matchesExpected` clause. On **match**: clear `awaitingResumeConfirm`; `setupMismatch` falls to `false` from the existing at-rest compare, which closes diagnostics, and the existing `effectsForModeChange(state, next)` shown→hidden edge emits the single `SetMode(GAME)` — **do not** add an explicit `SetMode(GAME)` (it would double-fire the restore path and fire spuriously on a clean match where the board is already in GAME). For recovery, the existing `restoreVerified` behaviour is preserved unchanged. On **mismatch** while `awaitingResumeConfirm`: keep the flag set; the existing `setupMismatch = !matchesExpected` compare opens diagnostics and `effectsForModeChange` re-arms `SetMode(DIAGNOSTIC)`; stay blocked; each subsequent occupancy re-runs the check until it matches.
- The existing `BoardConnected` arm ([`:82-95`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:82)) is unchanged for S-08; leave a note that S-09/FR-012 routes reconnect through the shared transition here.

#### 3. Reducer unit tests

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/physical/` (extend the existing reducer test suite)

**Intent**: Lock the new transitions as pure-logic units before any E2E or device work.

**Contract**: Tests asserting — resume of an in-progress physical game sets `awaitingResumeConfirm` and blocks acceptance; a matching snapshot clears it and re-enables acceptance with **no diagnostics and no `SetMode` emission** (the board is already in GAME mode); a mismatching snapshot keeps it blocked with diagnostics open (`SetMode(DIAGNOSTIC)`); a later matching snapshot (post-restore) clears it and emits exactly one `SetMode(GAME)` on the diagnostics shown→hidden edge; a finished game does **not** enter the gate; reject-recovery (`recovering`) behaviour is unchanged.

#### 4. Regression: existing physical tests under the new load-time gate

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/physical/` — `PhysicalRecoverEndToEndTest.kt`, `PhysicalCaptureEndToEndTest.kt`, `PhysicalPlayViewModelTest.kt`

**Intent**: The `Loaded` arm now blocks acceptance and (when connected) requests a snapshot — a behaviour change that ripples into every existing test that loads an in-progress physical game and then acts. Audit and adjust them so the gate is satisfied before their first action, rather than discovering the regression at a red `:shared:testAndroidHostTest` bar.

**Contract**: For each test that drives a `Loaded` and then acts — `PhysicalRecoverEndToEndTest` (assumes "no stream yet" post-load and hand-drives occupancy), `PhysicalCaptureEndToEndTest`, `PhysicalPlayViewModelTest` — ensure a matching start-position snapshot is delivered/expected after `Loaded` so `awaitingResumeConfirm` clears before the first `ConfirmPressed`, and account for the new post-load `Send(RequestSnapshot)` landing inside their `advanceUntilIdle()` windows. Fixtures start at `pgn = ""` (start position), so the emulator's start-occupancy snapshot clears the gate once it is actually delivered. These suites run under criteria 1.1 / 2.1 (no separate Progress item).

### Success Criteria:

#### Automated Verification:

- [ ] Reducer unit tests pass on Android host: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- [ ] Shared module compiles for all targets (no physical-only code leaks to web): `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:compileKotlinWasmJs :shared:compileKotlinIosSimulatorArm64 --console=plain --no-daemon`
- [ ] ktlint formatting clean: `cd SmartChessboard && ktlint -F`

#### Manual Verification:

- [ ] Code-read: `acceptanceBlocked` truly blocks acceptance on resume until a matching snapshot clears `awaitingResumeConfirm`, and the resume-match path leaves the board in GAME mode via the existing `effectsForModeChange` edge — `SetMode(GAME)` emitted once when diagnostics had been open (restore path), and **not at all** on a clean match where the board never left GAME mode.

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before Phase 2. Code-read manual items may be recorded in `manual-verification.md` and the phase committed, per the team's defer-code-read rule.

---

## Phase 2: Resume E2E, edge cases & write-backs

### Overview

Prove the gate end to end with a fault-injected resume E2E on the mobile targets, add the light History "Resume" affordance, and write the implemented gate back to the foundation docs.

### Changes Required:

#### 1. Resume end-to-end test

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalResumeEndToEndTest.kt` (new; model on `PhysicalRecoverEndToEndTest.kt`)

**Intent**: Exercise the full resume path against the emulator with injected occupancy, covering the happy path, the restore path, and the promotion-pending-at-kill edge.

**Contract**: Scenarios —
- **Match → auto-resume**: in-progress physical game (journaled PGN), fresh VM (simulated restart), inject occupancy == expected → gate clears with no extra input, `SetMode(GAME)`, next move accepted, journal/PGN intact (no move lost).
- **Mismatch → restore → resume**: inject mismatching occupancy → acceptance blocked + diagnostics open → inject the expected occupancy (board restored) → gate clears, play resumes.
- **Promotion-pending at kill**: a position where a pawn was lifted / on the last rank at kill → post-resume occupancy mismatches → routes to diagnostics → restore → resume (the move was never accepted, so nothing is lost).

#### 2. History "Resume" affordance

**File**: the History screen composable (`SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/history/HistoryScreen.kt`) — routing already in [`App.kt:81-97`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt:81)

**Intent**: Make the existing resume offer legible — an in-progress physical game already routes to `PhysicalPlay` on tap; surface it as resumable.

**Contract**: In-progress physical rows show a light "Resume" affordance/label; tap behaviour is unchanged (routes to `PhysicalPlayKey(id)`). No new navigation mechanism.

#### 3. Foundation write-backs

**File**: `docs/reference/contract-surfaces.md`, `context/foundation/roadmap.md`, `context/foundation/lessons.md`

**Intent**: Record the implemented resume gate as a load-bearing surface and capture any new pitfall.

**Contract**: `contract-surfaces.md` §6.3 notes the implemented resume reconcile — the `awaitingResumeConfirm` gate and the shared board-confirm transition reused by FR-012; `roadmap.md` S-08 status moved off `proposed`; `lessons.md` gets a new entry **only if** a genuine pitfall surfaced (candidate: "emulator resets to start-position occupancy on cold start → resume E2E must fault-inject occupancy, not rely on the emulator default").

### Success Criteria:

#### Automated Verification:

- [ ] Resume E2E passes on Android host: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- [ ] Resume E2E passes on iOS simulator: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:iosSimulatorArm64Test --console=plain --no-daemon`
- [ ] Full shared build green across targets + ktlint clean

#### Manual Verification:

- [ ] On a device/emulator: restart mid physical game, tap the in-progress game in History → expected position renders, board matches → auto-resume, play the next move, confirm no move was lost.
- [ ] Mismatched board on resume → diagnostics open and acceptance blocked → restore the board → play resumes automatically.
- [ ] History clearly presents the in-progress physical game as resumable.

**Implementation Note**: After automated verification passes, pause for the manual on-device pass before closing the slice. Device-dependent manual items may be collected in `manual-verification.md` for an end-of-slice pass.

---

## Testing Strategy

### Unit Tests:

- Reducer transitions for the resume gate (Phase 1): enter-on-resume, clear-on-match (+`SetMode(GAME)`), block-on-mismatch with diagnostics, clear-after-restore, finished-game-skips-gate, recovery-unchanged.
- Edge: gate not entered for a finished record; `paused` reset on resume.

### Integration Tests:

- `PhysicalResumeEndToEndTest` against the emulator with injected occupancy (match / mismatch→restore / promotion-pending-at-kill), on Android host and iOS simulator.

### Manual Testing Steps:

1. Start a physical game, play a few moves, force-quit the app.
2. Relaunch, open History, tap the in-progress physical game → confirm the expected position renders and acceptance is blocked until the board is verified.
3. Matching board → confirm auto-resume and that the next physical move is accepted; verify no move lost.
4. Mismatched board → confirm diagnostics open and acceptance stays blocked; restore the board → confirm play resumes.

## Performance Considerations

None beyond existing physical-mode behaviour. The gate adds one boolean and reuses the already-bounded snapshot/occupancy path; no new IO, no new persistence, no new queries.

## Migration Notes

No data migration. The journal schema (PGN/`dirty`/`result`) is unchanged; the new state is in-memory only.

## References

- Research: `context/changes/physical-resume-after-restart/research.md`
- Recovery-loop E2E template: [`PhysicalRecoverEndToEndTest.kt`](SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalRecoverEndToEndTest.kt)
- Canonical spec: [`contract-surfaces.md:601-613`](docs/reference/contract-surfaces.md:601) (§6.3 App crash mid-game) and §1.7 (FR-012 sibling)
- S-07 reuse surface: `context/changes/reject-recover-diagnostics/` (the `acceptanceBlocked` gate, `Position.toOccupancy`, `ReedDiagnosticsGrid`)

## Progress

> Convention: `- [x]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Resume gating core

#### Automated

- [x] 1.1 Reducer unit tests pass on Android host (`:shared:testAndroidHostTest`) — b78a7a3
- [x] 1.2 Shared module compiles for all targets, no physical leak to web (`compileKotlinWasmJs` + `compileKotlinIosSimulatorArm64`) — b78a7a3
- [x] 1.3 ktlint formatting clean (`ktlint -F`) — b78a7a3

#### Manual

- [x] 1.4 Code-read: acceptance blocked on resume until matching snapshot clears `awaitingResumeConfirm`; resume-match leaves board in GAME via `effectsForModeChange` edge — `SetMode(GAME)` once on the restore path, none on a clean match

### Phase 2: Resume E2E, edge cases & write-backs

#### Automated

- [x] 2.1 Resume E2E passes on Android host (`:shared:testAndroidHostTest`) — a30d43a
- [x] 2.2 Resume E2E passes on iOS simulator (`:shared:iosSimulatorArm64Test`) — a30d43a
- [x] 2.3 Full shared build green across targets + ktlint clean — a30d43a

#### Manual

- [x] 2.4 On device: restart mid physical game, matching board → auto-resume, next move accepted, no move lost
- [x] 2.5 Mismatched board → diagnostics + blocked → restore → play resumes
- [x] 2.6 History presents the in-progress physical game as resumable
