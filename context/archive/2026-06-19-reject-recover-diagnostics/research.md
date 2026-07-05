---
date: 2026-06-19T18:03:24+0200
researcher: claude (4 parallel codebase-research agents)
git_commit: c9442c7802b69d68ec2728ac1b976757d2aa30f1
branch: main
repository: smartchessboard
change_id: reject-recover-diagnostics
topic: "S-07 reject-recover-diagnostics: rejection taxonomy, live reed diagnostics, and diagnostics-assisted recovery on the physical-play MVI"
tags: [research, codebase, physical-board, reed-diagnostics, sequence-interpreter, mvi, s-07]
status: complete
last_updated: 2026-06-19
last_updated_by: claude
---

# Research: S-07 reject-recover-diagnostics

**Date**: 2026-06-19T18:03:24+0200
**Researcher**: claude (4 parallel codebase-research agents)
**Git Commit**: c9442c7802b69d68ec2728ac1b976757d2aa30f1
**Branch**: main
**Repository**: smartchessboard

## Research Question

How do we implement roadmap slice **S-07** (`reject-recover-diagnostics`) on top of the just-finished S-06 physical-play code?

> **S-07 outcome** (roadmap): *User can see illegal, ambiguous, or inconsistent sequences rejected with the game paused, and use a live per-square reed diagnostics view to restore the previous legal position and retry confirmation.*
> **PRD**: FR-010 (reject illegal/ambiguous/inconsistent → pause → manual restore with diagnostic assistance → retry), FR-011 (live reed-switch diagnostics for every square), US-02. **Prereq**: S-06 (implemented). **MVP guardrail**: *raw* diagnostics only — guided restoration is a PRD non-goal. Physical/diagnostics are **mobile-only** (never web).

**Scope chosen**: S-07 + forward notes (what S-08/S-09 reuse and what must stay hardware-faithful). **Emphasis**: plan-ready dossier (file contracts, seams, insertion points).

## Summary

**The headline finding: S-06 was deliberately built to grow into S-07, so most of the plumbing already exists.** S-07 is far more a *wiring + UI* slice than a net-new-domain slice. The MVI state machine, the rejection enum, the reject branch, a rejection banner, the diagnostic-mode command, the emulator's 10 Hz diagnostic snapshot stream, the `Position → occupancy` helper, and the occupancy-compare seam are all already present and tested.

What's genuinely missing breaks into **five gaps**:

1. **The "inconsistent" rejection category does not exist.** The interpreter only sees lift/place *deltas*, never an absolute board snapshot, so "the board can't be explained by any legal move" collapses into `Illegal`. Detecting it requires the *absolute* snapshot-vs-expected occupancy compare (the `setupMismatch` seam already does exactly this for setup).
2. **No real "pause → recover" gate.** Today a rejection is a *transient message* that clears the move buffer; the player just re-tries. FR-010 wants the game **paused** (acceptance blocked) until the previous legal position is restored and re-confirmed.
3. **Diagnostic mode is never entered.** `BoardCommand.SetMode(BoardMode.DIAGNOSTIC)` exists and the emulator honours it, but the app never sends it. No live reed grid is rendered.
4. **No diagnostics UI.** No 8×8 reed-occupancy grid composable; `ChessBoardView` renders pieces, not raw occupancy. A new lightweight grid + a `latestOccupancy` state field are needed.
5. **No restore-verification + retry affordance.** Nothing verifies the board was restored to the previous legal position before re-enabling moves, and there is no explicit "restore & retry" intent.

**The "previous legal position" is free**: a rejection never advances `positions`, so the position to restore *to* is simply `positions.last()` — already in memory, no recompute, no FEN reader needed.

## What already exists vs. what S-07 must add

