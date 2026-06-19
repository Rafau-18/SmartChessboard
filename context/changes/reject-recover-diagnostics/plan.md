# Reject, Recover & Diagnostics (S-07) Implementation Plan

## Overview

Wire roadmap slice **S-07** (`reject-recover-diagnostics`) onto the S-06 physical-play MVI. S-06 was deliberately built to grow into S-07, so this is a **wiring + UI** slice, not a new-domain slice. It delivers FR-010 (reject illegal / ambiguous / **inconsistent** sequences → pause the game → restore the previous legal position with diagnostic assistance → retry confirmation) and FR-011 (live per-square reed diagnostics), against the F-02 emulator, **mobile-only**.

The slice adds: a distinct `INCONSISTENT` rejection, a unified acceptance gate (`recovering`) that pauses the game after a rejection, a live observed-vs-expected reed diagnostics grid, and a **hard** restore-verification (the physical board must return to `positions.last()` occupancy) before acceptance re-enables. It reuses the existing `Position.toOccupancy()` absolute compare, the `BoardCommand.SetMode(DIAGNOSTIC)` / `RequestSnapshot` commands, the `PhysicalEffect.Send` channel, and the emulator's ~10 Hz diagnostic snapshot stream — all already present and tested. The chess interpreter stays **delta-only** (no second footprint consumer → no `lessons.md` SYNC-mirror threshold tripped).

## Current State Analysis

The MVI lives in `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/`. `Playing` is a single flat flag-bag state (by design, to grow monotonically), reduced by a pure IO-free `reduce()`; the §6.2 journal write is the only IO, deferred to the `CommitMove` effect the ViewModel interprets.

**Already present (reuse, do not rebuild):**

- MVI state / intent / effect / reducer / VM — `PhysicalPlayContract.kt`, `PhysicalPlayReducer.kt`, `PhysicalPlayViewModel.kt`.
- `RejectionReason` enum (`ILLEGAL`, `AMBIGUOUS`, `PROMOTION_REQUIRED`, `SAVE_FAILED`) + the error-container rejection banner — `PhysicalPlayContract.kt:79`, `PhysicalPlayScreen.kt:199`.
- Reject branch in `confirm()` (no save, clears buffer, sets reason) — `PhysicalPlayReducer.kt:242`.
- `Position.toOccupancy(): Long` and `BoardSnapshot.isOccupied(square)` — the absolute occupancy primitives, with the documented **h8 sign-bit** gotcha — `Occupancy.kt:21`, `BoardEvents.kt:46`.
- Absolute snapshot-vs-expected compare, today wired as `setupMismatch` — `PhysicalPlayReducer.kt:94`.
- `BoardCommand.SetMode(DIAGNOSTIC)` / `RequestSnapshot` + the `PhysicalEffect.Send(command)` channel + the generic `send()` effect handler — `BoardCommand.kt:8`, `PhysicalPlayContract.kt:185`, `PhysicalPlayViewModel.kt:185`. **Defined and honoured by the emulator, but the app never sends `SetMode` yet.**
- Emulator diagnostic mode (~10 Hz snapshot job, snapshot on connect/request, mode resets to GAME on every reconnect), every event round-tripped through `BoardWireCodec` — `EmulatedBoard.kt`, contract §1.6/§1.7.
- Mobile-only gating: `supportsPhysicalBoard=false` on wasm, no wasm DI binding, History/NewGame routes gate physical play — `PlatformCapabilities.wasmJs.kt:4`, `App.kt:81`.

**The five gaps S-07 closes** (research §"What already exists vs what S-07 must add"):

1. `INCONSISTENT` rejection does not exist — an absolute board-vs-expected mismatch collapses into `ILLEGAL`.
2. No real pause→recover gate — a rejection is a *transient* message that clears the buffer; the player just re-tries. FR-010 wants the game **paused** until restored.
3. Diagnostic mode is never entered — `SetMode(DIAGNOSTIC)` is unused; no live reed stream is consumed.
4. No diagnostics UI — `ChessBoardView` renders pieces, not raw occupancy.
5. No restore-verification + retry affordance.

**The "previous legal position" is free:** a rejection never advances `positions`, so the position to restore *to* is exactly `positions.last()` — already in memory, no recompute, no FEN reader.

