---
date: 2026-07-04T12:30:00+0200
researcher: Rafał Urbaniak
git_commit: d7cee0ce3c9a4c6da683a678ba21ba0e5edc94ad
branch: main
repository: smartchessboard (github: Rafau-18/SmartChessboard)
topic: "Physical-record integrity (test-plan Phase 2) — acceptance pipeline, durability pipeline, oracle surfaces, and coverage gaps for risks #1 and #2"
tags: [research, codebase, testing, physical-board, journal, autosaver, pgn, sequence-interpreter, record-integrity, s-06, s-08, s-09]
status: complete
last_updated: 2026-07-04
last_updated_by: Rafał Urbaniak
last_updated_note: "Open Questions 1–2 decided in session: hybrid oracle (fixtures + occupancy invariants), tests + fixes scope."
---

# Research: Physical-record integrity (test-plan Phase 2)

**Date**: 2026-07-04T12:30:00+0200
**Researcher**: Rafał Urbaniak
**Git Commit**: d7cee0ce3c9a4c6da683a678ba21ba0e5edc94ad
**Branch**: main
**Repository**: smartchessboard (github: Rafau-18/SmartChessboard)

Permalink base for all code references below:
`https://github.com/Rafau-18/SmartChessboard/blob/d7cee0ce3c9a4c6da683a678ba21ba0e5edc94ad/`

Path shorthand: `SRC` = `SmartChessboard/shared/src`; unqualified paths are under
`SRC/commonMain/kotlin/org/rurbaniak/smartchessboard/`; test paths under
`SRC/commonTest/kotlin/org/rurbaniak/smartchessboard/`.

## Research Question

Ground test-plan §2 risks #1 and #2 for rollout Phase 2
(`context/foundation/test-plan.md` §3, this change folder):

- **#1** — a physical-board game is silently recorded with moves that differ from
  what was actually played. Ground: acceptance → journal → cloud handoff points,
  the reconnect-reconcile gate, and a map of what existing emulator E2E already
  proves. Challenge "emulator-green implies board-green" and "accepted move equals
  persisted move". Avoid the implementation-oracle anti-pattern.
- **#2** — moves the player saw accepted are lost after crash/kill/offline,
  including the end-of-game result never reaching the cloud. Ground: journal write
  points and flush/retry windows, reconcile-on-load behavior, which failures are
  swallowed vs surfaced. Challenge "final status 200 means saved" and "the next
  move re-triggers sync". Avoid testing only graceful shutdown.

## Summary

**The local acceptance/durability core is strong and already well-tested.** The
§6.2 invariant — a move is "accepted" only after a *synchronous* journal write
returns; UI advances only on that success; cloud sync is asynchronous, best-effort,
never the only copy — is implemented as documented and covered by ~40 focused
tests (20 in `GameAutoSaverTest`, 4 physical E2E suites, 25 interpreter tests).

**Risk #1 exposure concentrates in the oracle problem, not in missing scenario
coverage.** Captures (both orderings), castling (three orderings), en passant,
promotion, reconnect- and resume-mid-game are all E2E-covered with PGN round-trip
assertions — but the round trip (`writePgn` → `parsePgn`) shares `ChessRules` as a
single trusted base on both sides, so an engine bug is invisible to it. The only
truly independent in-process oracle is the occupancy bitmap (sensed vs derived);
the only independent PGN oracle is a hand-written expectation or an external
verifier.

**Risk #2 exposure concentrates in five concrete, code-verified windows** (all
confirmed by direct read at this commit, §5 below):

1. Cloud re-flush is *open-this-game-triggered only* — no global/startup flush
   exists (journal has no enumeration API), and a physical game's reconcile
   additionally requires a successful BLE connect.
2. A zero-row UPDATE (row deleted elsewhere / RLS drift) returns 2xx success;
   `sync` then `journal.clear`s a *finished* entry — the only copy of the result
   is destroyed. This is the concrete "status 200 means saved" falsifier.
3. The physical *finish* path inverts §6.2: the reducer freezes the UI with the
   result **before** the journal write effect runs; a manual finish's journal
   write is unguarded (throw propagates), an auto-close (mate) failure is masked
   as `MoveRejected` on an already-finished state.
4. `reconcile` calls `sync` inline; for a *finished* dirty entry while offline the
   terminal retry never returns → the game screen sits in Loading forever.
5. Digital accept/finish journal writes are unguarded on the tap path (a wasm
   localStorage-quota throw would crash).

## Detailed Findings

### 1. Acceptance pipeline (risk #1): board event → accepted move → persisted PGN