| Capability needed by FR-010/FR-011 | Status | Evidence |
| --- | --- | --- |
| MVI state machine for physical play (state/intent/reducer/effect/VM) | ✅ exists | `PhysicalPlayContract.kt`, `PhysicalPlayReducer.kt`, `PhysicalPlayViewModel.kt` |
| `RejectionReason` enum + rejection banner | ✅ exists (ILLEGAL/AMBIGUOUS/PROMOTION_REQUIRED/SAVE_FAILED) | `PhysicalPlayContract.kt:78`, `PhysicalPlayScreen.kt:198` |
| Reject branch on confirm (no save, clears buffer, sets reason) | ✅ exists | `PhysicalPlayReducer.kt:249` |
| `Position → 64-bit occupancy` helper | ✅ exists — **reuse, don't build** | `Occupancy.kt:21` |
| `BoardSnapshot.occupancy: Long` + `isOccupied(square)` | ✅ exists | `BoardEvents.kt:38` |
| Absolute snapshot-vs-expected occupancy compare | ✅ exists (as `setupMismatch`) | `PhysicalPlayReducer.kt:94` |
| `SetMode(DIAGNOSTIC)` / `RequestSnapshot` commands + effect channel | ✅ command + `Send` effect exist; **never sent by app** | `BoardCommand.kt:9`, `PhysicalPlayContract.kt` (`PhysicalEffect.Send`) |
| Emulator diagnostic mode @ ~10 Hz, snapshot on connect/request, mode reset on reconnect | ✅ exists + tested | `EmulatedBoard.kt` (`startDiagnosticJob`, ~100 ms), `EmulatedBoardEndToEndTest.kt` |
| Mobile-only gating (no physical/diagnostics route on web) | ✅ exists | `PlatformCapabilities.wasmJs.kt:4`, `App.kt:81`, DI excludes wasm |
| **"Inconsistent" as a distinct rejection** | ❌ gap — collapses into `Illegal` | `SequenceInterpreter.kt:184`; no `INCONSISTENT` in enum |
| **Pause/recovering gate (block acceptance until restored)** | ❌ gap — rejection is transient only | `PhysicalPlayReducer.kt:266` (clears buffer, no gate) |
| **Enter/exit diagnostic mode from the reject path** | ❌ gap — `SetMode` unused | `BoardCommand.kt:9` (defined, never sent) |
| **Live 8×8 reed diagnostics grid composable + `latestOccupancy` state** | ❌ gap | `ChessBoardView.kt:103` renders pieces, not occupancy |
| **Restore-verification (observed == expected) + "restore & retry" intent** | ❌ gap | — |

## Detailed Findings

### Area 1 — Physical-play MVI core & current reject path (S-06)

The MVI lives in `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/`. It is a single `Playing` state carrying *flags*, not a tree of sub-states — by design, so S-07 grows it monotonically (per `physical-capture-emulated/plan.md:348`).

- **State** — `PhysicalPlayContract.kt:26`. `sealed interface PhysicalPlayState { Loading; Error; Playing(...) }`. `Playing` carries `positions: List<Position>`, `sanMoves`, `status`, `orientation`, `connectionState`, `liftedSquares: Set<Int>`, `eventsSinceConfirm: List<BoardEvent.SquareEvent>` (the lift/place buffer), `setupMismatch: Boolean`, `pendingPromotion`, `result`, `endGamePrompt`, and `rejection: RejectionReason?`. Derived: `position get() = positions.last()`, `terminal`, `paused get() = connectionState == DISCONNECTED`.
- **Intents** — `PhysicalPlayContract.kt:94`. `sealed interface PhysicalMsg` with board-origin (`SquareLifted`, `SquarePlaced`, `ConfirmPressed(button)`, `SnapshotReceived(occupancy)`, `BoardConnected/Disconnected`), user-origin (`PromotionPicked`, `EndGameRequested`, `FlipBoard`, `Retry` — the *load* retry, not reject recovery), and effect-feedback (`Loaded`, `LoadFailed`, `MoveCommitted`, `MoveRejected(reason)`, `SyncChanged`).
- **Rejection enum** — `PhysicalPlayContract.kt:78`. `enum class RejectionReason { ILLEGAL, AMBIGUOUS, PROMOTION_REQUIRED, SAVE_FAILED }`. No `INCONSISTENT`.
- **Effects** — `PhysicalPlayContract.kt:167`. `sealed interface PhysicalEffect { LoadGame; CommitMove(confirmed, sanSoFar, move); FinishGame(result, sanMoves); Send(command: BoardCommand) }`. **`Send` is the channel S-07 uses for `SetMode`/`RequestSnapshot`.**
- **Reducer** — pure, IO-free, `PhysicalPlayReducer.kt:27`. `confirm()` (`:242`) is the heart:
  - wrong-side button, paused, or finished → no-op;
  - `pendingPromotion != null` → `rejection = PROMOTION_REQUIRED`;
  - else `when (resolvePhysicalMove(position, eventsSinceConfirm))`:
    - `Resolved(move)` → emit `CommitMove`;
    - `NeedsPromotion` → set `pendingPromotion` (keep buffer);
    - `Ambiguous` → `rejection = AMBIGUOUS`, **clear buffer + highlights**, no effect;
    - `Illegal` → `rejection = ILLEGAL`, **clear buffer + highlights**, no effect;
    - `Incomplete` → no-op (keep accumulating).