## Desired End State

On a physical game against the emulator (Android / iOS), with the board connected:

1. A confirmed sequence that does not resolve to a legal move, or whose physical board does not reconcile with the expected position, is **rejected** with a category-specific message (`ILLEGAL` / `AMBIGUOUS` / `INCONSISTENT`) and the game is **paused** — no move is saved and acceptance is blocked.
2. The rejection banner offers **"Show diagnostics"**. Tapping it (or a setup/reconnect mismatch automatically) enters diagnostic mode and renders a live 8×8 reed grid highlighting which squares **differ from the expected position**.
3. The player physically restores the previous legal position guided by the grid; when the board's occupancy **equals** `positions.last().toOccupancy()`, the gate clears, diagnostic mode exits, and the player can retry a fresh confirmation.
4. The live reed grid (FR-011) is reachable only through the gated mobile route — it never appears on web.

**Verification:** the headless reducer + an emulator-driven end-to-end test exercise reject → paused gate (confirm blocked) → restore (via `setOccupancy` / lift-place) → verified match → retry → accept, for both the `ILLEGAL` and `INCONSISTENT` paths; manual device verification confirms the grid and recovery UX on Android.

### Key Discoveries

- MVI is intentionally extensible — add fields + intents + reducer arms, do **not** restructure (`physical-capture-emulated/plan.md:348`).
- Two layers of "does the board match?": *relative* (`resolvePhysicalMove` over lift/place deltas) and *absolute* (`snapshot == positions.last().toOccupancy()`). FR-010's "inconsistent" and the restore-verification are both **absolute** questions → built on the existing `setupMismatch` compare, never on the interpreter — `PhysicalPlayReducer.kt:94`, `Occupancy.kt:21`.
- Diagnostic mode still emits `SQUARE_EVENT` on every change **plus** the 10 Hz snapshot (contract §1.6) — so lift/place events keep arriving during restoration and must **not** be accumulated as a move.
- `SetMode` resets to GAME on every reconnect (contract §1.7, emulator-enforced) — diagnostic mode must be re-armed on `BoardConnected` if the grid is open.
- h8 is the sign bit: always test occupancy with `(bits and (1L shl n)) != 0L`, never `> 0L` — `Occupancy.kt:16`.
- The reducer is pure and IO-free; that property is a S-06 manual-verification gate and must be preserved — `physical-capture-emulated/manual-verification.md`.

## What We're NOT Doing

- **No guided, step-by-step restoration flow.** MVP commits to *raw* diagnostics only — the grid is the assistance (PRD non-goal, `prd.md:231`).
- **No "why illegal" classifier** (not-your-piece / blocked / leaves-king-in-check). `Move.IllegalReason` granularity stays generic; the reed grid is the explanation (MVP raw-diagnostics guardrail).
- **No interpreter change.** `resolvePhysicalMove` stays delta-only; we do **not** add a second consumer of the footprint geometry (avoids the `lessons.md:187` SYNC-extract threshold).
- **No production emulator change.** Inconsistent / fault boards are fabricated in tests via the existing `setOccupancy` (disconnected) + lift/place driver surface.
- **No `Resolution.Ambiguous` retirement.** It stays as defensive/dead; `AMBIGUOUS` keeps its message.
- **No S-08 resume logic and no FR-012 BLE reconnect loop.** S-07 *designs* the "verify occupancy == expected → else recover/diagnostics" transition to be reusable, but only wires it for the reject path and the setup-mismatch auto-entry. Resume-after-restart (FR-013) and BLE disconnect/reconnect (FR-012) are later slices.
- **No two-pane / 840.dp adaptive layout** — phone-first, single column (physical is mobile-only).
- **No new `PhysicalEffect` type** — diagnostics commands reuse the existing `Send(BoardCommand)`.

## Implementation Approach

Grow the existing MVI in three layers, mirroring how S-06 was built (headless pure core → UI/wiring → emulator E2E + manual gate):

