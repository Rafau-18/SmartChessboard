# Game End and Result (S-05) Implementation Plan

## Overview

Close a digital game and record its result into the canonical record. Two paths:

- **Automatic** (FR-007): when the F-01 engine reports checkmate or stalemate after an accepted
  move, the game closes itself — checkmate records the side that delivered mate as the winner,
  stalemate records a draw.
- **Manual** (FR-018): the player can end any in-progress game and record win / loss / draw — the
  only way draw-by-rule games (threefold / 50-move / insufficient material) and resignations close
  in MVP.

Closing a game means a single canonical transition: `status` → `finished`, `result` → one of
`white | black | draw`, and the PGN `[Result]` tag (and movetext terminator) rewritten to match
(`1-0` / `0-1` / `1/2-1/2`). The transition is offline-safe (rides the existing write-ahead
journal) and irreversible in MVP — a finished game is read-only and reopens in Replay. Verified on
Android, iOS, and web.

## Current State Analysis

S-04 (`digital-pass-and-play`, implemented) already **detects** terminal positions during play and
**surfaces** them, but never **closes** the record:

- `PlayViewModel.acceptMove` computes `status(nextPosition)` per move and stores it in
  `PlayUiState.Playing.status` ([PlayViewModel.kt:254](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/play/PlayViewModel.kt)).
- The derived `terminal` flag blocks input and freezes the board
  ([PlayViewModel.kt:69](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/play/PlayViewModel.kt),
  [PlayScreen.kt:135](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/play/PlayScreen.kt)).
- `StatusBanner` already renders "Checkmate — White wins" / "Stalemate — draw" text
  ([PlayScreen.kt:163](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/play/PlayScreen.kt)).
- **But** `meta.result` is hardcoded `"*"` ([PlayViewModel.kt:267](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/play/PlayViewModel.kt)),
  so every saved PGN carries `[Result "*"]`; the record stays `in_progress`. The S-04 brief and the
  in-code comments state plainly that result recording is S-05's job.

What already exists and is reused unchanged:

- **Domain types**: `GameStatus { IN_PROGRESS, FINISHED }` and `GameResult { WHITE, BLACK, DRAW }`
  enums; `GameRecord`/`GameSummary` already carry `status` + nullable `result`
  ([GameSummary.kt](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameSummary.kt)).
- **Engine**: `status(position): GameStatus` (sealed `Ongoing/Check/Checkmate/Stalemate`,
  [GameStatus.kt](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/GameStatus.kt)) — carries no winner; the winner is inferred from
  `position.sideToMove.opposite`. SAN already appends `#`/`+` to the mating/checking move
  ([SanWriter.kt:39](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/pgn/SanWriter.kt)).
- **PGN**: `PgnMeta(..., result: String, ...)` + `writePgn(meta, sanMoves)` already routes `result`
  into both the `[Result]` tag and the movetext terminator
  ([PgnWriter.kt](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/pgn/PgnWriter.kt)); `parsePgn` recognises the four result tokens and exposes
  `PgnHeaders.result`, which Replay already displays ([ReplayScreen.kt:288](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/replay/ReplayScreen.kt)).
- **Schema**: `games.status` (CHECK `in_progress|finished`) and `games.result`
  (CHECK `white|black|draw`, nullable) already exist — migration
  `20260611103324_games.sql`. **No migration needed.**
- **Routing**: `HistoryKey` already routes `IN_PROGRESS && DIGITAL → PlayKey`, everything else
  (incl. finished) → `ReplayKey` ([App.kt:70](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt)). `HistoryScreen.outcomeLabel` already
  maps `result` to "White won / Black won / Draw" ([HistoryScreen.kt:142](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/history/HistoryScreen.kt)).

What is missing (the work of S-05):

- A result mapping (terminal `GameStatus` + `Color` → `GameResult`; `GameResult` → PGN token).
- A repository finalization method (none today — `GamesRepository` has only `createGame`/`updatePgn`,
  the latter explicitly "status/result are untouched in S-04",
  [GamesRepository.kt](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GamesRepository.kt)).
- Offline-safe finalization: the journal/auto-saver flush only PGN today; `GameJournal` notes
  "Entries are kept until S-05 owns cleanup on game finish"
  ([GameJournal.kt:12](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameJournal.kt)).