- **The §6.2 write gate** — `PhysicalPlayViewModel.kt:152` `commitMove()`: re-`validate()`s, then **synchronously** `autoSaver.acceptMove(gameId, writePgn(...))` *before* dispatching `MoveCommitted`; a write failure → `MoveRejected(SAVE_FAILED)` (state does not advance). Best-effort `autoSaver.sync()` afterwards.
- **Buffer lifecycle** — `eventsSinceConfirm` is appended in `accumulate()` (`:211`) on every lift/place; cleared on success (`commit` `:310`), on save-fail (`:189`), on reject (`:270`/`:280`), and on promotion-dismiss (`:142`). Kept on `Incomplete`, `NeedsPromotion`, wrong-side, and disconnect.

**Current reject UX (the precise behaviour S-07 replaces):** rejection sets a *transient* `rejection` reason; `BoardMessage` (`PhysicalPlayScreen.kt:198`) shows error-container text via `rejectionText()` (`:226`); the buffer is wiped so the player simply makes the move again. **There is no enforced "paused until restored" gate and no diagnostics.** This was the explicit S-06/S-07 boundary (`physical-capture-emulated/plan.md:101`: *"No diagnostics / guided recovery (S-07)… Rejection is minimal (message + no save)."*).

### Area 2 — Sequence interpreter, rejection taxonomy & Position↔occupancy

- **Interpreter** is a pure top-level function, not a class: `fun resolvePhysicalMove(position: Position, events: List<BoardEvent.SquareEvent>): Resolution` — `domain/board/SequenceInterpreter.kt:37`. It builds an `Observed` signature (net-vacated / net-arrived / lifted-then-re-occupied) via `observe()` (`:79`), filters `legalMoves(position)` by `footprintOf(...).matches(observed)`, and **groups matches by `(from,to)`**.
- **Result type** — `domain/board/Resolution.kt:14`. `sealed interface Resolution { Resolved(move); NeedsPromotion(from,to); Ambiguous; Illegal; Incomplete }`. Total and pure — never null/throws/empty-list. `Resolved.move` always comes from `legalMoves` (never fabricated).
- **Rejection taxonomy verdict (load-bearing):** only **two** of FR-010's three categories exist, and the missing one is **inconsistent**:
  - *illegal* → `Resolution.Illegal` via `classifyNoMatch()` (`SequenceInterpreter.kt:184`) when the signature looks *completed* but matches no legal move → `RejectionReason.ILLEGAL`.
  - *ambiguous* → `Resolution.Ambiguous` when `groups.size > 1` — but **documented unreachable in practice** (`Resolution.kt:31`: a full lift/place stream self-disambiguates). Effectively dead/defensive today.
  - *inconsistent* (board explainable by **no** legal move from the expected position) → **NOT distinguished**; folds into `Illegal`/`Incomplete`. The interpreter never sees an absolute `BOARD_SNAPSHOT`, only `eventsSinceConfirm` deltas. **To add it, S-07 detects inconsistency via the absolute snapshot compare, then forks `Illegal` → `Illegal` + a new `Inconsistent`, and adds `RejectionReason.INCONSISTENT`.**