1. **Phase 1 — headless MVI core.** Add the state fields, the `INCONSISTENT` reason, the `acceptanceBlocked` gate, the two diagnostics intents, and all reducer transitions (snapshot → live occupancy + restore-verify + setup auto-entry; confirm → ILLEGAL/INCONSISTENT fork + recovering gate; accumulate guarded during recovery; `SetMode` effects at the diagnostics edges; re-arm on reconnect). Everything here is pure and exhaustively unit-tested with no UI.
2. **Phase 2 — diagnostics UI + screen wiring.** A new lightweight `ReedDiagnosticsGrid` composable, the banner CTA + `INCONSISTENT` copy + recovery guidance, the grid placed phone-first under the board, and the two VM intent methods.
3. **Phase 3 — emulator-driven E2E + manual verification.** A full recover-loop end-to-end test for both reject categories, plus a device manual-verification checklist.

The acceptance gate is the load-bearing invariant: `confirm()` and move-accumulation are blocked whenever `acceptanceBlocked` (disconnect ∪ recovering), and `recovering` is cleared **only** by an absolute occupancy match against `positions.last()` — the same transition S-08 will reuse for resume.

## Critical Implementation Details

- **Snapshot staleness in GAME mode (timing).** Outside diagnostic mode the board emits `BOARD_SNAPSHOT` only on connect / on request (not the 10 Hz stream), so `latestOccupancy` is stale during normal play. Two consequences: (a) the `INCONSISTENT` fork at confirm must act on a *fresh* snapshot — request one on the reject path (`Send(RequestSnapshot)`) and/or rely on diagnostic mode; when no fresh snapshot contradicts the expected position, stay `ILLEGAL`. (b) The restore-verification relies on the diagnostic-mode 10 Hz stream, so the recovery banner's primary CTA is "Show diagnostics"; additionally, a `ConfirmPressed` while `recovering` should emit `Send(RequestSnapshot)` so recovery can also clear without opening the grid.
- **Lift/place during recovery is not a move (state sequencing).** Diagnostic mode keeps emitting `SQUARE_EVENT` (§1.6); while `recovering` (or otherwise restoring), `accumulate()` must **not** build a move or mutate `eventsSinceConfirm` / `liftedSquares` from those events — the snapshot occupancy, not the deltas, drives restore-verification. Gate accumulation on `!recovering`.
- **`SetMode` resets to GAME on reconnect (lifecycle).** The emulator (and real firmware, §1.7) reset to GAME mode on every reconnect. The `BoardConnected` arm already re-requests a snapshot; it must additionally re-emit `Send(SetMode(DIAGNOSTIC))` when the grid is currently open, or diagnostics silently stop streaming after a reconnect.
- **`SetMode` effect emission at visibility edges (state sequencing).** `SetMode(DIAGNOSTIC)` must be sent when the grid transitions hidden→shown and `SetMode(GAME)` when shown→hidden, and exactly once per edge (no per-snapshot spam). Implement by emitting the effect from the arms that flip the contributing flags (ShowDiagnostics / HideDiagnostics / setup-mismatch edges in `SnapshotReceived` / recovery-clear), or via a small `effectsForModeChange(prev, next)` helper comparing derived `diagnosticsVisible`.
- **h8 sign bit (correctness).** The grid and every occupancy compare must test `(bits and (1L shl n)) != 0L`. Reuse `BoardSnapshot.isOccupied` / `Position.toOccupancy()`; never hand-roll a signed `> 0` test.

---

## Phase 1: Headless MVI core — gate, INCONSISTENT, restore-verify

### Overview

Extend the contract and the pure reducer so the full reject → pause → restore-verify → retry state machine works headless, plus the `INCONSISTENT` fork and the diagnostics-mode entry/exit transitions. No UI, no VM behavior change beyond two new intent methods (added in Phase 2). All transitions are unit-tested in `commonTest`.

### Changes Required:

#### 1. State, rejection reason, intents

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayContract.kt`

**Intent**: Add the live-occupancy + recovery surface to `Playing`, the new rejection category, the unified gate, and the diagnostics intents — so the reducer can pause acceptance, track the latest reed occupancy, and drive diagnostic mode.

**Contract**:
- `PhysicalPlayState.Playing` gains: `latestOccupancy: Long? = null` (last snapshot occupancy, kept fresh for the grid + restore-verify), `recovering: Boolean = false` (the reject-recovery acceptance gate), `manualDiagnostics: Boolean = false` (user opened the grid via the banner CTA).
- New derived vals on `Playing`: `acceptanceBlocked: Boolean get() = paused || recovering`; `diagnosticsVisible: Boolean get() = manualDiagnostics || setupMismatch`. (`paused` stays as the disconnect-specific predicate used by the banner.)
- `RejectionReason` gains `INCONSISTENT` (the physical board does not reconcile with the expected position).
- `PhysicalMsg` gains user-origin `ShowDiagnostics` and `HideDiagnostics` (`data object`s).
- No new `PhysicalEffect` — diagnostics use the existing `Send(BoardCommand)`.

#### 2. Reducer transitions

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt`