- ViewModel finalization logic (auto + manual) and the end-game UI + post-finish navigation.

## Desired End State

On Android, iOS, and web: when a player delivers checkmate (or reaches stalemate), the game closes
itself — the banner shows the final result, the board is frozen, and `status`/`result`/PGN
`[Result]` are persisted (locally first, then cloud). At any point during an in-progress game the
player can tap **End game**, pick White wins / Black wins / Draw, confirm, and the same closure
happens. A finished game shows **Analyse** and **Back to history** actions, reopens in Replay
(read-only) with the correct result, and appears as finished in History. Finishing works offline:
the result is journaled durably and flushed on reconnect; a crash between finish and flush re-syncs
on relaunch.

Verify: `parsePgn(stored_pgn).result` equals the result token; the Supabase row has
`status='finished'` with a matching `result`; History shows the outcome; Replay's `PlayerLine`
shows `White vs Black · 1-0`; the per-target test suites and a three-surface E2E pass.

### Key Discoveries:

- Terminal detection is already wired but non-closing — `status(nextPosition)` at
  [PlayViewModel.kt:254](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/play/PlayViewModel.kt); `meta.result` hardcoded `"*"` at
  [PlayViewModel.kt:267](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/play/PlayViewModel.kt).
- `GameStatus` (engine) carries no winner — infer from `position.sideToMove.opposite`
  ([ChessRules.kt:53](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/ChessRules.kt)).
- `writePgn` already serialises any `result` token into tag + terminator
  ([PgnWriter.kt:34,47](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/pgn/PgnWriter.kt)); SAN already carries `#` ([SanWriter.kt:39](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/pgn/SanWriter.kt)).
- Schema is ready (CHECK constraints on `status`/`result` in `20260611103324_games.sql`) — no
  migration, no pgTAP.
- The auto-saver's `acceptMove → sync` ordering ([GameAutoSaver.kt:29,42](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt)) is the §6.2
  durable-write invariant the finish path must extend, not bypass.
- Finished games already route to Replay; `PromotionPicker` ([PromotionPicker.kt](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/board/PromotionPicker.kt)) is the
  reusable `Dialog` pattern for the result picker.

## What We're NOT Doing

- **No new schema / migration / pgTAP** — `status`/`result` columns and CHECK constraints exist.
- **No draw-by-rule auto-detection** (threefold / 50-move / insufficient material) — out of MVP per
  FR-007; those close via the manual path only.
- **No un-finish / resume of a finished game** — finishing is irreversible in MVP (read-only →
  Replay). No "edit result" screen either (the confirmation step is the guard).
- **No resignation/termination reason tag** in PGN — `[Result]` only; no extra custom tag.
- **No physical mode** (S-06) — this slice is digital-only, though the closure path is built so the
  shared record/journal stays reusable by S-06.
- **No deletion**, takeback, or game-over animations/sounds.
- **No changes to S-01/S-02/S-03 surfaces** beyond what the routing/Replay already do with a result.

## Implementation Approach

Bottom-up along reuse boundaries, mirroring S-02/S-04: prove the pure-domain mapping + PGN result
first, then the durable/offline-safe persistence, then the ViewModel choreography (auto + manual),
then the screen + navigation, then three-surface E2E and doc write-backs.

The closure is one logical operation expressed end-to-end: map state → `GameResult`, rebuild
`PgnMeta` with the result token, re-serialise the PGN, hand it to a **finish-aware** journal write
(the §6.2 durable gate), update UI to a frozen finished state, and launch a best-effort cloud flush
that targets `finishGame` (which writes `status` + `result` + `pgn` atomically). Cleanup of the
journal entry happens only after the finish is confirmed in the cloud, so an offline finish survives
a crash and re-syncs via `reconcile` on the next load.

## Critical Implementation Details

- **Terminal ≠ finished.** S-04's `terminal` flag (mate/stalemate) freezes the board but the record
  is still open. S-05 introduces a distinct *finished* state that can also be reached manually on a
  non-terminal position. Model finished as `PlayUiState.Playing.result: GameResult?` (non-null ⇒
  finished ⇒ frozen + final banner + actions); keep `terminal` for the engine classification. A
  manual end on a non-terminal position freezes via `result != null`, not via `terminal`.

