# Physical-record Integrity Tests Implementation Plan

## Overview

Rollout Phase 2 of `context/foundation/test-plan.md` ("Physical-record integrity").
Prove two guarantees and close the five code-verified defects that break them:

- **Risk #1** — a physical-board game is never *silently* recorded with moves that
  differ from what was played. Every accepted sequence replays to exactly the physical
  position; any divergence surfaces as a visible rejection.
- **Risk #2** — no crash / kill / offline window after "move accepted" loses the move
  or the end-of-game result, and a "status 200" false success never destroys the only
  copy.

The work is **test-first with minimal fixes** (red → green): each defect gets a test
that fails on the current code, then the smallest production change that makes it pass.
Oracle strategy is settled (research Open Q1): hand-written PGN fixtures + per-move
occupancy invariants + a `footprintOf`↔`applyMove` geometry property — **no external
chess library**.

## Current State Analysis

The §6.2 durability spine is implemented and already covered by ~40 focused tests
(`GameAutoSaverTest` ×20, four physical E2E suites, `SequenceInterpreterTest` ×25).
A move is "accepted" only after a synchronous journal write returns; the UI advances
on that success; cloud sync is asynchronous best-effort. The exposure is **not** missing
scenario coverage — it is five concrete defects and an oracle gap, all code-verified at
commit `014b03f` (research §5):

- **W5 (zero-row false success)** — `updatePgn`/`finishGame` are bare PostgREST UPDATEs
  filtered `eq("id", id)` with no affected-row check (`SupabaseGamesRepository.kt:106-135`).
  supabase-kt throws on non-2xx, but a **zero-row match returns 2xx**; `sync` then
  `markSynced`s, or for a finished entry **`journal.clear`s** — destroying the only copy
  (`GameAutoSaver.kt:79-80`).
- **W6 (reconcile offline hang)** — `reconcile` awaits `sync` **inline**
  (`GameAutoSaver.kt:114`); a finished dirty entry retries terminally (never gives up,
  `:97`), so `load()` never returns → the game screen sits in Loading forever.
- **W2 (physical finish inverts §6.2)** — the reducer freezes the UI with the result
  **before** the `FinishGame` effect journals (`PhysicalPlayViewModel.kt:131-135` vs
  `:208-213`). Manual finish's `autoSaver.finishGame` is **unguarded** (throw propagates
  after "finished" already shows); auto-close (mate) failure is **masked** as
  `MoveRejected(SAVE_FAILED)` on an already-finished state — nothing journaled, and on
  next load nothing re-closes it.
- **W3 (unguarded digital writes)** — `acceptMove` (`PlayViewModel.kt:296`) and finish
  (`:374`) sit unguarded on the synchronous tap path — a Settings throw (wasm localStorage
  quota) crashes the app.
- **W7 (kill window between key writes)** — the journal writes dirty → result → pgn as
  three independent `Settings` puts (`SettingsGameJournal.kt:28-41`); a crash between the
  result and pgn write can leave a finish that evaporates on next reconcile.