**Intent**: Implement the gate, the live-occupancy tracking, the restore-verification, the setup-mismatch auto-entry, the `ILLEGAL`/`INCONSISTENT` fork, and the diagnostics-mode `SetMode` effects — all pure.

**Contract**:
- `SnapshotReceived(occupancy)` arm: always store `latestOccupancy = occupancy`; when `eventsSinceConfirm.isEmpty()`, recompute `setupMismatch = occupancy != position.toOccupancy()`; if `recovering && occupancy == position.toOccupancy()` → clear `recovering` + `rejection` + `manualDiagnostics` and emit `Send(SetMode(GAME))` + `Send(RequestSnapshot)` (restore verified); emit `Send(SetMode(DIAGNOSTIC))` on a `setupMismatch` false→true edge and `Send(SetMode(GAME))` on the true→false edge (respecting other open reasons).
- `confirm()`: replace the `state.paused` guard with `state.acceptanceBlocked`; on `Resolution.Illegal`, fork — surface `INCONSISTENT` when a fresh snapshot shows the board is inconsistent with the expected position (absolute compare), otherwise `ILLEGAL` — and set `recovering = true` on either reject; emit `Send(RequestSnapshot)` on the reject path so the fork + restore-verify have fresh occupancy. `Resolved` / `NeedsPromotion` / `Ambiguous` / `Incomplete` behavior is unchanged except `Ambiguous` also sets `recovering = true`.
- `accumulate()`: no-op for move-building while `state.recovering` (restoration lift/place are not a move).
- `ShowDiagnostics` arm: set `manualDiagnostics = true`, emit `Send(SetMode(DIAGNOSTIC))` + `Send(RequestSnapshot)` (on the hidden→shown edge). `HideDiagnostics` arm: set `manualDiagnostics = false`, emit `Send(SetMode(GAME))` if the grid is now fully hidden.
- `BoardConnected` arm: keep the existing `Send(RequestSnapshot)`; additionally emit `Send(SetMode(DIAGNOSTIC))` when `diagnosticsVisible` (re-arm after the reconnect mode reset).
- `commit()`: also reset `recovering = false` and `manualDiagnostics = false` on a successful move (alongside the existing `setupMismatch = false`).

**Note on the fork rule**: `INCONSISTENT` = the *absolute* board does not match the expected position; `ILLEGAL` = the *delta* sequence completed but is not a legal move. The interpreter is untouched; the fork is a reducer-level absolute compare against `positions.last().toOccupancy()` using the freshest available snapshot. Deterministic on the emulator (exact snapshots); degrades to `ILLEGAL` when no fresh snapshot is available.

### Success Criteria:

#### Automated Verification:

- [ ] Reducer/contract compiles: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:compileKotlinAndroid --console=plain --no-daemon`
- [ ] New + existing reducer unit tests pass: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon` (extends `presentation/physical/PhysicalPlayReducerTest.kt`)
- [ ] iOS + wasm targets green: `:shared:iosSimulatorArm64Test` and `:shared:wasmJsTest`
- [ ] ktlint clean: `ktlint -F` from `SmartChessboard/`

#### Manual Verification:

- [ ] Reducer remains pure / IO-free (no new imports of `data/` / repository / coroutine types into the reducer) — code read, preserving the S-06 gate.

**Implementation Note**: Phase 1 is headless and fully covered by automated reducer tests. The one Manual item is a code-read of reducer purity; per the project lesson, record it in `manual-verification.md` and proceed — no device run is needed at this phase.

---

## Phase 2: Diagnostics UI + screen wiring

### Overview