- **Offline-safe finish ordering (extends §6.2).** The finish must be durable before it counts:
  `map result → rebuild meta → writePgn (with token) → synchronous journal write carrying the
  result → UI update → launch best-effort flush`. The flush calls `finishGame(id, result, pgn)`; the
  journal entry is cleared **only after** the flush is confirmed. `reconcile` on load must re-flush a
  journaled-but-unsynced finish (journal-ahead path) so an offline finish that crashed before flush
  still closes the cloud copy. The finished PGN already encodes the result in `[Result]`, so the
  journal additionally carries the `GameResult` to know which flush verb (`finishGame` vs
  `updatePgn`) to use.

- **Single-fire auto-finish.** `acceptMove` may compute a terminal status; closure must fire exactly
  once and be a no-op if the state is already finished (guard on `result == null`). Input is already
  blocked at terminal, but the guard protects against re-entrancy and a manual end racing an
  auto-finish.

- **Winner inference.** `Checkmate` ⇒ winner is `position.sideToMove.opposite`; `Stalemate` ⇒
  `DRAW`. The mapping is a pure, separately tested function — not inline ViewModel logic — and is
  proven on a Native target (Kotlin/Native differs from JVM; see lessons.md).

## Phase 1: Result mapping & PGN result serialisation

### Overview

Introduce the pure, testable mapping between engine/terminal state, the `GameResult` domain enum,
and PGN result tokens, and prove a finished PGN round-trips through `parsePgn` with its result
preserved. Pure `commonMain`, no persistence, no UI.

### Changes Required:

#### 1. Result mapping helpers

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/` (new file, e.g. `GameResultMapping.kt`)

**Intent**: Provide two pure mappings used by the finish path: (a) terminal engine state → result,
(b) result ↔ PGN token. Keeps winner-inference and token formatting out of the ViewModel and under
test on all three targets.

**Contract**: A function mapping a terminal `GameStatus` (engine, `domain/chess`) + side-to-move
`Color` → `GameResult` (`Checkmate` ⇒ `sideToMove.opposite`; `Stalemate` ⇒ `DRAW`; non-terminal ⇒
null/none), and a function `GameResult → PGN token`:

| `GameResult` | PGN token |
| --- | --- |
| `WHITE` | `1-0` |
| `BLACK` | `0-1` |
| `DRAW` | `1/2-1/2` |
| (in progress) | `*` |

Layering note: this couples `domain/games` to `domain/chess` (lower-level) — acceptable, the
dependency direction is games→chess. Keep the token strings consistent with `PgnParser`'s
`RESULT_TOKENS`.

#### 2. Finished-PGN round-trip coverage

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/domain/chess/pgn/` (extend existing PGN tests)

**Intent**: Prove that serialising with a real result token and re-parsing preserves the result and
the moves/positions — the property Replay relies on to display the outcome.

**Contract**: For each token, `parsePgn(writePgn(meta.copy(result = token), sans)).result == token`
and positions/SAN are unchanged; include a mate game (final SAN carries `#`), a stalemate game
(draw token, no suffix), an empty-movetext manual draw (`[Result "1/2-1/2"]` + bare terminator).

### Success Criteria:

#### Automated Verification:

- Mapping + round-trip tests pass on Android host: `:shared:testAndroidHostTest`
- Pass on iOS (Native): `:shared:iosSimulatorArm64Test`
- Pass on web: `:shared:wasmJsTest`
- ktlint clean (`ktlint -F` reports no changes)

#### Manual Verification:

- Eyeball a serialised mate game: header `[Result "1-0"]`, final move `...#`, terminator `1-0`.
- Eyeball a serialised 0-move manual draw: `[Result "1/2-1/2"]` and a bare `1/2-1/2` terminator.

**Implementation Note**: After this phase and all automated verification passes, pause for manual
confirmation before proceeding.

---

## Phase 2: Finalization persistence (repository + offline-safe journal)

### Overview

Add the atomic repository finalization and extend the journal/auto-saver so a finish is durable,
offline-safe, and cleaned up only after a confirmed cloud flush.

### Changes Required:

#### 1. Repository finalization method

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GamesRepository.kt`
and `data/games/SupabaseGamesRepository.kt`

**Intent**: One method that transitions a game to finished and writes the result + final PGN in a
single round-trip, so the cloud row never sits half-finished (status set but PGN stale or vice
versa).

**Contract**: `suspend fun finishGame(id: String, result: GameResult, pgn: String)` →
PostgREST `UPDATE games SET status='finished', result=<token>, pgn=<pgn> WHERE id=$id` (RLS scopes
ownership; mobile never passes `user_id`). Serialise `result` to `"white"|"black"|"draw"` using the
existing DTO/string convention (mirror `parseResult`). Propagates exceptions like `updatePgn`.
This widens contract §3.2's "Mark finished" to also carry `pgn` (write-back in Phase 5).

#### 2. Journal carries the finished result

**File**: `domain/games/GameJournal.kt`, `data/journal/SettingsGameJournal.kt`

**Intent**: Let the write-ahead journal record that the latest journaled PGN is a *finished* one and
which result, so the flush picks `finishGame` over `updatePgn` and cleanup can target finished games.

**Contract**: Extend `JournalEntry` with a nullable finished result (null ⇒ in progress). `save`
gains the result; add `clear(gameId)` to remove an entry after a confirmed finished flush. Keep the
"dirty written before pgn" crash-ordering. `SettingsGameJournal` stores the result under a third
per-game key (alongside `pgn`/`dirty`).

#### 3. Finish-aware auto-saver

**File**: `domain/games/GameAutoSaver.kt`

**Intent**: Realise the offline-safe finish: a synchronous durable journal write is the acceptance
gate; the flush targets `finishGame`; the entry is cleared only after the flush confirms; a
journaled-but-unsynced finish re-flushes on load.

**Contract**: Add a finish entry point (e.g. `finishGame(gameId, result, pgn)`) that journals
`(pgn, result, dirty=true)` synchronously and raises `syncPending`. Extend `sync` so a dirty
*finished* entry flushes via `gamesRepository.finishGame(...)` then `journal.clear(gameId)` on
success (instead of `markSynced`); a dirty in-progress entry keeps the existing `updatePgn` path.
Extend `reconcile` so a journal-ahead **finished** entry re-flushes the finish; the cloud-wins
branch is unchanged (LWW per §3.4). Retry/backoff semantics unchanged.

### Success Criteria:

#### Automated Verification:

- Auto-saver/journal/repo tests pass on all three targets: `:shared:testAndroidHostTest`,
  `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest`
- Tests (with fakes) cover: finish flushes `finishGame` with the right token+pgn; offline finish
  stays dirty and re-flushes on the next `sync`; journal entry cleared only after a confirmed flush;
  `reconcile` re-flushes a journaled-but-unsynced finish; in-progress saves still use `updatePgn`
- ktlint clean

#### Manual Verification:

- Against the hosted project: finishing a game sets the row's `status='finished'`, `result`, and
  `pgn` (with matching `[Result]`) in one update.
- Finish offline (airplane mode), then reconnect → the row updates without a lost result.

**Implementation Note**: Pause for manual confirmation before proceeding (the offline + hosted
checks need a human).

---

## Phase 3: PlayViewModel finalization (auto + manual)

### Overview

Wire both closure paths into the ViewModel: auto-finish on terminal, manual end with a confirmation
sub-state, frozen finished UI state, and finished-on-load rendering.

### Changes Required:

#### 1. Finished + pending-end state

**File**: `presentation/play/PlayViewModel.kt` (`PlayUiState.Playing`)

**Intent**: Represent a finished game and an in-flight manual-end confirmation without a separate
top-level state (board/move-list are reused).

**Contract**: Add `result: GameResult?` to `Playing` (non-null ⇒ finished ⇒ board frozen + final
banner + actions) and a manual-end UI flag (e.g. `pendingEndGame: Boolean` or a small sub-state for
"picker open" vs "confirm pending"). `onSquareTap` no-ops when `result != null` (alongside the
existing `terminal`/promotion guards).

#### 2. Auto-finish in the acceptance path

**File**: `presentation/play/PlayViewModel.kt` (`acceptMove`)

**Intent**: After an accepted move that yields checkmate/stalemate, close the game automatically,
exactly once.

**Contract**: When `status(nextPosition)` is terminal and `result == null`: map to `GameResult`,
rebuild `meta` with the result token, `writePgn`, route through the auto-saver finish entry point,
and set `result` in the new state. Single-fire guard. The non-terminal path is unchanged
(`acceptMove → updatePgn`).

#### 3. Manual end intents

**File**: `presentation/play/PlayViewModel.kt`

**Intent**: Expose the manual-end flow as plain MVVM intents (lessons.md: MVVM default).

**Contract**: `onEndGameRequest()` (open picker), `onResultPick(result: GameResult)` (advance to
confirm), `onConfirmEndGame()` (finalize: rebuild meta with token, writePgn, auto-saver finish, set
`result`), `onEndGameDismiss()` (cancel — game stays in progress). Finalization reuses the Phase-2
finish path; available only while in progress.

#### 4. Finished-on-load

**File**: `presentation/play/PlayViewModel.kt` (`load`)

**Intent**: Defensive rendering if a finished record is ever opened in Play (routing normally sends
finished → Replay, but guard the race).

**Contract**: If the loaded `record.status == FINISHED`, set `Playing.result = record.result` so the
screen renders frozen with the final banner + actions.

### Success Criteria:

#### Automated Verification:

- ViewModel tests pass on all three targets (`:shared:testAndroidHostTest`,
  `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest`)
- Tests cover: checkmate by White → `result=WHITE` + PGN `1-0`; checkmate by Black → `BLACK`/`0-1`;
  stalemate → `DRAW`/`1/2-1/2`; each manual result via request→pick→confirm; cancel keeps game in
  progress; input blocked after finish; single-fire (no double close); finished-on-load renders
  frozen
- ktlint clean

#### Manual Verification:

- (Deferred to Phase 4 — VM has no UI of its own.)

**Implementation Note**: Pause for manual confirmation before proceeding.

---

## Phase 4: End-game UI & navigation

### Overview

Add the on-screen end-game affordance, the result picker + confirmation dialog, the finished banner
+ actions, and the post-finish navigation callbacks.

### Changes Required:

#### 1. End-game button + result picker dialog

**File**: `presentation/play/PlayScreen.kt` (+ a new picker composable, sibling of
`presentation/board/PromotionPicker.kt`)

**Intent**: Always-visible "End game" button under the board while in progress; tapping opens a
3-option result picker (White wins / Black wins / Draw); choosing advances to a confirmation step
before finalizing. Reuses the `Dialog { Surface { ... } }` pattern.

**Contract**: Button added to `PlayingContent`'s column (hidden once `result != null`), wired to
`onEndGameRequest`. New dialog composable takes the three result options + `onPick`/`onConfirm`/
`onDismiss`; mirror `PromotionPicker`'s split (a previewable inner surface). Confirmation copy makes
the irreversibility clear.

#### 2. Finished banner + actions

**File**: `presentation/play/PlayScreen.kt` (`StatusBanner` + `PlayingContent`)

**Intent**: When finished, the banner shows the final result and an action row offers Analyse / Back
to history; the board is already frozen via `result != null`.

**Contract**: `StatusBanner` renders the final result for a finished game (reuse the existing
"… wins" / "draw" phrasing, including the manual case). Add an actions row (Analyse, Back to
history) shown only when finished, wired to new screen callbacks.

#### 3. Navigation callbacks

**File**: `presentation/play/PlayScreen.kt` signature + `App.kt` (`entry<PlayKey>`)

**Intent**: Let a finished Play screen route to Replay or back to History.

**Contract**: `PlayScreen` gains `onReviewGame: () -> Unit` and `onBackToHistory: () -> Unit`.
`App.kt` wires `onReviewGame = { backStack … ReplayKey(gameId) }` (replacing Play with Replay so
back doesn't return to a frozen Play) and `onBackToHistory = { backStack … HistoryKey }`. Reopening
the finished game from History already routes to Replay (unchanged).

### Success Criteria:

#### Automated Verification:

- All three targets build: `:shared` compiles for Android/iOS/web (e.g. `:androidApp:assembleDebug`,
  `:webApp` build, iOS framework build)
- Per-target test suites still green
- ktlint clean

#### Manual Verification:

- "End game" visible only while in progress; opens the 3-option picker; confirm finalizes; cancel
  leaves the game playable.
- After finishing (manual): banner shows the chosen result, board frozen, End-game button gone,
  Analyse → Replay (correct result), Back to history → History (game listed as finished).
- After checkmate/stalemate: banner becomes the final result automatically, End-game button gone,
  same actions available.

**Implementation Note**: Pause for manual confirmation before proceeding.

---

## Phase 5: Three-surface E2E & write-backs

### Overview

Prove the closure end-to-end on Android, iOS, and web against the hosted backend, and sync the
documentation surfaces.

### Changes Required:

#### 1. Three-surface E2E

**File**: (manual test pass — no production code; fixes land where they belong if found)

**Intent**: Confirm the full closure works on all three surfaces against the hosted project.

**Contract**: On Android, iOS, and web: (a) play to checkmate → game closes, `[Result]` correct,
History shows the outcome, reopen → Replay shows the result; (b) manual draw/resign via the picker;
(c) finish offline then reconnect → cloud row updates with no lost result; (d) a finished game is
read-only and opens in Replay.

#### 2. Documentation write-backs

**File**: `context/foundation/roadmap.md`, `docs/reference/contract-surfaces.md`,
`context/changes/game-end-and-result/change.md`, `context/foundation/lessons.md` (if a reusable rule
emerged)

**Intent**: Keep the foundation contracts in sync with what shipped.

**Contract**: Roadmap S-05 status → implemented (+ Streams note); contract-surfaces §3.2 amended so
"Mark finished" carries `pgn` (atomic `status`+`result`+`pgn` update), dated; `change.md` status →
implemented; add a lessons.md entry only if the offline-safe-finish-via-journal pattern is worth
generalising for S-06.

### Success Criteria:

#### Automated Verification:

- Full per-target suites green: `:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`,
  `:shared:wasmJsTest`
- Web production build succeeds; ktlint clean

#### Manual Verification:

- All four E2E scenarios pass on Android, iOS, and web.
- `parsePgn(stored_pgn).result` matches the row `result`; Replay's `PlayerLine` shows the outcome.
- Roadmap, contract-surfaces, and change.md write-backs are present and accurate.

**Implementation Note**: Final manual confirmation closes the slice.

---

## Testing Strategy

### Unit Tests:

- Result mapping: checkmate (White-mates and Black-mates), stalemate→draw, non-terminal→none;
  `GameResult` ↔ PGN token both directions.
- PGN round-trip with each result token, incl. a mate game (`#` final move) and an empty-movetext
  manual draw.