**Ingestion.** The move-acceptance port is `BoardConnection`
(`domain/board/BoardConnection.kt:18-27`): `connectionState:
StateFlow<BoardConnectionState>` (CONNECTED/DISCONNECTED only),
`events: SharedFlow<BoardEvent>` (hot, **no replay** — late subscribers miss the
on-connect burst), `send(command)` throwing when disconnected. Transport lifecycle
(scan/connect/reconnect, 10-state machine) is a separate face, `BoardTransport`
(`domain/board/BoardTransport.kt:18-73`), deliberately outside the acceptance gate;
one adapter object implements both.

Event types (`domain/board/BoardEvents.kt:11-61`): `SquareEvent` (LIFT/PLACE),
`ButtonEvent` (WHITE/BLACK confirm), `BoardSnapshot(occupancy: Long)` — full 64-bit
bitmap, a1=bit 0, h8=bit 63 (sign bit!), emitted on every (re)connect, on request,
and at ~10 Hz in DIAGNOSTIC mode — and `DeviceStatus`. Commands: `SetMode(GAME |
DIAGNOSTIC)`, `RequestSnapshot`, `RequestStatus` (`domain/board/BoardCommand.kt:8-25`).
The board resets to GAME on every reconnect (§1.7).

Implementations: `EmulatedBoard` (`data/board/emulator/EmulatedBoard.kt:56-232`) —
every emitted event round-trips typed → §1.3 bytes → typed through `BoardWireCodec`
(`emitEvent`, :187-201), so the emulated stream is byte-identical to firmware's;
offline lift/place mutate occupancy silently (divergence surfaces only in the
reconnect snapshot); `setOccupancy` only while disconnected (:136-142). Real BLE:
`BleBoardAdapterCore` (`data/board/ble/BleBoardAdapterCore.kt:36-92`, commonMain,
Kable-free) + `KableBoardAdapter` platform halves (`androidMain`/`iosMain`,
byte-parallel); `BleMapping.mapNotification` **drops malformed frames (returns
null)** (`data/board/ble/BleMapping.kt:25-29`); `BoardWireCodec` decoding is total —
malformed → `Malformed(bytes, reason)`, never a throw
(`data/board/protocol/BoardWireCodec.kt:52-99`).

**Resolution.** `SequenceInterpreter.resolvePhysicalMove(position, events)`
(`domain/board/SequenceInterpreter.kt:37-64`) folds the lift/place stream into an
`Observed` signature (vacated / arrived / liftedReoccupied sets, starting from
`position.toOccupancy()`), then filters `legalMoves(position)` by
`footprintOf(position, it).matches(observed)`. Matches group by `(from, to)` so
four promotion moves collapse to one `NeedsPromotion`. Outcomes
(`domain/board/Resolution.kt:14-44`): `Resolved` (move taken **straight from
`legalMoves`** — the resolver never fabricates a move), `NeedsPromotion`,
`Ambiguous` (documented as defensively unreachable under a full stream), `Illegal`,
`Incomplete`. Matching is exact set equality on vacated/arrived; capture
destinations need only be a *subset* of liftedReoccupied (j'adoube/noise tolerance,
:132-137). `footprintOf` (:139-176) hand-mirrors `applyMove`'s castling/en-passant
geometry — SYNC-commented both sides (`SequenceInterpreter.kt:124-125` ↔
`domain/chess/ChessRules.kt:78-79`); silent-drift risk if either changes alone.

**Acceptance moment.** MVI: `dispatch` = reduce → publish state → run effects
(`presentation/physical/PhysicalPlayViewModel.kt:131-135`). For one accepted move:

1. Confirm button → `confirm()` (`presentation/physical/PhysicalPlayReducer.kt:339-404`).
   Guards: frozen/paused no-op; gated confirm only re-pulls a snapshot (:348-350);
   wrong-side confirm silent no-op (chess-clock semantics, :351); unpicked
   promotion → `PROMOTION_REQUIRED` (:352).
2. `Resolved` → effect `CommitMove(position, sanMoves, move)` — **state does not
   advance yet**.
3. `commitMove` (`PhysicalPlayViewModel.kt:182-201`, in `viewModelScope.launch`):
   re-`validate` → `sanForMove` → `autoSaver.acceptMove(gameId, writePgn(meta,
   sanSoFar + san))` (**the §6.2 gate — synchronous, non-suspend journal write**)
   → `dispatch(MoveCommitted)` → `launch { autoSaver.sync(gameId) }` (cloud,
   fire-and-forget). A journal-write throw feeds back `MoveRejected(SAVE_FAILED)`
   (:197-198) and the state never advances.