Render the live reed diagnostics grid, surface the `INCONSISTENT` message and the "Show diagnostics" recovery affordance, and wire the two new intents through the ViewModel. Phone-first single column. No new ViewModel effect handling is needed — the generic `Send` effect handler already exists (`PhysicalPlayViewModel.kt:185`).

### Changes Required:

#### 1. Reed diagnostics grid composable

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/board/ReedDiagnosticsGrid.kt` (new, sibling of `ChessBoardView.kt`)

**Intent**: A lightweight 8×8 grid of reed-switch states that highlights squares differing from the expected position, so the player can see exactly which fields to fix during restoration. Separate from `ChessBoardView` (which is coupled to `Position`/pieces) because diagnostics needs only a bitfield and an observed-vs-expected diff.

**Contract**: `@Composable fun ReedDiagnosticsGrid(observed: Long, expected: Long?, modifier: Modifier = Modifier, orientation: Color = Color.WHITE)`. Renders 64 cells using the a1=0…h8=63 convention and the screen orientation; each cell shows occupied/empty and, when `expected != null`, tints squares where `observed` and `expected` differ. **Must** test bits with `(bits and (1L shl n)) != 0L` (reuse `BoardSnapshot.isOccupied` semantics) — h8 is the sign bit. Display-only, no interaction.

#### 2. Banner + grid wiring on the screen

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayScreen.kt`

**Intent**: Add the `INCONSISTENT` message, a "Show diagnostics" action on the recovery banner, render the grid under the board when diagnostics are visible, and a way to hide it — so the player can recover from a paused game.

**Contract**:
- `rejectionText()` gains an `INCONSISTENT` arm (e.g. distinguishing "the board doesn't match the game — restore the previous position" from the generic illegal copy).
- `BoardMessage` (or the recovery section) renders a "Show diagnostics" button when `recovering` and the grid is hidden; recovery guidance copy stays raw (no step-by-step).
- `PlayingContent` renders `ReedDiagnosticsGrid(observed = state.latestOccupancy ?: state.position.toOccupancy(), expected = state.position.toOccupancy(), orientation = state.orientation)` under the board when `state.diagnosticsVisible`, with a "Hide" affordance when it was opened manually (`manualDiagnostics`).
- New callbacks `onShowDiagnostics` / `onHideDiagnostics` threaded from the screen to `PlayingContent`.

#### 3. ViewModel intents

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayViewModel.kt`

**Intent**: Expose the two user intents; the existing `dispatch`/`runEffect`/`send` path already interprets the resulting `Send(SetMode(...))` effects.

**Contract**: add `fun showDiagnostics() = dispatch(PhysicalMsg.ShowDiagnostics)` and `fun hideDiagnostics() = dispatch(PhysicalMsg.HideDiagnostics)`. No change to `runEffect` (the `Send` arm already handles all `BoardCommand`s).

### Success Criteria:

#### Automated Verification:

- [ ] App compiles for all targets: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:compileKotlinAndroid :webApp:compileKotlinWasmJs --console=plain --no-daemon`
- [ ] Android debug APK assembles: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :androidApp:assembleDebug --console=plain --no-daemon`
- [ ] Existing presentation tests still pass: `:shared:testAndroidHostTest`
- [ ] ktlint clean: `ktlint -F` from `SmartChessboard/`

#### Manual Verification:

- [ ] On Android, after an illegal sequence the game pauses, the banner shows the reason + "Show diagnostics", and tapping it renders the live reed grid.
- [ ] The grid highlights exactly the squares that differ from the on-screen position (including an h8-corner case).
- [ ] Web build still excludes the physical route (no diagnostics grid reachable on wasm).

**Implementation Note**: After automated verification passes, pause for the human to confirm the device checks (grid render, diff highlight, web exclusion) before Phase 3. Per the project lesson, code/doc-read manual items may be recorded in `manual-verification.md` and the phase committed; the interactive device pass is collected at end-of-slice.

---

## Phase 3: Emulator-driven end-to-end + manual verification

### Overview

Prove the whole recover loop against the emulator for both reject categories, and capture the manual device checklist. Mirrors the existing `PhysicalCaptureEndToEndTest` and reuses the emulator driver surface; no production emulator change.

### Changes Required:

#### 1. Recover-loop end-to-end test

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalRecoverEndToEndTest.kt` (new; sibling of `PhysicalCaptureEndToEndTest.kt`)