- Auto-saver/journal: finish flush via `finishGame`; offline finish stays dirty + re-flushes;
  cleanup only after confirmed flush; `reconcile` re-flushes a journaled finish; in-progress path
  unchanged.
- ViewModel: auto-finish results + PGN; manual request→pick→confirm + cancel; input blocked after
  finish; single-fire; finished-on-load.

### Integration Tests:

- ViewModel + fake repository + fake journal: a full accept-move-to-checkmate sequence produces a
  finished state and a `finishGame` call with the right token and PGN.

### Manual Testing Steps:

1. Play to checkmate on each surface → game auto-closes; History shows the winner; reopen → Replay
   shows `1-0`/`0-1`.
2. Reach stalemate → auto-closes as draw.
3. Mid-game, tap End game → pick a result → confirm → game closes; cancel instead → game continues.
4. Finish offline (airplane mode) → reconnect → hosted row reflects the result.
5. Confirm a finished game is read-only and opens in Replay (no End-game button, no input).

## Performance Considerations

Closure is a one-shot, off the move-acceptance hot path; map/serialise are pure and synchronous
(well under the 500 ms NFR). The cloud flush is best-effort and asynchronous, identical to the
existing move sync. No new hotspots.

## Migration Notes

No schema change. `games.status` and `games.result` (with CHECK constraints) already exist
(`20260611103324_games.sql`). Existing in-progress games are unaffected; only new finishes write
`status='finished'`. No data backfill.