The oracle problem (risk #1): the PGN round trip (`writePgn`→`parsePgn`) shares
`ChessRules` on both sides, so an engine bug is invisible to it. The only engine-independent
in-process oracles are the occupancy bitmap (sensed vs derived) and hand-written
expectations. `footprintOf` (`SequenceInterpreter.kt:139`) is a second, hand-mirrored
encoding of move geometry — a SYNC-comment coupling (`lessons.md`) that can be turned into
a mechanical property.

## Desired End State

The per-target test suites (`:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`,
`:shared:wasmJsTest`) carry new fault-injection and record-integrity tests that:

- were **red** against the code at `014b03f` for each of W5/W6/W2/W3 and are **green**
  after the minimal fix;
- prove for a corpus of physical games (incl. one long "kitchen-sink" game from
  published PGN) that the persisted PGN equals a **hand-written** expectation and that the
  sensed occupancy equals the derived occupancy after **every** accepted move;
- prove `footprintOf`'s square-sets equal the `applyMove` occupancy diff for every legal
  move in a position corpus;
- prove adversarial board streams (phantom lifts, bit-flipped snapshots, divergence during
  a disconnect gap) produce a **visible rejection or a no-op**, never a divergent accepted
  move.

The test-plan §2 risk-#2 proof line is annotated with the P4 scope concession, the roadmap
carries a follow-up item for the auto-flush sweep, and `test-plan.md §6.5` cookbook is
filled. **Verify**: all three target suites green in CI; each defect's test demonstrably
red when its fix is reverted.

### Key Discoveries

- Zero-row detection in postgrest-kt 3.6.0 (spike, Open Q3 resolved): `update({...}) { select(...) }`
  returns the updated rows; a zero-row UPDATE decodes to an **empty list**
  (`PostgrestQueryBuilder.kt:187` "updated rows are not returned … call `select`";
  `PostgrestResult.decodeList`). `count(Count.EXACT)` + `countOrNull()` is the fallback.
  supabase-kt does **not** throw on zero rows.
- `footprintOf` + `Footprint` are `private` (`SequenceInterpreter.kt:127,139`); `applyMove`
  is `internal` (`ChessRules.kt:69`); `toOccupancy`/`legalMoves` are public. The G2 property
  needs `footprintOf`/`Footprint` raised to `internal` — the one production touch in the
  otherwise test-only Phase 3.
- Test seams already present: `FakeGamesRepository` (`updatePgnFailures` countdown,
  `onUpdatePgn` mid-flight hook, `failure: Throwable`), `FakeGameJournal`
  (`saveLog`/`syncedIds`/`clearedIds`), `EmulatedBoard(initialOccupancy=…)` + `setOccupancy`
  (disconnected only) + `BoardScenarios` DSL. Gaps to add: repository zero-row signal,
  a journal that throws on `save`, and a `Settings` that stops between puts.
- The virtual-time discipline is load-bearing (`lessons.md`): `runCurrent` for the connect
  burst, `tick()` for diagnostic snapshots, `advanceUntilIdle` only while no stream is armed.
- `reconcile` has exactly two call sites: `PlayViewModel.load` (`:150`) and
  `PhysicalPlayViewModel.load` (`:152`). Both must launch the background flush after the
  reconcile/sync split.
- A curated published-PGN corpus already exists: `internal object PgnFixtures`
  (`commonTest/.../domain/chess/pgn/PgnFixtures.kt`, added by the seed slice, parser-legality
  verified in `PgnParserTest`) — 8 famous games (Opera, Immortal, Evergreen, Game of the
  Century, …). Reuse it as the Phase-3 external oracle. Caveat: **none of the famous games
  contains a promotion or an en-passant capture**, so those two shapes stay covered by the
  short hand-written scenarios (`PhysicalCaptureEndToEndTest` already scripts both).

## What We're NOT Doing

- **Not** building a global / startup / History-level auto-flush sweep (P4 = A). The
  "eventually lands without further user activity" guarantee stays scoped to "on next open
  of that game on this device"; a test documents the floor and the roadmap carries the
  mechanism.
- **Not** re-creating a deleted cloud row on zero-row (P1 = A, not C) — no INSERT, no
  client-assigned id, no RLS change. Zero-row is a hard failure that preserves the journal.
- **Not** cleaning up local journal orphans left after a real cross-device delete (deferred
  with the sweep to the roadmap).
- **Not** adding a read-back verify for the Android `commit()==false` window (P5 = A) — a
  full-disk cache read would lie anyway; recorded as an accepted risk.
- **Not** testing the BLE transport / codec garbage-byte layer here (P7 = A) — that is
  test-plan Phase 3 ("BLE resilience"). This phase attacks only the game-logic level.
- **Not** an external chess-engine oracle library (research Open Q1) — hand-written
  fixtures + occupancy invariants only.
- **Not** real process-kill / platform-persistence, real-radio BLE, or multi-device
  concurrent editing (test-plan §7).

## Implementation Approach

Five phases, ordered damage-first: fix the data-destroying defects (Phases 1–2), then
build the correctness proofs (Phases 3–4), then the edge/documentation tail (Phase 5).
Each defect fix is preceded by its failing test in the same phase. The only cross-phase
dependency: Phase 4 reuses the occupancy-invariant helper introduced in Phase 3.

Fixes stay minimal and pattern-consistent: the physical finish rework mirrors the existing
`CommitMove`→`MoveCommitted` feedback loop (so "state advances only on durable success"
holds on the finish path exactly as it does on the move path); the zero-row signal is a
domain exception caught **before** the existing `catch (Throwable)` retry arm; the
reconcile/sync split reuses the fire-and-forget `viewModelScope.launch { sync }` already
used after every move.

## Critical Implementation Details

- **Zero-row must be caught before the generic retry.** `GameAutoSaver.sync`'s
  `catch (_: Throwable)` currently treats every failure as a transient blip and retries.
  The new `GameRowMissingException` must be caught in a **dedicated arm above** it — it is a
  deterministic failure (retrying re-hits zero rows), so it stops the loop and leaves the
  journal untouched. Order of catch arms is the correctness contract.
- **The reconcile/sync split must not regress the finished-flush backstop.** After
  `reconcile` stops awaiting `sync`, the journal-ahead entry must stay `dirty` and the
  caller must launch the background flush, or a journaled-but-unsynced finish would no
  longer re-close the cloud on load. The `sync` launched after load is idempotent (no-op on
  a clean entry), so launching it unconditionally after every `load` is safe.
- **Physical finish feedback ordering.** The reducer must stop setting `result` in
  `EndGameConfirmed` and in `commit`'s auto-close arm; `result` is set only by the new
  `FinishCommitted` message the ViewModel dispatches **after** `autoSaver.finishGame`
  returns. The pending result must live in `Playing` state (e.g. `finishResult: GameResult?`)
  so a `FinishRejected` can drive the retry without re-deriving it — an auto-close result is
  derivable from the position, but a manual result (resign/agreed draw) is not.
- **Load self-heal is bounded to terminal positions.** The `Loaded` arm auto-closes only
  when `status` is Checkmate/Stalemate **and** `result == null` **and** the record is not
  finished — otherwise an ordinary in-progress resume would spuriously finish.

## Phase 1: Cloud-handoff Durability (W5 + W6 + W3)

### Overview

Fix the three risk-#2 back-half defects: the zero-row false success that destroys the only
copy (W5, P1 = A), the reconcile-await hang on offline load (W6, P2 = A), and the unguarded
digital journal writes that crash (W3). Each with its failing test first.

### Changes Required

#### 1. Zero-row domain signal

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GamesRepository.kt`

**Intent**: give the durability layer a way to distinguish "the row is gone" (a
deterministic failure that must **not** clear the journal) from a transient network throw.

**Contract**: a new `class GameRowMissingException(id: String) : Exception`. Document on
`updatePgn` / `finishGame` that a zero-row UPDATE throws it (in addition to the existing
network-failure propagation).

#### 2. Zero-row detection in the repository

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/games/SupabaseGamesRepository.kt`

**Intent**: make `updatePgn` and `finishGame` observe whether the UPDATE actually matched a
row, and throw `GameRowMissingException` when it matched none.

**Contract**: add `select(Columns.list("id"))` to both UPDATE request blocks and decode the
returned rows; an empty result throws. A private `@Serializable data class UpdatedIdDto(val id: String)`.
Non-obvious (worth a snippet — supabase-kt does not surface zero-row otherwise):

```kotlin
val updated = client.from("games")
    .update({ set("pgn", pgn) }) {
        select(Columns.list("id"))
        filter { eq("id", id) }
    }.decodeList<UpdatedIdDto>()
if (updated.isEmpty()) throw GameRowMissingException(id)
```

#### 3. `sync` handles zero-row without destroying the journal

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt`

**Intent**: on `GameRowMissingException`, leave the journal entry intact (no `markSynced`,
no `clear`), stop retrying (the failure is deterministic), and end the flush.

**Contract**: a dedicated `catch (gone: GameRowMissingException)` arm **above** the existing
`catch (cancellation)` / `catch (Throwable)` arms in `sync`; it `return`s without mutating
the journal. `syncPending` stays raised (the entry genuinely is unsynced).

#### 4. `reconcile` returns without awaiting the flush

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt`

**Intent**: stop blocking `load()` on the cloud. `reconcile` decides which PGN to play from
(logic unchanged) and returns immediately; the journal-ahead entry stays `dirty` for the
caller's background flush.

**Contract**: `reconcile` no longer calls `sync` inline (`:114`). The journal-ahead branch
returns `entry.pgn` and leaves the entry dirty (+ `syncPending` raised). Signature unchanged
(still `suspend` for `journal.load`). This changes behavior asserted by `GameAutoSaverTest`
(offline-reconcile tests) — update those.

#### 5. Background flush after load (both play screens)

**Files**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/play/PlayViewModel.kt`,
`shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayViewModel.kt`

**Intent**: replace the flush that `reconcile` used to await with a fire-and-forget launch
after the game loads — the pattern already used after every accepted move.

**Contract**: in each `load()`, after the `Loaded` dispatch, `viewModelScope.launch { autoSaver.sync(gameId) }`.
Idempotent on a clean entry.

#### 6. Guard the digital tap-path writes

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/play/PlayViewModel.kt`

**Intent**: a journal `save` throw on the synchronous tap path (wasm localStorage quota)
must not crash — the move is simply not accepted (no UI advance), mirroring the physical
`MoveRejected(SAVE_FAILED)` posture.

**Contract**: wrap the `autoSaver.acceptMove` (`:296`) and `autoSaver.finishGame` (`:374`)
calls in `try` / `catch (Throwable)` (rethrowing `CancellationException`); on failure, do
not advance `_uiState`. Minimal graceful handling — no new banner required for the digital
MVP surface.

#### 7. Test seams + tests (red → green)

**Files**: `shared/src/commonTest/.../presentation/FakeGamesRepository.kt`,
`shared/src/commonTest/.../presentation/FakeGameJournal.kt`,
`shared/src/commonTest/.../domain/games/GameAutoSaverTest.kt`,
`shared/src/commonTest/.../presentation/play/PlayViewModelTest.kt` (or the existing digital VM test file)

**Intent**: add the fault-injection knobs and the three failing tests.

**Contract**:
- `FakeGamesRepository`: a `rowMissing: Boolean` (or per-verb flag) that makes
  `updatePgn`/`finishGame` throw `GameRowMissingException` while still recording the call.
- `FakeGameJournal`: a `failSaveWith: Throwable?` that makes `save` throw.
- **G5** (W5): a finished, dirty entry + `rowMissing` → after `sync`, assert the journal is
  **not** cleared and **not** markSynced (`clearedIds`/`syncedIds` empty). Red today.
- **G7** (W6): a finished, dirty entry + offline repo (`shouldFail`) → `reconcile` returns
  (bounded) instead of hanging; assert `load()` reaches Playing/Error, not stuck Loading.
  Red today (inline await never returns).
- **G8** (W3): `failSaveWith` set → a digital tap does not crash and the move is not
  advanced. Red today.

### Success Criteria

#### Automated Verification

- `:shared:testAndroidHostTest` passes: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- `:shared:iosSimulatorArm64Test` passes (native target — mandatory for any changed logic)
- `:shared:wasmJsTest` passes
- ktlint clean on changed sources
- Each of G5/G7/G8 is demonstrably red when its fix is reverted (spot-check during dev)

#### Manual Verification

- Offline open of a finished-but-unsynced physical game shows the board immediately
  (no indefinite spinner) and the "Saving…" indicator resolves once connectivity returns
- Digital play under a forced storage failure rejects the move without crashing (dev-only
  spot check; not a shipped affordance)

**Implementation Note**: After automated verification passes, pause for manual confirmation
before Phase 2. Code/doc-read manual items may be deferred to `manual-verification.md` per
project convention and confirmed at slice end.

---

## Phase 2: Physical Finish Integrity (W2)

### Overview

Restore §6.2 on the physical finish path (P3 = A): the UI shows "finished" only **after**
the durable finished-PGN write returns; a write failure surfaces a banner with **Retry**
and leaves the game live; opening a game whose board is mate/stalemate but whose record was
never finished self-heals by auto-closing at load. Mirrors the existing
`CommitMove`→`MoveCommitted` feedback loop.

### Changes Required

#### 1. Pending-result state + finish feedback messages

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayContract.kt`

**Intent**: model "a finish is requested and being written but not yet durable" separately
from "the game is finished", so the reducer can advance to `result` only on durable success.

**Contract**: add `finishResult: GameResult?` to `PhysicalPlayState.Playing` (the result
awaiting its durable write). Add `PhysicalMsg.FinishCommitted(result)` and
`PhysicalMsg.FinishRejected` (or reuse `MoveRejected(SAVE_FAILED)` — decide during impl, but
the retry must know the pending result). Keep `RejectionReason.SAVE_FAILED`.

#### 2. Reducer: don't freeze before the write

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt`

**Intent**: stop setting `result` before the journal write; set `finishResult` and emit the
`FinishGame` effect; advance to `result` only on `FinishCommitted`.

**Contract**:
- `EndGameConfirmed` (`:229-239`): set `finishResult = prompt.result`, clear the prompt,
  emit `FinishGame` — do **not** set `result`.
- `commit` auto-close (`:434-438`): set `finishResult = autoResult`, emit `FinishGame` — do
  **not** set `result`.
- New `FinishCommitted` arm: `result = finishResult`, `finishResult = null` (the freeze).
- New `FinishRejected` arm: `rejection = SAVE_FAILED`, keep `finishResult` set (so Retry can
  re-emit), game stays live.
- `Loaded` arm (`:41-79`): when `status` is Checkmate/Stalemate **and** `msg.result == null`
  **and** the record is not finished, emit `FinishGame(gameResultFor(status), sanMoves)` to
  self-heal a W2-stranded game (instead of the current dead frozen-without-result state).

#### 3. ViewModel: guarded finish with feedback

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayViewModel.kt`

**Intent**: make `finishGame` symmetric to `commitMove` — the durable write is the gate;
only its success dispatches the freeze.

**Contract**: `finishGame` (`:208-213`) runs in `viewModelScope.launch`, `try`s
`autoSaver.finishGame(...)`, then `dispatch(FinishCommitted(result))` and
`launch { autoSaver.sync(gameId) }`; `catch (Throwable)` (rethrow Cancellation) →
`dispatch(FinishRejected)`. Add a `retryFinish()` intent that re-emits the finish from
`finishResult`.

#### 4. Screen: Retry affordance on the SAVE_FAILED banner

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayScreen.kt`

**Intent**: give the SAVE_FAILED banner a Retry button, reusing the Reconnect-button pattern
already in the banner slot.

**Contract**: when `rejection == SAVE_FAILED` and a finish is pending, render a Retry action
wired to `viewModel.retryFinish()`. Banner texts (`:459-471`) extended if needed.

#### 5. Tests (red → green) — G6

**File**: `shared/src/commonTest/.../presentation/physical/PhysicalFinishEndToEndTest.kt` (new),
extending the `PhysicalCaptureEndToEndTest` harness pattern.

**Contract**:
- Manual finish + journal throw → no frozen "finished" state without a journaled result;
  SAVE_FAILED banner shows; `retryFinish` after the fault clears lands the write and freezes.
  Red today (unguarded throw after freeze).
- Auto-close (mate) + journal throw → not masked as `MoveRejected` on a finished state;
  result not shown until durably written. Red today.
- Load self-heal: a record `in_progress` whose PGN's last position is checkmate and carries
  no result token → `load()` auto-finishes (journaled result, frozen UI). Red today (dead
  frozen-without-result).

### Success Criteria

#### Automated Verification

- `:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest` pass
- ktlint clean on changed sources
- The three G6 assertions are red when the Phase-2 reducer/VM changes are reverted

#### Manual Verification

- Physical manual end-game under a forced write failure keeps the game playable and shows
  a Retry that succeeds once the fault clears (three-target manual gate, may be deferred to
  `manual-verification.md`)
- A mate-auto-closed physical game reopened from History shows the correct result

**Implementation Note**: Pause for manual confirmation after automated verification.

---

## Phase 3: Independent Record-integrity Oracle (G1 + G2)

### Overview

Prove recording fidelity with engine-independent oracles (risk #1, P6 = B): hand-written
PGN fixtures, per-move occupancy invariants, the `footprintOf`↔`applyMove` geometry
property, and one long "kitchen-sink" game from published PGN. Test-only except one
visibility change.

### Changes Required

#### 1. Raise `footprintOf` / `Footprint` to internal

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/SequenceInterpreter.kt`

**Intent**: let the property test read the raw footprint square-sets (the only production
touch in this phase).

**Contract**: `private class Footprint` → `internal`; `private fun footprintOf` → `internal`
(`:127`, `:139`). Expose vacated/arrived/captured sets as internal-visible members. Keep the
SYNC comment.

#### 2. Occupancy-invariant test helper

**File**: `shared/src/commonTest/.../domain/board/OccupancyInvariant.kt` (new)

**Intent**: a reusable assertion that the sensed board occupancy equals the derived occupancy
of the recorded position after every accepted move — the engine-independent risk-#1 guard.

**Contract**: `assertSensedMatchesDerived(sensed: Long, position: Position)` comparing
`sensed == position.toOccupancy()`; a driver that runs it after each accept in a scripted
E2E. Reused by Phase 4.

#### 3. `footprintOf` ↔ `applyMove` occupancy-diff property

**File**: `shared/src/commonTest/.../domain/board/FootprintApplyMoveConsistencyTest.kt` (new)

**Intent**: turn the SYNC-comment coupling into a mechanical check — for every legal move in
a position corpus, the footprint's vacated/arrived/captured sets must equal the occupancy
diff between `position` and `applyMove(position, move)`.

**Contract**: iterate `legalMoves(position)` over a corpus (start position, castling/en-passant/
promotion-rich positions reused from `SequenceInterpreterTest`, plus Kiwipete-style
positions); assert footprint sets == `toOccupancy(position)` XOR `toOccupancy(applyMove(...))`
decomposed into vacated/arrived (+ the en-passant/castle-rook captured square). `applyMove`
is `internal` — same-module commonTest sees it.

#### 4. Independent-oracle expectations on the short scenarios (esp. promotion + en passant)

**Files**: `shared/src/commonTest/.../presentation/physical/PhysicalCaptureEndToEndTest.kt`
(and siblings), `shared/src/commonTest/.../fixtures/RecordFixtures.kt` (new — only for shapes
`PgnFixtures` doesn't cover)

**Intent**: assert the persisted PGN against an expectation **not** produced by `writePgn`, and
run the occupancy invariant per move. The famous-game corpus (`PgnFixtures`, reused in #5)
covers castling/disambiguation/mate/captures on a long chain; this step covers the two shapes
the corpus lacks — **promotion (incl. the promoted piece) and en passant** — plus captures in
both lift orderings, via short hand-written expected PGN.

**Contract**: hand-written expected-PGN constants in `RecordFixtures.kt` for the promotion and
en-passant scenarios (and any capture ordering not otherwise asserted against a `PgnFixtures`
game); each scripted scenario asserts `journaled PGN == fixture` and calls the occupancy-invariant
driver after every accept. Do **not** re-derive the expectation from `writePgn` output.

#### 5. Kitchen-sink game (reuse `PgnFixtures`)

**File**: `shared/src/commonTest/.../presentation/physical/PhysicalKitchenSinkEndToEndTest.kt` (new)

**Intent**: the strongest single anti-drift proof — a **published** game played move-by-move
through the full pipeline (sensors → resolve → accept → journal), whose persisted PGN must equal
the published text exactly, with the occupancy invariant after every move.

**Contract**: drive `PgnFixtures.OPERA_GAME` (33 plies: queenside castling `O-O-O`, the `Nbd7`
file disambiguation, check suffixes, mate — the most special-move-dense of the short famous
games) via `BoardScenarios` (extend the DSL if a move shape is missing); assert character-exact
equality against the `PgnFixtures` constant (a genuinely external oracle — verified in
`PgnParserTest`, byte-parity with `seed.sql`) + per-move occupancy invariant. **Promotion and
en passant are absent from every famous game** — they are covered by the short scenarios in #4,
not here. Longest test in the suite — budget accordingly.

### Success Criteria

#### Automated Verification

- `:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest` pass
  (native target mandatory — the property + fixtures are logic)
- ktlint clean on changed sources
- The `footprintOf`↔`applyMove` property fails if a deliberate one-square error is injected
  into `footprintOf` (spot-check the oracle actually bites)

#### Manual Verification

- Review the hand-written promotion + en-passant expected PGN (step #4) against a trusted
  source to confirm the expectation is genuinely external (not copied from `writePgn` output).
  The kitchen-sink game reuses `PgnFixtures.OPERA_GAME`, already externally verified.

**Implementation Note**: Pause for manual confirmation after automated verification.

---

## Phase 4: Adversarial Board Streams (G3 + G4)

### Overview

Prove that hostile board input at the **game-logic** level (not the BLE transport — P7 = A)
never yields a divergent accepted move: it surfaces a visible rejection or is a no-op.
Reuses the Phase 3 occupancy invariant.

### Changes Required

#### 1. Adversarial-stream scenario tests

**File**: `shared/src/commonTest/.../presentation/physical/PhysicalAdversarialStreamTest.kt` (new)

**Intent**: inject phantom and corrupted board events during GAME mode and assert the
acceptance gate never records a move that diverges from the physical truth.

**Contract**: scenarios asserting visible rejection (banner / `setupMismatch`) or no-op:
- phantom lift/place blips mid-sequence → absorbed by subset matching or `Incomplete`, no
  divergent accept;
- a bit-flipped at-rest snapshot → `setupMismatch` (visible), not a silent accept;
- a malformed/garbage board event at the port during GAME mode → dropped, no-op;
- each asserts the occupancy invariant holds (no accepted move ever diverges).

#### 2. Divergence-during-gap tests (G4)

**File**: same suite

**Intent**: cover the couple-point between risk #1 and #2 — board changes made while
DISCONNECTED that happen to produce a legal-looking stream after reconnect must be blocked
by the reconcile gate, not silently accepted.

**Contract**: using `EmulatedBoard` offline mutation + `setOccupancy` (disconnected only):
- offline board change producing a legal-looking post-reconnect stream → gate holds until
  restore (no accept);
- second capture of an already-captured square → rejected/no-op.
  Settle the connect burst with `runCurrent` per `lessons.md` (never `advanceUntilIdle` while
  the diagnostic stream is armed).

### Success Criteria

#### Automated Verification

- `:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest` pass
- ktlint clean on changed sources
- No test relies on `waitForTimeout`-style waits; virtual-time discipline followed

#### Manual Verification

- Spot-review that each adversarial scenario asserts "no divergent accept" (occupancy
  invariant), not merely "no crash"

**Implementation Note**: Pause for manual confirmation after automated verification.

---

## Phase 5: Kill-window + Durability Floor + Docs (W7/G9 + G10)

### Overview

Document the remaining narrow windows and scope concessions with tests and prose: the
kill-window between journal key writes (W7/G9), the auto-flush reachability floor (G10,
P4 = A), the accepted Android `commit()` risk (W11, P5 = A), and the cookbook/roadmap/test-plan
updates.

### Changes Required

#### 1. Failing `Settings` between puts + kill-window test

**Files**: `shared/src/commonTest/.../data/journal/StoppingSettings.kt` (new),
`shared/src/commonTest/.../data/journal/SettingsGameJournalTest.kt`

**Intent**: simulate a crash between the dirty/result write and the pgn write, and assert the
documented recovery (the pgn presence-gate means `load` sees only the prior document).

**Contract**: a `Settings` test double that stops writing after N puts; a test that performs a
partial finish write (dirty + result landed, pgn not) and asserts `load` returns the prior
entry and the subsequent `reconcile` outcome (research W7: identical movetext + non-finished
journal pgn → not ahead → cloud wins). If the outcome is a genuine loss, record it as an
accepted narrow risk (do not expand scope to a transactional journal).

#### 2. Auto-flush floor test (G10)

**File**: `shared/src/commonTest/.../domain/games/AutoFlushFloorTest.kt` (new)

**Intent**: pin the current behavior — a finished-but-unsynced game whose screen closed before
the flush lands is **not** re-flushed until that game is re-opened on this device.

**Contract**: assert no flush occurs without a re-open (documents the floor P4 = A concedes);
a comment ties it to the roadmap follow-up.

#### 3. Documentation updates

**Files**: `context/foundation/test-plan.md`, `context/foundation/roadmap.md`,
`context/foundation/lessons.md`, `context/changes/testing-record-integrity/change.md`

**Intent**: record the concession, the follow-up, and the reusable lessons.

**Contract**:
- `test-plan.md`: annotate the risk-#2 proof line ("… eventually lands …") with the P4 = A
  concession ("on next open of that game on this device"); fill cookbook §6.5 (fault-injection
  test pattern: kill/offline injection points + `runCurrent` discipline); move the phase Status
  to `complete` at slice end.
- `roadmap.md`: add a follow-up item — background auto-flush sweep (journal enumeration +
  History/startup flush) **and** local orphan cleanup (the P1 zero-row orphan).
- `lessons.md`: append lesson(s) worth surfacing (zero-row false success; physical finish must
  mirror the move-accept feedback loop; reconcile must not await the flush). Use `/10x-lesson`
  format.
- Record W11 (Android `commit()==false`) as an accepted risk in this plan's Open Risks
  (below) — no code.

### Success Criteria

#### Automated Verification

- `:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest` pass
- ktlint clean on changed sources
- `test-plan.md §6.5` no longer reads "TBD"; `roadmap.md` carries the sweep follow-up

#### Manual Verification

- Read-through: the risk-#2 proof line and the roadmap item together make the floor and its
  planned closure explicit and honest

**Implementation Note**: Pause for manual confirmation; then the slice is ready for
`/10x-impl-review` and `/10x-archive`.

---

## Testing Strategy

### Unit / Integration Tests (KMP commonTest)

- Autosaver/repository fault injection: zero-row (G5), offline reconcile (G7), guarded digital
  writes (G8), kill-window (W7/G9), auto-flush floor (G10).
- Reducer/ViewModel: physical finish feedback + self-heal (G6).
- Record integrity: hand-written PGN fixtures + per-move occupancy invariant (G1), kitchen-sink
  game, `footprintOf`↔`applyMove` property (G2).
- Adversarial: phantom/bit-flip/garbage streams, divergence-during-gap (G3/G4).

### Manual Testing Steps

1. Offline-open a finished-but-unsynced physical game → board shows immediately; indicator
   resolves on reconnect.
2. Force a write failure during a physical manual end-game → game stays live, Retry appears
   and succeeds after the fault clears.
3. Reopen a mate-auto-closed physical game from History → correct frozen result.

## Performance Considerations

The kitchen-sink E2E is the longest test in the suite (a 15–25-move game driven event-by-event
through the emulator). Keep it a single test; rely on the `BoardScenarios` DSL and virtual time
so wall-clock stays in seconds. No production hot-path changes (the zero-row `select` adds one
returned id column to an UPDATE already on the async flush path, never the acceptance path).

## Migration Notes

No data migration. `GameRowMissingException` and the `finishResult` state field are additive.
The reconcile/sync contract change is internal to the app (no schema/API change); existing
`GameAutoSaverTest` assertions about inline flush are updated in Phase 1.

## References

- Research: `context/changes/testing-record-integrity/research.md`
- Test plan: `context/foundation/test-plan.md` (§2 risks #1/#2, §3 Phase 2, §6.5 cookbook)
- Lessons: `context/foundation/lessons.md` (terminal flush retry; catch(Throwable) at wasm
  sites; SYNC-comment geometry; mid-game resume `runCurrent` discipline)
- Durability spine (archived 2026-07-04): `context/archive/2026-06-12-digital-pass-and-play/plan.md` (§6.2),
  `context/archive/2026-06-13-game-end-and-result/plan.md` (offline-safe finish),
  `context/archive/2026-06-19-physical-resume-after-restart/` (resume gate, occupancy injection)
- Zero-row spike: postgrest-kt 3.6.0 `PostgrestQueryBuilder.update` + `PostgrestResult.decodeList`

## Open Risks & Assumptions

- **W11 (Android `commit()==false`)** — accepted as a theoretical risk (P5 = A). `Settings.putString`
  returns `Unit`, so a false commit is unobservable; a read-back verify would read from cache and
  lie under the exact (full-disk) condition it would target. No code; revisit post-MVP if observed.
- **Auto-flush floor (P4 = A)** — a game finished offline and never re-opened does not reach the
  cloud. Conceded in scope, documented by the G10 test + test-plan annotation, closure deferred to
  the roadmap sweep item.
- **Zero-row orphan (P1 = A)** — a deliberate cross-device delete leaves a local journal orphan
  invisible in the UI. Harmless; cleanup deferred to the roadmap sweep item.
- **Assumption**: the postgrest-kt `select`-on-UPDATE returns exactly the matched rows under the
  project's RLS; verified against the library API, to be confirmed on the manual gate against live
  Supabase during Phase 1's manual step.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Cloud-handoff Durability (W5 + W6 + W3)

#### Automated

- [ ] 1.1 `:shared:testAndroidHostTest` passes
- [ ] 1.2 `:shared:iosSimulatorArm64Test` passes
- [ ] 1.3 `:shared:wasmJsTest` passes
- [ ] 1.4 ktlint clean on changed sources
- [ ] 1.5 G5/G7/G8 each demonstrably red when its fix is reverted

#### Manual

- [ ] 1.6 Offline open of a finished-but-unsynced physical game shows the board immediately; indicator resolves on reconnect
- [ ] 1.7 Digital play under forced storage failure rejects the move without crashing

### Phase 2: Physical Finish Integrity (W2)

#### Automated

- [ ] 2.1 `:shared:testAndroidHostTest` passes
- [ ] 2.2 `:shared:iosSimulatorArm64Test` passes
- [ ] 2.3 `:shared:wasmJsTest` passes
- [ ] 2.4 ktlint clean on changed sources
- [ ] 2.5 The three G6 assertions are red when the Phase-2 reducer/VM changes are reverted

#### Manual

- [ ] 2.6 Physical manual end-game under forced write failure stays playable; Retry succeeds after the fault clears
- [ ] 2.7 A mate-auto-closed physical game reopened from History shows the correct result

### Phase 3: Independent Record-integrity Oracle (G1 + G2)

#### Automated

- [ ] 3.1 `:shared:testAndroidHostTest` passes
- [ ] 3.2 `:shared:iosSimulatorArm64Test` passes
- [ ] 3.3 `:shared:wasmJsTest` passes
- [ ] 3.4 ktlint clean on changed sources
- [ ] 3.5 The `footprintOf`↔`applyMove` property fails on a deliberate one-square error injection

#### Manual

- [ ] 3.6 Hand-written promotion + en-passant expected PGN reviewed against a trusted source (genuinely external; kitchen-sink reuses the already-verified `PgnFixtures.OPERA_GAME`)

### Phase 4: Adversarial Board Streams (G3 + G4)

#### Automated

- [ ] 4.1 `:shared:testAndroidHostTest` passes
- [ ] 4.2 `:shared:iosSimulatorArm64Test` passes
- [ ] 4.3 `:shared:wasmJsTest` passes
- [ ] 4.4 ktlint clean on changed sources
- [ ] 4.5 No timeout-style waits; virtual-time discipline followed

#### Manual

- [ ] 4.6 Each adversarial scenario asserts "no divergent accept" (occupancy invariant), not merely "no crash"

### Phase 5: Kill-window + Durability Floor + Docs (W7/G9 + G10)

#### Automated

- [ ] 5.1 `:shared:testAndroidHostTest` passes
- [ ] 5.2 `:shared:iosSimulatorArm64Test` passes
- [ ] 5.3 `:shared:wasmJsTest` passes
- [ ] 5.4 ktlint clean on changed sources
- [ ] 5.5 `test-plan.md §6.5` no longer reads "TBD"; `roadmap.md` carries the sweep follow-up

#### Manual

- [ ] 5.6 Risk-#2 proof line + roadmap item together make the floor and its planned closure explicit