**Intent**: Drive the real `PhysicalPlayViewModel` + `EmulatedBoard` end-to-end through reject → paused gate → restore → verified → retry → accept, for `ILLEGAL` and `INCONSISTENT`, asserting no save happens until the retried legal move.

**Contract**: using the emulator driver (`lift`/`place`/`pressButton`, and `setOccupancy` while disconnected to fabricate an inconsistent board):
- **Illegal path**: drive a lift/place sequence that resolves to an illegal move, confirm → assert `recovering`, `rejection == ILLEGAL`, acceptance blocked (a second confirm is a no-op, nothing saved); `ShowDiagnostics` → assert a `SetMode(DIAGNOSTIC)` was sent; restore occupancy to `positions.last().toOccupancy()` → assert `recovering` clears and `SetMode(GAME)` is sent; retry a legal sequence + confirm → assert exactly one move is journaled.
- **Inconsistent path**: fabricate a board occupancy that does not match the expected position, confirm → assert `rejection == INCONSISTENT` and the gate; restore + retry as above.
- Assert the journal/autosaver sees **no** `acceptMove` until the final legal retry (the §6.2 gate holds).

#### 2. Manual verification doc

**File**: `context/changes/reject-recover-diagnostics/manual-verification.md` (new)

**Intent**: Record the device checklist and the code-read manual items deferred from Phases 1–2 (reducer purity, grid render, diff highlight, web exclusion), following the `physical-capture-emulated/manual-verification.md` precedent.

**Contract**: a checklist mapping each Manual Verification item from Phases 1–3 to a pass/fail with notes; the interactive Android pass is performed here at end-of-slice.

### Success Criteria:

#### Automated Verification:

- [ ] End-to-end recover test passes on all three targets: `:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest`
- [ ] Full shared test suite green (no regressions): `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- [ ] ktlint clean: `ktlint -F` from `SmartChessboard/`

#### Manual Verification:

- [ ] Full reject→recover→retry loop verified by hand on an Android device for both `ILLEGAL` and `INCONSISTENT`, with the diagnostics grid guiding restoration.
- [ ] No accepted move is ever saved from a rejected or unrestored board (journal inspected).
- [ ] `manual-verification.md` completed and checked in.

**Implementation Note**: This is the slice's manual gate. Collect all deferred device checks here, complete `manual-verification.md`, and only then close the slice.

---

## Testing Strategy

### Unit Tests (`commonTest`, all three targets):

- **Reducer** (`PhysicalPlayReducerTest.kt`, extend): the `ILLEGAL`/`INCONSISTENT` fork; `recovering` set on reject and cleared **only** on an exact occupancy match; `acceptanceBlocked` blocks `confirm()`; `accumulate()` is a no-op while `recovering`; `SnapshotReceived` keeps `latestOccupancy` fresh and recomputes `setupMismatch` only at rest; `ShowDiagnostics`/`HideDiagnostics` emit the right `SetMode` effects on the visibility edges; `BoardConnected` re-arms `SetMode(DIAGNOSTIC)` when the grid is open; `commit()` resets the recovery flags.
- **Grid geometry** (optional, alongside `ChessBoardGeometryTest`): observed-vs-expected diff bit math, including the h8 sign-bit square.

### Integration / End-to-End:

- `PhysicalRecoverEndToEndTest` (Phase 3): the full loop against `EmulatedBoard` for both reject categories, asserting the §6.2 no-save-until-restored-and-legal guarantee.

### Manual Testing Steps:

1. Start a physical game (Android), board connected, set up to match the opening.
2. Make an illegal move on the board, press confirm → game pauses, banner shows the reason + "Show diagnostics".
3. Verify a second confirm does nothing (no save, still paused).
4. Tap "Show diagnostics" → live reed grid appears, highlighting the differing squares.
5. Restore the previous position → grid diff clears, banner clears, game un-pauses.
6. Make a legal move + confirm → exactly one move is appended; verify in the move list.
7. Repeat with an inconsistent board (e.g. an extra/missing piece) → expect the `INCONSISTENT` message.
8. Confirm the web target never shows the physical/diagnostics route.

## Performance Considerations

Diagnostic mode runs a ~10 Hz snapshot stream only while the grid is open / during recovery; it is exited (`SetMode(GAME)`) as soon as the board is restored or the grid is hidden, so steady-state play is unaffected. The grid is a 64-cell static composable recomposed on each snapshot — trivial. No new allocations on the hot lift/place path (accumulation is short-circuited during recovery).

## Migration Notes

Pure additive change to the S-06 MVI — no data, schema, or persisted-format changes. New `Playing` fields default to their no-op values, so any in-flight state shape is forward-compatible. The only behavioral change to existing flows: `confirm()` now gates on `acceptanceBlocked` instead of `paused` (a strict superset — disconnected still blocks), and a rejection now pauses instead of silently clearing, which is the intended FR-010 behavior.

## References

- Research: `context/changes/reject-recover-diagnostics/research.md`
- Change identity: `context/changes/reject-recover-diagnostics/change.md`
- Roadmap S-07: `context/foundation/roadmap.md:194`
- PRD: FR-010 (`prd.md:139`), FR-011 (`prd.md:142`), US-02 (`prd.md:74`), raw-diagnostics non-goal (`prd.md:231`)
- Contract: diagnostic mode §1.6, disconnect/reconnect §1.7 — `docs/reference/contract-surfaces.md:135`
- S-06 precedent: `context/changes/physical-capture-emulated/plan.md` (`:101` S-07 boundary, `:348` "grow into S-07"), `manual-verification.md`
- Lessons: web digital-only (`lessons.md:17`), MVI justification (`lessons.md:57`), SYNC-mirror geometry (`lessons.md:187`)
- Key code: `PhysicalPlayContract.kt:43`, `PhysicalPlayReducer.kt:94`/`:242`, `PhysicalPlayViewModel.kt:185`, `PhysicalPlayScreen.kt:199`, `Occupancy.kt:21`, `BoardCommand.kt:8`, `BoardEvents.kt:42`, `ChessBoardView.kt:103`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Headless MVI core — gate, INCONSISTENT, restore-verify

#### Automated

- [x] 1.1 Reducer/contract compiles (`:shared:compileKotlinAndroid`) — fc962d2
- [x] 1.2 Reducer unit tests pass (`:shared:testAndroidHostTest`, extends `PhysicalPlayReducerTest.kt`) — fc962d2
- [x] 1.3 iOS + wasm targets green (`:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest`) — fc962d2
- [x] 1.4 ktlint clean (`ktlint -F`) — fc962d2

#### Manual

- [ ] 1.5 Reducer remains pure / IO-free (code read; record in `manual-verification.md`)

### Phase 2: Diagnostics UI + screen wiring

#### Automated

- [x] 2.1 App compiles for all targets (`:shared:compileKotlinAndroid`, `:webApp:compileKotlinWasmJs`) — 7ba6c6a
- [x] 2.2 Android debug APK assembles (`:androidApp:assembleDebug`) — 7ba6c6a
- [x] 2.3 Existing presentation tests still pass (`:shared:testAndroidHostTest`) — 7ba6c6a
- [x] 2.4 ktlint clean (`ktlint -F`) — 7ba6c6a

#### Manual

- [ ] 2.5 Illegal sequence pauses the game; banner shows reason + "Show diagnostics"; tapping renders the live grid
- [ ] 2.6 Grid highlights exactly the squares differing from the on-screen position (incl. h8 corner)
- [ ] 2.7 Web build still excludes the physical/diagnostics route

### Phase 3: Emulator-driven end-to-end + manual verification

#### Automated

- [x] 3.1 End-to-end recover test passes on all three targets (`PhysicalRecoverEndToEndTest`) — 48d6a8d
- [x] 3.2 Full shared test suite green — no regressions (`:shared:testAndroidHostTest`) — 48d6a8d
- [x] 3.3 ktlint clean (`ktlint -F`) — 48d6a8d

#### Manual

- [ ] 3.4 Full reject→recover→retry loop verified by hand on Android for `ILLEGAL` and `INCONSISTENT`
- [ ] 3.5 No accepted move ever saved from a rejected or unrestored board (journal inspected)
- [ ] 3.6 `manual-verification.md` completed and checked in