## References

- Roadmap slice: `context/foundation/roadmap.md` (S-05)
- Prerequisite plan: `context/changes/digital-pass-and-play/plan.md` (S-04 — journal, auto-saver,
  Play screen, PGN writer)
- Engine plan: `context/changes/chess-rules-engine/plan.md` (F-01 — `status`)
- Contracts: `docs/reference/contract-surfaces.md` §2.2 (schema), §3.2 (CRUD / Mark finished), §5.5
  (in-progress vs finished transitions)
- PRD: FR-007 (auto mate/stalemate), FR-018 (manual end), US-01
- Key code: [PlayViewModel.kt](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/play/PlayViewModel.kt),
  [PgnWriter.kt](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/pgn/PgnWriter.kt),
  [GameAutoSaver.kt](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt),
  [GamesRepository.kt](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GamesRepository.kt)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Result mapping & PGN result serialisation

#### Automated

- [x] 1.1 Mapping + round-trip tests pass on Android host (`:shared:testAndroidHostTest`) — e266455
- [x] 1.2 Pass on iOS / Native (`:shared:iosSimulatorArm64Test`) — e266455
- [x] 1.3 Pass on web (`:shared:wasmJsTest`) — e266455
- [x] 1.4 ktlint clean — e266455

#### Manual

- [x] 1.5 Serialised mate game reads `[Result "1-0"]`, final move `#`, terminator `1-0` — e266455
- [x] 1.6 Serialised 0-move manual draw reads `[Result "1/2-1/2"]` + bare `1/2-1/2` terminator — e266455