4. `MoveCommitted` → `commit()` (`PhysicalPlayReducer.kt:411-440`): append
   position/SAN, recompute status, clear gates, `syncPending = true`;
   mate/stalemate auto-closes via `FinishGame` effect (:434-438).

**Ordering answer (risk #1 "accepted move equals persisted move"):** the durable
local write strictly *precedes* the UI showing acceptance; the *cloud* copy is
asynchronous and lags — "accepted equals persisted" holds for the journal, not
for the cloud. Digital play has the same order but *synchronously on the tap path*
(`presentation/play/PlayViewModel.kt:191 → 261 → 296`, then UI :297-306, then sync
:307).

### 2. Rejection paths: visible vs silent

Visible (banner in fixed slot, `presentation/physical/PhysicalPlayScreen.kt:336-350`,
texts :459-471):

| Trigger | Decision point | Surface |
|---|---|---|
| `Illegal` on confirm, board matches expected | `PhysicalPlayReducer.kt:381-397` | `ILLEGAL` banner + `recovering` + `RequestSnapshot` |
| `Illegal` + fresh occupancy ≠ expected | fork :385-389 | `INCONSISTENT` (FR-010) + `recovering` |
| `Ambiguous` | :370-379 | `AMBIGUOUS` + `recovering` |
| Confirm with unpicked promotion | :352 | `PROMOTION_REQUIRED` |
| Journal write threw | VM :197-198 → reducer :276-285 | `SAVE_FAILED`, state not advanced |
| Board disconnected | `paused` (`PhysicalPlayContract.kt:118`) | banner + Reconnect button |
| At-rest snapshot ≠ expected | reducer :151 | `setupMismatch` banner + auto-opened diagnostics grid |

Silent (no user-visible signal): `Incomplete` on confirm (j'adoube, piece in hand —
reducer :400-402); wrong-side confirm (:351); lift/place while gated **not
accumulated at all** (:310-312, treated as restoration motion); reed blips absorbed
by subset matching; malformed BLE frames dropped (`BleMapping.kt:25-29`);
`DeviceStatus` mapped to null (`PhysicalPlayViewModel.kt:257-259`); events during
`Loading` dropped (reducer :85-89, mitigated by post-load `RequestSnapshot`);
`send()`/manual-reconnect failures swallowed (VM :215-224, :117-128); cloud-sync
failure silent except the 600 ms-debounced "Saving…" indicator
(`presentation/components/SyncIndicator.kt:26-51`).

**Gap note:** `awaitingResumeConfirm` and `reconnectReconciling` have **no
dedicated banner** — while the board matches, the gate window is invisible; a
mismatch surfaces only indirectly via `setupMismatch`.

### 3. Resume / reconnect reconcile gates (couples #1 ↔ #2)

All three gates compare the board's absolute occupancy to
`position.toOccupancy()` (`domain/board/Occupancy.kt:21-27` — bit N set iff a piece
sits on square N; **color/type deliberately discarded**; expected position is
always the in-memory `positions.last()` — no FEN reader ships in production).

- **Resume (S-08, FR-013):** `load()` (`PhysicalPlayViewModel.kt:146-173`) →
  `getGame` → `autoSaver.reconcile(record)` → `parsePgn` off-main — the expected
  position is **rebuilt by replaying the persisted PGN**. The `Loaded` reducer arm
  (`PhysicalPlayReducer.kt:41-79`) arms `awaitingResumeConfirm` for in-progress
  games and emits `RequestSnapshot` if connected.
- **BLE reconnect (S-09, FR-012):** `BoardConnected` arm (:103-122) sets
  `reconnectReconciling = true` **on every (re)connect** + `RequestSnapshot`
  (+ re-arms DIAGNOSTIC if the grid is open — §1.7 mode reset). Below the port the
  Kable adapter auto-reconnects with backoff (1 s → 10 s cap, 6 attempts).
- **Shared clearing seam — `SnapshotReceived`** (:128-162): `atRest =
  eventsSinceConfirm.isEmpty()`; `matchesExpected = msg.occupancy ==
  position.toOccupancy()` (exact equality). Match → clears
  `recovering`/`awaitingResumeConfirm`/`reconnectReconciling`. Mismatch at rest →
  `setupMismatch` + auto-grid; the gate holds and every later snapshot re-runs the
  check. While any gate is set, `acceptanceBlocked` (`PhysicalPlayContract.kt:125`)
  blocks accumulation and confirms.

### 4. Durability pipeline (risk #2): journal → autosaver → cloud

**Journal.** Port `GameJournal` + `JournalEntry(pgn, dirty, result)`
(`domain/games/GameJournal.kt:9-36`; save "must persist across process death
before returning"). Impl `SettingsGameJournal`
(`data/journal/SettingsGameJournal.kt:16-61`): three keys per game
(`journal.<id>.pgn`/`.dirty`/`.result`), written **dirty → result → pgn** — pgn
last because `load` gates presence on the pgn key, so a mid-save crash can only
re-flush the *prior* document. No transaction (three independent `Settings` puts).
Platform backends (multiplatform-settings 1.3.0): Android
`SharedPreferencesSettings(..., commit = true)` — synchronous commit chosen
against process-death loss (`androidMain/.../di/PlatformModule.android.kt:23-35`);
iOS `NSUserDefaultsSettings` (`iosMain/.../di/PlatformModule.ios.kt:25`); wasm
`StorageSettings()` = localStorage (`wasmJsMain/.../di/PlatformModule.wasmJs.kt:8-11`).

**GameAutoSaver** (`domain/games/GameAutoSaver.kt`) — Koin `factory`, one per game
screen; `sync` runs in the calling ViewModel's scope, so **navigating away cancels
any in-flight/retrying flush**. No debounce: every accepted move launches `sync`
immediately.

- `acceptMove` (:32-38) / `finishGame` (:46-53): synchronous `journal.save(...,
  dirty = true)`; `finishGame` adds `result`.
- `sync` (:60-102): re-loads the journal each iteration (retries always upload the
  freshest PGN); bounded retry `[1s, 2s, 4s]` (4 attempts) for in-progress
  entries — give-up at :97 leaves the entry dirty, the next move's sync or
  reconcile re-enters; **a finished entry never gives up** (backoff capped at 4 s)
  until it lands or the coroutine is cancelled; after upload, `journal.load(id)?.pgn
  == entry.pgn` (:79) refuses mark-synced if a newer move superseded mid-flight;
  success → `markSynced` (in-progress) or **`clear`** (finished, :80);
  `catch (Throwable)` with CancellationException rethrow (:84-86).
- `reconcile` (:111-121): journal wins only if `dirty && isAhead` (movetext
  strictly extends cloud's, or same moves but journal finished what cloud left
  open, :131-143); then **`sync` is awaited inline** and the journal PGN plays.
  Everything else → cloud wins, journal overwritten clean (LWW). Exactly two call
  sites: `PlayViewModel.load` (:150) and `PhysicalPlayViewModel.load` (:152) —
  **not** History, not Replay, not app startup. `GameJournal` has no
  key-enumeration API, so no global flush is even possible.

**Cloud handoff** (`data/games/SupabaseGamesRepository.kt`): `createGame` is
verified (insert + select + `decodeSingle`, :83-104). `updatePgn` (:106-115) and
`finishGame` (:118-135) are bare PostgREST UPDATEs filtered `eq("id", id)` — **no
select, no affected-row check**. supabase-kt throws on non-2xx, but a **zero-row
match returns 2xx success** (the `deleteGame` comment :137-139 relies on exactly
this semantics for idempotency). `finishGame` is atomic (status + result + PGN in
one UPDATE). Auth: PKCE with default auto-refresh; no auth-specific handling in
the save pipeline — an expired session surfaces as a throw → retried like a
network blip.

### 5. Failure-handling audit & crash/kill windows (code-verified)

The three highest-impact claims below were re-verified by direct read in the main
research context at this commit; the rest carry agent-verified file:line refs.

| # | Window / site | Behavior at this commit | Class |
|---|---|---|---|
| W1 | UI-accept → journal write | Does not exist for *moves* (§6.2: write precedes UI advance, both modes) | protected |
| W2 | **Physical finish ordering** | `dispatch` publishes the frozen/finished state (:131-135) **before** the `FinishGame` effect journals (:208-213). Manual finish: `autoSaver.finishGame` **unguarded** — a journal throw propagates after the UI already shows finished. Auto-close (mate): the effect runs inside `commitMove`'s try → caught as `MoveRejected(SAVE_FAILED)` on an already-finished state — masked, result stays displayed, nothing journaled | **defect: inverted §6.2 + unguarded/masked** |
| W3 | Journal write, digital accept/finish | `acceptMove` at `PlayViewModel.kt:296` and finish at :374 sit unguarded on the synchronous tap path — a Settings throw (e.g. wasm localStorage quota) crashes | **defect: unguarded** |
| W4 | Journal write → cloud sync | Nothing lost locally; cloud catches up **only when this device re-opens this game via Play/PhysicalPlay** (the only reconcile sites). Physical re-flush additionally requires a successful BLE connect (History routes physical in-progress via ConnectionKey, `App.kt:130-148, 193-203`); a dead board strands a dirty journal indefinitely; other devices/web see stale cloud PGN | exposure (by design, mitigations absent) |
| W5 | **Zero-row UPDATE false success** | Row deleted from another device / RLS drift → `updatePgn`/`finishGame` match zero rows, return success → `sync` does `markSynced`, or for finished **`journal.clear`** (:80) — the only copy of moves/result is destroyed | **defect: destructive silent success** |
| W6 | **Finished dirty entry + offline at load** | `reconcile` awaits `sync` inline (:114); terminal retry never returns (:97 guard skips finished) → `load()` never dispatches → screen stuck in Loading until connectivity or exit | **defect: unbounded await on load path** |
| W7 | Crash between result-key and pgn-key writes (finish) | Journal left as *old pgn + result + dirty*; on next load `isAhead` sees identical movetext + non-finished journal pgn → not ahead → cloud wins, entry overwritten clean — **the finish evaporates silently**. Narrow (ordering makes it two puts wide); digital UI freeze happens only after save returns, physical shows finished already (W2) | sub-window |
| W8 | Sync failure | Silent by design (`catch Throwable`); only signal is "Saving…" ≥600 ms; bounded give-up leaves dirty + indicator raised | by design |
| W9 | Promotion at kill | Digital: pawn move not journaled until picked — only the un-accepted move lost. Physical: in-memory `pendingPromotion`/`eventsSinceConfirm` lost; FR-013 resume gate then blocks until board restored — consistent | protected |
| W10 | Mid-BLE-reconnect | Acceptance blocked while DISCONNECTED and until post-reconnect snapshot matches; journaled moves untouched; offline board moves surface as `setupMismatch` | protected |
| W11 | Android `commit()` returning false | `Settings.putString` returns Unit — a false commit is unobservable (journal claims durability it didn't get) | theoretical |

CancellationException is correctly rethrown at every catch-Throwable site
(GameAutoSaver :84-85; PlayViewModel :169-170; PhysicalPlayViewModel :166-167,
:195-196, :219-220).

### 6. Oracle surfaces & the implementation-oracle problem

Independent representations of "what was played" available to tests:

| # | Representation | Producer | Independence |
|---|---|---|---|
| 1 | Occupancy bitmap (sensed) | firmware / `EmulatedBoard.occupancy` | **fully independent of the engine** |
| 2 | Occupancy bitmap (derived) | `Position.toOccupancy()` | derived from #3 |
| 3 | `Position` list | `ChessRules.applyMove` (via `validate` / `parsePgn`) | the engine itself |
| 4 | SAN strings | `SanWriter.sanForMove` | grammar independent, legality via engine |
| 5 | Persisted PGN | `PgnWriter.writePgn` | composition of #4 |
| 6 | PGN replay | `PgnParser.parsePgn` | grammar independent, legality + application via engine |
| 7 | FEN (write-only) | `Position.toFen()` | no in-app reader — external check only |

**Confirmed, with nuance:** `sanForMove` and `parsePgn` have *independently
implemented SAN grammars* (generation: `SanWriter.kt:49-92`; regex-free
decomposition: `PgnParser.kt:176-254`) — grammar-level divergence IS caught by
round-trip. But **both delegate legality and position evolution to the same
`ChessRules`** (`SanWriter.kt:30, 81`; `PgnParser.kt:20-22, 65, 180, 190`), and
`PgnWriter.kt:21-24` documents `parsePgn(writePgn(...))` as an identity. An
engine bug (move application, legality) is invisible to any write→parse round
trip. `SequenceInterpreter.footprintOf` is a *second* hand-mirrored encoding of
move geometry — a drift risk, but also a testable cross-check: for every legal
move, `footprintOf`'s vacated/arrived sets must be derivable from
`toOccupancy(position)` vs `toOccupancy(applyMove(position, move))`.

Truly engine-independent oracles: (a) the sensed occupancy bitmap vs derived
occupancy — exactly what the gates already compare, exploitable per-move in tests;
(b) hand-written expected PGN/positions (the author as oracle — the existing
`PgnParserTest` famous-games pattern); (c) an external chess library/engine
verifying PGN/FEN (JVM test-only dependency). Perft (`PerftTest`) already pins the
move generator against published node counts — an external, engine-independent
corpus for `legalMoves`, but not for `applyMove`-in-context, SAN, or persistence.

### 7. Existing coverage map & gaps

**What existing tests already prove** (all commonTest, run on 3 targets unless
noted; `uitest/` excluded from the Android host task):

- `presentation/physical/PhysicalCaptureEndToEndTest.kt` (4 tests): full scripted
  game with captures in both physical orderings + interleaved castle → canonical
  PGN; en passant resolves and round-trips; promotion picker-gated (confirm before
  pick saves nothing); mating move auto-closes with result token. Asserts via
  `sanMoves`, `parsePgn` equality, result tokens.
- `presentation/physical/PhysicalRecoverEndToEndTest.kt` (2): ILLEGAL and
  INCONSISTENT forks pause the game, restore clears via snapshot match, a legal
  retry journals **exactly one** dirty entry (`saveLog.count { dirty }`).
- `presentation/physical/PhysicalResumeEndToEndTest.kt` (3): matching board
  auto-resumes with no move lost; mismatch blocks until restored; promotion
  lifted-in-hand at kill is never accepted, 8 prior moves survive.
- `presentation/physical/PhysicalReconnectEndToEndTest.kt` (2): reconnect
  match auto-resumes (no SetMode); offline board change blocks until restored.
- `domain/board/SequenceInterpreterTest.kt` (25): all move shapes, all orderings,
  j'adoube noise, Illegal/Incomplete classification, near-ambiguity resolution.
- `domain/games/GameAutoSaverTest.kt` (20): §6.2 gate, bounded vs terminal retry,
  mid-flight supersede, offline reconcile (in-progress), cloud-wins LWW branches,
  stale-dirty self-heal, finished re-flush ("crash window" tests).
- `data/journal/SettingsGameJournalTest.kt` (8): key round-trips over MapSettings.
- Engine: `ChessRulesTest`, `PerftTest` (published counts to depth 4 + Kiwipete),
  `FenTest`, `PgnParserTest` (famous games, `finalPieceAt` hand-expectations),
  `SanWriterTest`, `PgnWriterTest` (round-trip identity).
- Board/wire: `EmulatedBoardTest`, `EmulatedBoardEndToEndTest` (byte-level codec
  round-trip through the port), `BoardWireCodecTest`, `BleMappingTest`.

Test seams available: `FakeGameJournal` (`saveLog: List<Triple<gameId, pgn,
dirty>>`, `syncedIds`, `clearedIds`); `FakeGamesRepository` (`updatePgnFailures`
countdown fault injection, `onUpdatePgn` mid-flight callback, call logs);
`EmulatedBoard(initialOccupancy=...)` + `setOccupancy` (disconnected only) +
`BoardScenarios` DSL (capture/castle/enPassant orderings, `adjust` j'adoube);
`RecordingBoardConnection` wrapper (asserts SetMode traffic); virtual-time
discipline from lessons.md (`runCurrent` for the connect burst, `tick()` =
150 ms + runCurrent for diagnostic snapshots, `advanceUntilIdle` only while no
stream is armed).

**Gaps (what Phase 2 must add):**

*Risk #1:*
- G1. **Independent-oracle round-trip invariant**: scripted physical games asserted
  against *hand-written* expected PGN (not `writePgn` output) and/or an external
  verifier; per-move occupancy invariant (sensed board bitmap == derived bitmap of
  the recorded position after every accept).
- G2. **`footprintOf` ↔ `applyMove` cross-check property**: for every legal move in
  a position corpus, footprint sets must equal the occupancy diff of `applyMove` —
  turns the SYNC-comment coupling into a compiler-adjacent guard.
- G3. **Adversarial board streams**: phantom lifts/places, bit-flipped snapshots,
  garbage injected at the codec/port seam during GAME mode — assert visible
  rejection or no-op, never a divergent accepted move (challenges "emulator-green
  implies board-green" at the automatable floor).
- G4. Divergence-during-gap scenarios: board moves made while disconnected that
  *happen to* produce a legal-looking stream after reconnect; second capture of an
  already-captured square.

*Risk #2:*
- G5. **Zero-row UPDATE false success** (W5): fake repository returning
  silent-zero-row success → assert the journal must NOT be cleared/marked synced
  (red against current code).
- G6. **Physical finish ordering** (W2): journal throw on manual finish and on
  auto-close — assert no frozen-finished UI without a journaled result (red).
- G7. **Reconcile offline hang** (W6): finished dirty entry + repository throwing
  → `load()` must complete (bounded) into a playable/error state (red).
- G8. **Unguarded digital journal writes** (W3): throwing journal on the tap path
  → graceful rejection, no crash (red).
- G9. Kill-window simulation at the journal-write boundary: partial three-key
  writes (dirty landed, pgn didn't — W7) → reconcile outcome asserted; needs a
  fake Settings that fails/stops between puts.
- G10. Cloud-flush reachability: finished-but-unsynced game whose screen closed
  before the flush landed — currently nothing re-flushes until the game is
  re-opened on this device (W4); test documents the floor, plan decides whether a
  History-level or startup reconcile is in scope.

*Explicitly out of scope (test-plan §7):* real process-kill/platform persistence
(SharedPreferences/NSUserDefaults/localStorage actual disk behavior), real-radio
BLE, multi-device concurrent editing (no conflict protocol exists — LWW).

## Code References

Permalink base: `https://github.com/Rafau-18/SmartChessboard/blob/d7cee0ce3c9a4c6da683a678ba21ba0e5edc94ad/`

- `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt:32-38,60-102,111-121` — §6.2 gate, sync retry policy, reconcile (W5/W6 verified here)
- `.../domain/games/GameJournal.kt:9-36` — journal port, durability contract, no enumeration API
- `.../data/journal/SettingsGameJournal.kt:19-41,56-60` — dirty→result→pgn ordering, key scheme
- `.../presentation/physical/PhysicalPlayViewModel.kt:131-135,146-173,182-213` — dispatch ordering, load/reconcile, commitMove, finishGame (W2 verified here)
- `.../presentation/physical/PhysicalPlayReducer.kt:41-79,103-162,310-323,339-440` — Loaded/BoardConnected/SnapshotReceived gates, accumulate, confirm, commit
- `.../presentation/physical/PhysicalPlayContract.kt:20-27,117-128,151-152` — state-advance guarantee, acceptanceBlocked, SAVE_FAILED semantics
- `.../presentation/play/PlayViewModel.kt:191,261,296-307,374` — digital tap-path accept/finish (W3)
- `.../data/games/SupabaseGamesRepository.kt:83-144` — createGame verified; updatePgn/finishGame bare UPDATEs, deleteGame zero-row comment (W5 verified here)
- `.../domain/board/SequenceInterpreter.kt:37-64,124-176` — resolution, footprintOf mirror + SYNC comment
- `.../domain/chess/ChessRules.kt:25-44,69-128` — validate/applyMove (internal), SYNC counterpart
- `.../domain/chess/pgn/SanWriter.kt:25-92`, `.../pgn/PgnParser.kt:12-80,176-254`, `.../pgn/PgnWriter.kt:21-49` — the round-trip pair and its shared-engine base
- `.../domain/board/Occupancy.kt:21-27` — Position → 64-bit occupancy (colorless)
- `.../domain/board/BoardConnection.kt:18-33`, `.../domain/board/BoardEvents.kt:11-61`, `.../domain/board/BoardCommand.kt:8-25` — the port and event/command types
- `.../data/board/emulator/EmulatedBoard.kt:56-232` — emulator, byte-round-trip fidelity, offline mutation
- `.../data/board/ble/BleBoardAdapterCore.kt:36-92`, `.../data/board/ble/BleMapping.kt:25-42` — real transport core, malformed-frame drop
- `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/physical/` — the four E2E suites
- `.../commonTest/.../domain/games/GameAutoSaverTest.kt` — 20 sync/reconcile tests
- `.../commonTest/.../presentation/FakeGameJournal.kt:9,21`, `.../presentation/FakeGamesRepository.kt` — fault-injection seams
- `.../commonTest/.../data/board/emulator/BoardScenarios.kt` — physical-sequence DSL

## Architecture Insights

- **§6.2 is the project's durability spine**: synchronous journal write as the
  acceptance gate, UI advance only on its success, cloud strictly best-effort.
  Digital enforces it inline on the tap path; physical enforces it through the MVI
  effect + `MoveCommitted` feedback. The *finish* paths are the asymmetry: digital
  keeps the order, physical inverts it (W2).
- **The interpreter never fabricates moves** — everything accepted comes from
  `legalMoves`, so "wrong move recorded" can only arise from (a) a wrong-but-legal
  resolution matching a corrupted stream, (b) engine bugs shared with the
  replay path, or (c) divergence during gate windows. That's what makes the
  occupancy invariant (sensed vs derived, after every accept) the strongest cheap
  guard: it catches (a) and (c) mechanically.
- **All reconcile trust flows through movetext prefix comparison** (`isAhead`) —
  string-level, not position-level; headers excluded; finished-ness decided by
  terminator tokens. Tests can attack it with crafted PGN pairs without any board.
- **One-shot, screen-scoped sync**: no background/global flush exists by
  construction (factory-scoped autosaver, no journal enumeration). Any "eventually
  lands in the cloud without further user activity" guarantee is currently limited
  to *the same device re-opening that game* (plus BLE connect for physical).
- The test harness discipline from lessons.md (`runCurrent` vs `advanceUntilIdle`
  around armed diagnostic streams, occupancy injection) is load-bearing for every
  new physical E2E — codified in `context/foundation/lessons.md` and
  test-plan §6.5 (TBD → this phase fills it).

## Historical Context (from prior changes)

- `context/changes/digital-pass-and-play/plan.md:112-121` — §6.2 ordering invariant
  defined (validate → SAN → serialize → synchronous journal write → UI → async
  sync); Android `commit = true` rationale; offline `createGame` explicitly not
  supported (:85-86); S-04 force-quit E2E proved the PGN-rebuild path on 3 targets
  (:488).
- `context/changes/physical-capture-emulated/plan.md:145-160` — §6.2 preserved
  through the MVI effect; interpreter only hands `legalMoves`-sourced moves;
  Phase 5 proved one dirty write per confirm with `[Mode "physical"]` (:591).
- `context/changes/game-end-and-result/plan.md:132-140,262` — offline-safe finish
  ordering (durable journal write carrying the result **before** UI update — note:
  the physical implementation inverted this, W2); reconcile extended to re-flush a
  journal-ahead finished entry.
- `context/changes/physical-resume-after-restart/plan.md:33-36` +
  `research.md:83-104` — resume gate design; emulator start-position trap
  (inject `initialOccupancy`); dirty→result→pgn ordering rationale; terminal-flush
  lesson pointer.
- `context/changes/reject-recover-diagnostics/plan.md:76-77` — `latestOccupancy`
  is stale in GAME mode (snapshot only on connect/request); the INCONSISTENT fork
  must act on a fresh snapshot.
- `context/changes/real-board-over-ble/plan.md:115-118` — reconnect E2E
  `runCurrent` discipline; `reviews/impl-review.md` (2026-07-04, unreviewed at
  research time) — check before planning for any S-09 findings that overlap.
- `context/changes/ble-connectivity-robustness/` (new, unplanned) — real-world BLE
  drop diagnostics; overlaps risk #3, not #1/#2, but its device-matrix findings
  may motivate G3/G4 adversarial vectors.
- `context/foundation/lessons.md` — "terminal flush needs its own retry",
  "mid-game resume E2E must inject occupancy AND settle with runCurrent",
  "SYNC-comment hand-mirrored geometry", "catch(Throwable) at every wasm-reachable
  call site".

## Related Research

- `context/changes/real-board-over-ble/research.md` — BLE transport seams,
  reconnect-reconcile design (S-09)
- `context/changes/physical-resume-after-restart/research.md` — crash-safety model,
  journal write ordering, promotion-at-kill edge
- `context/changes/reject-recover-diagnostics/research.md` — recovery loop,
  absolute-vs-delta occupancy comparison
- `context/changes/ui-test-layer/research.md` — screenshot/smoke tooling landscape
  (Phase 1)

## Open Questions

1. **Oracle strategy for the round-trip invariant (G1)** — **DECIDED 2026-07-04
   (session):** hybrid A+B — hand-written expected-PGN fixtures in the E2E suites
   (author-as-oracle, covers PGN text incl. promotion piece) **plus** per-move
   occupancy invariants and a `footprintOf` ↔ `applyMove` occupancy-diff property
   test (breadth, engine-independent signal). No external verifier library for
   now; revisit (option C) only if A+B prove insufficient.
2. **Scope of the five verified defects (W2, W3, W5, W6, + G10 floor)** —
   **DECIDED 2026-07-04 (session):** tests + fixes in this change (red→green):
   each defect gets a test that exposes it on current code, then a minimal fix.
   The change therefore touches production code (PhysicalPlayViewModel/Reducer,
   PlayViewModel, SupabaseGamesRepository, GameAutoSaver).
3. **Zero-row UPDATE guard shape (W5)** — PostgREST `select()` on UPDATE +
   decode/row-count check vs a `count` header; needs a small spike at plan time.
4. **Is a History-level / startup reconcile in scope (G10/W4)** — "a finished game
   eventually lands in the cloud without further user activity" currently cannot
   hold if the screen closed; the risk-#2 proof line needs either a scope
   concession ("on next open of that game") or a mechanism change.
5. **W11 (Android `commit()` returning false)** — accept as theoretical, or wrap
   the journal in a read-back verify? Likely out of MVP scope; record as accepted
   risk in the plan.