- **Footprint geometry** — `footprintOf()` (`SequenceInterpreter.kt:139`) returns `Footprint(vacated, arrived, captureDest)`, hand-deriving castle/en-passant/capture/quiet square sets. `Footprint.matches()` (`:132`): exact equality on vacated/arrived, **subset** on captureDest (j'adoube/noise tolerance). This geometry is the SYNC-mirror of `ChessRules.applyMove` — comments on both sides (`ChessRules.kt:78` ↔ `SequenceInterpreter.kt:122`), per the `lessons.md` SYNC rule. **Note for planning:** if S-07 adds a second consumer of this geometry, the lesson's stated threshold ("extract a shared `squaresTouched`") is reached.
- **Canonical position & "previous legal position"** — PGN is the source of truth; `parsePgn()` (`domain/chess/pgn/PgnParser.kt:27`) replays SAN → `ReplayGame.positions` (invariant `size == sanMoves.size + 1`). Live state holds the whole list; current = `positions.last()` (`PhysicalPlayContract.kt`). **A rejection never mutates `positions`**, so the previous legal position to restore to is exactly `positions.last()` — already in memory, no recompute, **no FEN reader required** (none ships; `Occupancy.kt:11`, `plan.md:162`).
- **Position → occupancy helper EXISTS** — `domain/board/Occupancy.kt:21`: `fun Position.toOccupancy(): Long { ... if (pieceAt(square) != null) bits = bits or (1L shl square) }`. Convention `index = file + 8*rank`, a1=0…h8=63 (`Square.kt`), the documented inverse of `BoardSnapshot.isOccupied`. `Position.board: List<Piece?>` (64 entries; `Position.kt:20`, `pieceAt(n)`). **h8 gotcha** (`Occupancy.kt:16`): bit 63 is the sign bit; test with `(bits and (1L shl n)) != 0L`, never `> 0L` — the diagnostics grid must obey this.
- **Finer "why illegal" deferred to S-07** — `domain/chess/Move.kt:27` `IllegalReason` doc explicitly defers not-your-piece / blocked / leaves-king-in-check classification to "S-07 diagnostics". Given the *raw-diagnostics* MVP guardrail, S-07 likely does **not** build a why-classifier; the reed grid is the explanation.

### Area 3 — Reed-board emulator (F-02), board abstraction & DI

- **Port** — `domain/board/BoardConnection.kt:1`: `interface BoardConnection { val connectionState: StateFlow<BoardConnectionState>; val events: SharedFlow<BoardEvent>; suspend fun send(command: BoardCommand) }`. This is the single seam; **the S-09 BLE adapter swaps in here.**
- **Events** — `domain/board/BoardEvents.kt:10`: `sealed interface BoardEvent { SquareEvent(square,type); ButtonEvent(button); BoardSnapshot(occupancy: Long){ isOccupied(square) }; DeviceStatus(...) }`. `BoardSnapshot.occupancy` is a 64-bit `Long`; **there is no pre-built 8×8 grid type** — the UI derives per-square state from the bitfield.
- **Commands** — `domain/board/BoardCommand.kt:7`: `sealed interface BoardCommand { SetMode(mode: BoardMode); RequestSnapshot; RequestStatus }`, `enum BoardMode { GAME, DIAGNOSTIC }`. **Diagnostic mode is fully modelled at the port; the app just never sends it yet.**
- **Emulator** — `data/board/emulator/EmulatedBoard.kt` implements `BoardConnection`, exposes a read-only `occupancy: Long`, a driver surface (`connect/disconnect/lift/place/pressButton/setOccupancy`), and honours commands: `SetMode(DIAGNOSTIC)` starts a ~100 ms (~10 Hz) snapshot job (`startDiagnosticJob`), `SetMode(GAME)` stops it, mode resets to GAME on every reconnect (§1.7), `RequestSnapshot` emits immediately, and **all events round-trip through `BoardWireCodec`** (encode→decode) for byte-fidelity. `setOccupancy()` (disconnected only) is useful in tests to inject an arbitrary/"inconsistent" board. Snapshot byte layout per `BoardWireCodec.kt` matches contract §1.3 (clarified 2026-06-16).
- **DI / capability** — `expect val supportsPhysicalBoard` (`platform/PlatformCapabilities.kt`) is `true` on android/ios, `false` on wasm (`PlatformCapabilities.wasmJs.kt:4`). `single<BoardConnection> { EmulatedBoard(...).also { connect() } }` is bound in `di/PlatformModule.android.kt` and `.ios.kt` only; **wasm binds no `BoardConnection`** (and no `PhysicalPlayViewModel`).
- **Verdict:** the transport/protocol/emulator layer needs **no changes** for FR-011 — it already provides `SetMode(DIAGNOSTIC)`, the 10 Hz snapshot stream, `RequestSnapshot`, and `BoardSnapshot.occupancy`. S-07's work here is purely *consuming* it (send `SetMode`, subscribe to snapshots, render). The one open emulator question is fault-injection for *testing* the recovery path (see Open Questions).

### Area 4 — Diagnostics view, paused/error UI & navigation gating

- **Physical screen** — `presentation/physical/PhysicalPlayScreen.kt:57` `PhysicalPlayScreen(gameId, onBack, onReviewGame, onBackToHistory)`. Renders `StatusBanner` (turn/terminal), `BoardMessage` (`:198`, the rejection/paused/setup surface), `ChessBoardView` (display-only, `highlightedSquares = state.liftedSquares`), `MoveList`. Error (load) state has a centred Retry (`:86`).
- **`ChessBoardView`** — `presentation/board/ChessBoardView.kt:103`: `ChessBoardView(position, modifier, orientation, interaction: BoardInteraction?, bestMoveArrow: BoardArrow?, highlightedSquares: Set<Int>)`. It is coupled to `Position` (piece rendering). **Assessment: build a *separate* lightweight `ReedDiagnosticsGrid(occupancy: Long, expected: Long?)` composable** rather than overloading `ChessBoardView` — diagnostics needs only a bitfield and wants to show observed-vs-expected, not pieces.
- **Reusable patterns** (match house style):
  - error-container banner — `PhysicalPlayScreen.kt:198` (`BoardMessage` + `rejectionText` `:226`).
  - two-step irreversibility dialog — `presentation/play/EndGamePicker.kt:32` (pick → confirm "can't be undone"). Template for a "restore erases the rejected sequence?" confirm, if a confirm is wanted.
  - modal surface — `presentation/board/PromotionPicker.kt:37`.
  - load error + Retry — `PhysicalPlayScreen.kt:86`, mirrored in `ReplayScreen`/digital `PlayScreen`.
- **Navigation gating (mobile-only rule)** — route `PhysicalPlayKey(gameId)` (`Routes.kt:37`, alongside `App.kt`). In `App.kt:81`, History→Physical is gated `status==IN_PROGRESS && mode==PHYSICAL && supportsPhysicalBoard` (else →Replay); NewGame→Physical gated on `mode==PHYSICAL` (`:105`), and the Digital/Physical picker only shows when `supportsPhysicalBoard`. With wasm `supportsPhysicalBoard=false` + no wasm DI binding, **web can never reach physical play or diagnostics** — S-07 keeps the diagnostics grid in `commonMain` UI but reachable only through this gated route (it never appears on web).
- **Adaptive precedent** — `presentation/replay/ReplayScreen.kt:127` uses `BoxWithConstraints` + an `840.dp` breakpoint for a two-pane layout. S-07 *may* place board + diagnostics side-by-side on wide screens the same way, but physical is phone-first/mobile-only, so this is optional polish, not core.
- **Snapshot handling today** — `PhysicalPlayViewModel.kt:220` maps `BoardSnapshot` → `SnapshotReceived(occupancy)`; the reducer (`:94`) only uses it to set `setupMismatch` *when the buffer is empty*. For a live grid S-07 needs the latest occupancy retained at all times → add a `latestOccupancy: Long?` field updated on every `SnapshotReceived`.

## Code References

- `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayContract.kt:26` — `PhysicalPlayState` (`Playing` flags incl. `rejection`, `setupMismatch`, `eventsSinceConfirm`)
- `…/presentation/physical/PhysicalPlayContract.kt:78` — `RejectionReason` enum (no `INCONSISTENT`)
- `…/presentation/physical/PhysicalPlayContract.kt:94` / `:167` — `PhysicalMsg` / `PhysicalEffect` (`Send(BoardCommand)` is the diagnostic channel)
- `…/presentation/physical/PhysicalPlayReducer.kt:242` — `confirm()` (reject arms at `:266`/`:276`)
- `…/presentation/physical/PhysicalPlayReducer.kt:94` — `SnapshotReceived` → `setupMismatch` occupancy compare (the inconsistency seam)
- `…/presentation/physical/PhysicalPlayViewModel.kt:152` — `commitMove()` §6.2 write gate; `:220` snapshot mapping; `:116` load via `parsePgn`
- `…/presentation/physical/PhysicalPlayScreen.kt:198` — `BoardMessage` rejection/paused/setup banner; `:226` `rejectionText`
- `…/domain/board/SequenceInterpreter.kt:37` — `resolvePhysicalMove`; `:127`–`176` footprint geometry; `:184` `classifyNoMatch`
- `…/domain/board/Resolution.kt:14` — sealed result; `:31` Ambiguous "unreachable" note
- `…/domain/board/Occupancy.kt:21` — `Position.toOccupancy(): Long` (reuse); `:16` h8 sign-bit gotcha
- `…/domain/board/BoardEvents.kt:38` — `BoardSnapshot.occupancy` + `isOccupied`
- `…/domain/board/BoardCommand.kt:9` — `SetMode(BoardMode.DIAGNOSTIC)` (defined, unused by app)
- `…/domain/board/BoardConnection.kt:1` — the port (S-09 BLE swap point)
- `…/domain/chess/Move.kt:27` — `IllegalReason` "deferred to S-07 diagnostics"
- `…/domain/chess/Position.kt:20` — `board: List<Piece?>`, `pieceAt`
- `…/data/board/emulator/EmulatedBoard.kt` — diagnostic job, `setOccupancy`, command handling
- `…/data/board/protocol/BoardWireCodec.kt` — §1.3/§1.4 wire layout
- `…/App.kt:81` + `…/Routes.kt:37` — physical-play nav gate
- `…/platform/PlatformCapabilities.wasmJs.kt:4` — `supportsPhysicalBoard = false`
- `…/presentation/board/ChessBoardView.kt:103` — board signature (no occupancy grid)
- `…/presentation/play/EndGamePicker.kt:32` — two-step irreversibility dialog
- `…/presentation/replay/ReplayScreen.kt:127` — `BoxWithConstraints` 840.dp two-pane precedent
- `docs/reference/contract-surfaces.md` §1.3/§1.4/§1.6/§1.7, §6.1/§6.3 — message catalog, diagnostic mode, reconnect-mismatch, invariants

## Architecture Insights

- **The MVI is intentionally extensible.** `Playing` is a flag-bag, not a sub-state tree; the plan says it "grows monotonically across S-07 (paused / diagnostic / restoring states + outbound `SetMode`/`RequestSnapshot`)". S-07 should add fields + intents + reducer arms, not restructure.
- **Two layers of "does the board match?"** (a) *relative* — `resolvePhysicalMove` over lift/place deltas (answers "can these deltas be one legal move?"); (b) *absolute* — `snapshot.occupancy == positions.last().toOccupancy()` (answers "is the whole board where it should be?"). FR-010's "inconsistent" and the restore-verification are both **absolute** questions → built on the existing `setupMismatch` compare, not on the interpreter.
- **The reducer stays pure.** Occupancy compares and footprint derivations are pure domain functions; diagnostic decisions flow through intents/effects, never side-channel mutation. S-07 must preserve this (it's a manual-verification gate in S-06).
- **Wire-fidelity is already enforced** (emulator round-trips every event through the codec), so verification done on the emulator transfers to real firmware — the load-bearing F-02 property the whole physical stream depends on.
- **One unified "acceptance blocked" predicate** is the clean model: today `paused` = DISCONNECTED only; S-07 adds rejection/recovery as additional blockers. Consider a single derived `acceptanceBlocked` to avoid scattering the gate.

## Forward constraints (S-08 / S-09) — notes only

*(Scope choice: flag these without deep-diving the slices.)*

- **S-08 (resume-after-restart, FR-013) reuses S-07's recovery loop wholesale.** On resume the app renders the expected position (`positions.last()`) and must confirm the physical board matches before re-enabling moves; mismatch routes into *the same* diagnostic-restore path. **Design S-07's "verify occupancy == expected → else enter diagnostics/restore" as a reusable transition, not inlined into the reject path only.** The `setupMismatch` field + occupancy compare is the shared primitive.
- **S-09 (real board over BLE) must render the *same* diagnostics grid + rejection contract on hardware.** Keep the diagnostics UI consuming only the contract-level `BoardSnapshot.occupancy` (never emulator internals) so it transfers unchanged when `BoardConnection` is swapped from `EmulatedBoard` to the BLE adapter. The h8 sign-bit handling and the §1.3 byte layout are the faithfulness-critical details.
- **Real reed hardware is noisy/imperfect** (the entire reason FR-010/FR-011 are must-have). On the emulator, occupancy is always "true", so the recovery path is exercised by *illegal/inconsistent sequences* and by `setOccupancy` in tests — not by sensor noise. S-09 will add real noise; S-07's grid + restore-verify is what makes that survivable. Do not bake emulator-perfection assumptions into the UX copy or the gate.
- **Carry-over risk from S-06 impl-review (F1, LOW/pending):** `single<BoardConnection>` is created with a scope that is never cancelled. Harmless for the emulator; a real leak once the **S-09** BLE adapter holds connections. Worth resolving when the adapter lands; note it, don't necessarily fix in S-07.
- **FR-012 (BLE disconnect/reconnect, parked/nice-to-have)** also routes a reconnect *mismatch* into this exact diagnostic-restore path (contract §1.7, §6.1). Out of scope here by choice, but S-07's recovery loop is the thing it would later reuse.

## Historical Context (from prior changes)

- `context/changes/physical-capture-emulated/plan.md:101` — S-06 explicitly deferred to S-07: *"No diagnostics / guided recovery (S-07): no live reed-switch grid (FR-011), no `SetMode(DIAGNOSTIC)` UI, no step-by-step restore flow. Rejection is minimal (message + no save)."* And `:348` — the reducer was shaped "to grow into S-07".
- `context/changes/physical-capture-emulated/reviews/impl-review.md` — approved 2026-06-19; F1 forward-looking `BoardConnection`-scope note (ties to S-09).
- `context/changes/physical-capture-emulated/manual-verification.md` — reducer IO-freedom + MVI-justification gates (preserve these properties in S-07).
- `context/changes/reed-board-emulator/research.md` + `/change.md` (status `impl_reviewed`, 2026-06-17) — F-02 contract; emulator + `BoardScenarios` "promote unchanged to commonMain" when S-06 wires the first consumer.
- `context/changes/chess-rules-engine/` — `Move.kt:27` `IllegalReason` hand-off: finer rejection reasons "deferred to S-07 diagnostics".
- `context/foundation/lessons.md:57` (MVVM default; MVI only for event-heavy screens, justified in plan — S-06 used MVI), `:107` (Nav3 committed library), `:187` (SYNC-comment move geometry — relevant if S-07 adds a second footprint consumer), `:17` (web is digital-only — no physical/diagnostics on wasm).
- `context/foundation/roadmap.md:194` — S-07 definition; Stream C (`F-02 → S-06 → S-07 → S-08 → S-09`).

## Related Research

- `context/changes/reed-board-emulator/research.md` — emulator injection layer & "dumb board" model (the data source for FR-011).
- `context/changes/firmware-ble-gatt-service/research.md` — firmware side of the §1 BLE contract (the S-09 hardware peer that must match this slice's diagnostics/rejection contract).

## Open Questions (for `/10x-plan` to resolve)

1. **Where does "inconsistent" get classified?** Recommended: a reducer/VM-level *absolute* check (`snapshot.occupancy != positions.last().toOccupancy()` while the buffer is non-trivial) → `RejectionReason.INCONSISTENT`, leaving the interpreter delta-only. Alternative: push an absolute-snapshot input into the interpreter (bigger change). Decide.
2. **Pause model:** add a dedicated `recovering`/`diagnosticMode` flag (or sub-state) to `Playing`, plus a unified `acceptanceBlocked` predicate that ORs disconnect + recovery? Or keep `rejection` transient and gate only on a new flag?
3. **Auto vs manual diagnostics entry.** FR-010 says "ask the player to manually restore … with diagnostic assistance"; contract §1.7 says a *reconnect/setup mismatch* "automatically enter[s] diagnostic mode". Likely split: **reject → banner + user-tapped "Show diagnostics"**; **setup/reconnect mismatch → auto-enter**. Confirm.
4. **Restore-verification gate.** Require observed occupancy `==` `positions.last().toOccupancy()` before allowing the retry confirmation (enforces "restore the previous legal position")? Recommended yes. Define the exact re-enable transition (and `SetMode(GAME)` exit).
5. **Diagnostics grid content.** Observed-only, or observed-vs-expected (highlight the squares that differ)? Observed-vs-expected makes "restore" far easier and reuses `toOccupancy()` — recommended, still "raw" (no step-by-step guidance, so within the MVP non-goal).
6. **Ambiguous:** keep `Resolution.Ambiguous` as defensive/dead, or retire it? (It's currently unreachable.)
7. **`IllegalReason` granularity:** surface *why* a move was illegal (not-your-piece/blocked/in-check), or keep the generic "not legal — restore & retry" + the reed grid? MVP raw-diagnostics guardrail suggests the latter; confirm.
8. **Emulator fault-injection for tests.** To exercise recovery deterministically, do we drive `lift/place`/`setOccupancy` to fabricate inconsistent boards in tests (no production emulator change), or add a small dev affordance? Likely tests only.
9. **Adaptive layout:** ship phone-first (board over grid), or add the `840.dp` two-pane like Replay? Optional; physical is mobile-only.

## Proposed seams / insertion points (for the plan, not yet code)

- **State (`PhysicalPlayContract.kt`)**: add `latestOccupancy: Long?` (live grid), a recovery flag (e.g. `diagnosticMode: Boolean` / `recovering: Boolean`), and `RejectionReason.INCONSISTENT`.
- **Intents**: add `ShowDiagnostics` / `HideDiagnostics` (user), `RestoreAndRetry` (user), and route `SnapshotReceived` to always update `latestOccupancy`.
- **Effects**: emit `Send(BoardCommand.SetMode(DIAGNOSTIC))` on entering, `Send(BoardCommand.RequestSnapshot)` to prime, `Send(BoardCommand.SetMode(GAME))` on exit/resume.
- **Reducer**: fork `Illegal` vs new `Inconsistent` (via absolute compare); add the recovery gate so `confirm()` is blocked while recovering until occupancy matches expected; clear recovery on verified restore.
- **ViewModel**: handle the new `Send` effects; keep the reducer pure (compares are pure domain calls).
- **UI**: new `ReedDiagnosticsGrid(observed: Long, expected: Long?)` composable (respect h8 sign-bit); extend `BoardMessage` with a "Show diagnostics" / "Restore & retry" affordance; reuse `EndGamePicker`'s two-step dialog only if a confirm is wanted.
- **Reuse, don't rebuild**: `Position.toOccupancy()`, `BoardSnapshot.isOccupied`, `SetMode`/`RequestSnapshot`, the emulator diagnostic stream, the `setupMismatch` compare.