### Phase 2: Finalization persistence (repository + offline-safe journal)

#### Automated

- [x] 2.1 Auto-saver/journal/repo tests pass on Android host (`:shared:testAndroidHostTest`) — bbedd14
- [x] 2.2 Pass on iOS / Native (`:shared:iosSimulatorArm64Test`) — bbedd14
- [x] 2.3 Pass on web (`:shared:wasmJsTest`) — bbedd14
- [x] 2.4 Tests cover finish flush, offline re-flush, cleanup-after-confirm, reconcile re-flush, in-progress path unchanged — bbedd14
- [x] 2.5 ktlint clean — bbedd14

#### Manual

> Deferred to Phase 5 (decided 2026-06-16): 2.6/2.7 need the end-game UI (Phase 3/4) to drive a real
> finish against the hosted backend, so they are exercised in Phase 5's three-surface E2E (the
> offline-finish flush scenario covers both). They stay `- [ ]` until then.

- [ ] 2.6 Hosted row sets `status='finished'` + `result` + `pgn` in one update
- [ ] 2.7 Offline finish then reconnect updates the hosted row without a lost result

### Phase 3: PlayViewModel finalization (auto + manual)

#### Automated

- [x] 3.1 ViewModel tests pass on Android host (`:shared:testAndroidHostTest`) — 18df392
- [x] 3.2 Pass on iOS / Native (`:shared:iosSimulatorArm64Test`) — 18df392
- [x] 3.3 Pass on web (`:shared:wasmJsTest`) — 18df392
- [x] 3.4 Tests cover auto-finish results+PGN, manual request→pick→confirm, cancel, input-blocked, single-fire, finished-on-load — 18df392
- [x] 3.5 ktlint clean — 18df392

### Phase 4: End-game UI & navigation

#### Automated

- [ ] 4.1 `:shared` + apps build on all three targets
- [ ] 4.2 Per-target test suites still green
- [ ] 4.3 ktlint clean

#### Manual

- [ ] 4.4 End-game button visible only while in progress; picker opens; confirm finalizes; cancel keeps game playable
- [ ] 4.5 Manual finish: final banner, frozen board, Analyse→Replay (correct result), Back→History (finished)
- [ ] 4.6 Checkmate/stalemate: banner becomes final result automatically, same actions available

### Phase 5: Three-surface E2E & write-backs

#### Automated

- [ ] 5.1 Full per-target suites green (`:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest`)
- [ ] 5.2 Web production build succeeds; ktlint clean

#### Manual

> Reminder: also run the Phase-2 manual checks deferred here — 2.6 (atomic finished+result+pgn
> UPDATE) and 2.7 (offline finish then reconnect, no lost result). The offline-finish flush in 5.3
> exercises both.

- [ ] 5.3 E2E (auto-mate, manual draw, offline-finish flush, finished→Replay) passes on Android, iOS, and web
- [ ] 5.4 `parsePgn(stored_pgn).result` matches row `result`; Replay `PlayerLine` shows the outcome
- [ ] 5.5 Roadmap, contract-surfaces §3.2, and change.md write-backs present and accurate
