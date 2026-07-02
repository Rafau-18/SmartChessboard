# Delete Game from History Implementation Plan

## Overview

S-11 (`delete-game-from-history`): let a signed-in player permanently delete one of their own games from the History list — any status (in-progress or finished), on Android, iOS, and the web target, through one shared code path. This is the first destructive user action in the app: the load-bearing parts are the explicit confirmation gate, owner-scoping (already enforced by RLS), and making sure a deleted game cannot resurrect from the local journal on the next reconcile.

PRD refs: FR-021, US-04. Backend is already complete — the RLS policy `games_delete_own` and the `DELETE FROM games WHERE id = $1` contract surface have existed since S-01 (`docs/reference/contract-surfaces.md` §3.2). No schema or migration change. All work is client-side plus one contract-doc amendment.

## Current State Analysis

- **No delete anywhere in the client.** `GamesRepository` (`shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GamesRepository.kt:5-49`) exposes `changes`, `listMyGames`, `getGame`, `createGame`, `updatePgn`, `finishGame` — no `deleteGame`.
- **Push-driven refresh is already wired.** `SupabaseGamesRepository` (`data/games/SupabaseGamesRepository.kt:55-58`) emits `_changes.tryEmit(Unit)` after `createGame` and `finishGame`; `HistoryViewModel` (`presentation/history/HistoryViewModel.kt:31-40`) collects `changes` and calls `refresh()`. A delete only needs to emit the same signal — the list refreshes itself.
- **The local journal already has the cleanup primitive.** `SettingsGameJournal` (`data/journal/SettingsGameJournal.kt`) stores three keys per game (`journal.<id>.pgn/.dirty/.result`); `GameJournal.clear(gameId)` (`domain/games/GameJournal.kt:35`) removes all three. Today `clear` fires only after a confirmed finished flush in `GameAutoSaver.sync` (`domain/games/GameAutoSaver.kt:79-80`).
- **Resurrection risk is real but narrow.** `GameAutoSaver.reconcile` (`GameAutoSaver.kt:111-120`) re-flushes a dirty journal entry that is ahead of the cloud — via `updatePgn`/`finishGame`, both plain `UPDATE`s (never `INSERT`), so a deleted row cannot be re-created by sync; a stale journal entry is the only leftover. Clearing the journal at delete time closes the gap entirely.
- **The delete-vs-in-flight-sync race is benign.** A Postgrest `UPDATE` matching zero rows succeeds silently, so a sync that loses the race with a delete neither errors nor recreates anything.
- **History is cloud-only.** `listMyGames()` is a Supabase query; there is no local games list. Offline, the History screen already shows its Error state — an offline delete queue would protect an operation the user can't even reach offline.
- **UI patterns to follow exist.** Confirmation dialog: `EndGamePicker` (`presentation/play/EndGamePicker.kt` — `Dialog` wrapper, irreversibility copy, Cancel/confirm button row). In-flight mutation: `NewGameScreen`/`NewGameViewModel` (`creating` flag disables inputs, `CircularProgressIndicator` inside the button, failure text in `colorScheme.error`, button text flips to "Retry"). Destructive coloring: `MaterialTheme.colorScheme.error`/`onError` (used on PhysicalPlayScreen's Reconnect button).
- **No row-level actions exist anywhere yet.** `GameRow` (`presentation/history/HistoryScreen.kt:131-164`) is a plain clickable `Column`. The kebab menu introduced here is the app's first `DropdownMenu`.
- **Tests.** `FakeGamesRepository` (`shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/FakeGamesRepository.kt`) uses call-capture lists + `shouldFail`/`failure` toggles; `HistoryViewModelTest` uses `StandardTestDispatcher` + `Dispatchers.setMain`. Test targets: `:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest`. No Compose UI tests exist — screen behavior is covered by the manual gate.
- **Lessons in force**: every wasm-reachable network call site must `catch (Throwable)` (rethrowing `CancellationException`); refresh-on-return must be push-driven; MVVM is the default presentation pattern.

## Desired End State

Each History row has a kebab (⋮) menu with a "Delete" item. Choosing it opens a confirmation dialog naming the game and stating the action is permanent. Confirming shows an in-flight spinner, deletes the cloud row, clears the game's local journal entry, and the row disappears from the list (via the `changes` signal). Cancelling leaves the game untouched. On failure the dialog stays open with an error message and Retry. Behavior is identical on Android, iOS, and web; deleted games no longer appear on any signed-in device.

Verification: unit tests green on all three test targets; manual gate on all three surfaces (delete finished + in-progress games, cancel, offline failure + retry, cross-device disappearance, no resurrection after restart).

### Key Discoveries:

- `GameJournal.clear(gameId)` already exists (`domain/games/GameJournal.kt:35`) — delete only needs to call it, not build it.
- Re-flush from reconcile uses `UPDATE` only (`GameAutoSaver.kt:72-75` → `SupabaseGamesRepository.kt:106-135`) — sync can never re-create a deleted row; journal cleanup is about hygiene and future-proofing, not a live resurrection bug.
- `_changes` emission after mutation (`SupabaseGamesRepository.kt:104,134`) is the established way to make History refresh — no new refresh mechanism needed.
- `GameAutoSaver` (`domain/games/GameAutoSaver.kt`) is the precedent for a domain class composing `GamesRepository` + `GameJournal` — the delete orchestrator follows the same shape.
- Postgrest `delete`/`update` on zero matching rows succeeds silently — deleting an already-deleted game (e.g. raced from another device) is an idempotent success; the list refresh removes the stale row.

## What We're NOT Doing

- **No offline delete queue / tombstones.** Delete is online-only for MVP; failure is surfaced, nothing is removed locally until the cloud confirms. `contract-surfaces.md` §3.4 is amended accordingly (Phase 1). The queued-delete design remains a post-MVP option.
- **No bulk or multi-select delete** (PRD Non-Goal) — one game at a time.
- **No trash, undo, restore, or archive** (PRD Non-Goal) — hard delete.
- **No delete affordance on Play/Replay screens** (US-04: history surface only).
- **No `position_evals` cleanup** — global cache keyed by FEN, shared across users (contract §3.2 note).
- **No snackbar/Scaffold infrastructure** — errors render inside the confirmation dialog.
- **No localization system** — strings stay hardcoded in composables per current convention.

## Implementation Approach

Follow the existing seams end to end. Phase 1 builds the invisible path: `GamesRepository.deleteGame(id)` (Postgrest `DELETE` + `changes` emission) plus a small domain orchestrator `GameDeleter` (cloud delete first, then `journal.clear`), registered in Koin, fully unit-tested, with the §3.4 contract amendment. Phase 2 makes it visible: `HistoryViewModel` gains a delete-prompt state machine (request → confirm → deleting → done/failed), `HistoryScreen` gains the kebab menu and the confirmation dialog modeled on `EndGamePicker`, with the `NewGame`-style in-flight/failure treatment. The user decision to use a kebab menu (rather than a bare delete icon) deliberately leaves room for future per-game actions — e.g. a potential digital↔physical game conversion.

## Critical Implementation Details

- **Ordering: cloud delete strictly before journal clear.** If the `DELETE` fails, the journal entry must be left untouched — an in-progress game's dirty entry is its durability guarantee, and a failed delete must not degrade it. Never clear optimistically.
- **The race with an in-flight auto-save needs no handling.** `sync`'s `UPDATE` against a just-deleted row matches zero rows and succeeds silently; it cannot recreate the row. Do not add locking.
- **wasm error shape.** The delete call is wasm-reachable; the ViewModel must `catch (Throwable)` (rethrowing `CancellationException` first), per the standing lesson — and the test suite must cover a non-`Exception` `Throwable`.

## Phase 1: Domain & data delete path

### Overview

Add the delete capability below the UI: repository method, domain orchestrator that also clears the journal, DI wiring, fakes and unit tests, and the §3.4 contract amendment. No user-visible change after this phase.

### Changes Required:

#### 1. Repository interface

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GamesRepository.kt`

**Intent**: Add the delete operation to the games contract so the domain layer can remove a cloud row.

**Contract**: `suspend fun deleteGame(id: String)` — permanently deletes one own game (contract §3.2); caller never passes a user id (RLS scopes ownership); deleting an already-gone row is an idempotent success; emits [changes] on success so the History list re-fetches; requires connectivity, failures propagate. Extend the `changes` KDoc to name deletion as a third emitting mutation.

#### 2. Supabase implementation

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/games/SupabaseGamesRepository.kt`

**Intent**: Implement `deleteGame` with a Postgrest `delete` filtered by `id`, following the shape of the existing mutations, and emit `_changes.tryEmit(Unit)` after success (mirrors `finishGame`).

**Contract**: `DELETE FROM games WHERE id = $1` per contract §3.2; no user_id from the client; emission happens only after the call returns without throwing.

#### 3. Domain orchestrator

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameDeleter.kt` (new)

**Intent**: One domain entry point that performs the full delete: cloud row first, then `journal.clear(gameId)` — so no caller can forget the journal half and leave a resurrection-shaped leftover. Modeled on `GameAutoSaver` (same dependency pair: `GamesRepository` + `GameJournal`).

**Contract**: `class GameDeleter(gamesRepository, journal)` with `suspend fun delete(gameId: String)`. Ordering invariant: `journal.clear` runs only after the cloud delete succeeds; on failure the exception propagates and the journal is untouched.

#### 4. DI registration

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/di/AppModules.kt`

**Intent**: Register `GameDeleter` in the existing Koin module so `HistoryViewModel` (Phase 2) can take it as a constructor dependency.

**Contract**: Follows the existing factory/single registration pattern used for `GameAutoSaver`.

#### 5. Test fakes

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/FakeGamesRepository.kt`

**Intent**: Extend the fake with `deleteGame` support following its call-capture convention (`deleteGameCalls` list, honor `shouldFail`/`failure`, emit `_changes` on success).

**Contract**: Same observable-behavior surface as the real implementation so ViewModel tests can assert what was requested.

#### 6. Unit tests

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/domain/GameDeleterTest.kt` (new; reuse the existing journal fake/in-memory settings used by the `GameAutoSaver` tests)

**Intent**: Pin the orchestrator's invariants: success deletes cloud then clears all journal keys; failure propagates and leaves the journal entry (including a dirty one) intact; delete of a game with no journal entry succeeds.

**Contract**: Covers the ordering invariant from Critical Implementation Details.

#### 7. Contract amendment

**Files**: `docs/reference/contract-surfaces.md`, `context/foundation/prd.md`

**Intent**: Amend the §3.4 delete bullet to match the implemented MVP: delete is **online-only** (cloud `DELETE` first; on success the local journal entry is dropped; offline the action fails visibly and nothing is removed). Note the queued-delete/tombstone design as post-MVP. Rationale: the history list itself is cloud-only, so delete is unreachable offline. Per `contract-surfaces.md`'s own Change control rule (a §3.4 change mirrors into `prd.md`), also append a one-line dated clause to the existing 2026-07-02 CRUD-completion bullet in `prd.md`'s Implementation Decisions recording the online-only decision.

**Contract**: §3.4 bullet text in `contract-surfaces.md`; one dated clause appended to the 2026-07-02 CRUD-completion bullet in `prd.md`'s Implementation Decisions. §3.2 delete row is already accurate and stays.

### Success Criteria:

#### Automated Verification:

- JVM tests pass: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- iOS tests pass: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:iosSimulatorArm64Test --console=plain --no-daemon`
- Web tests pass: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:wasmJsTest --console=plain --no-daemon`
- ktlint clean: `ktlint` from `SmartChessboard/` reports no violations on touched files

#### Manual Verification:

- None — no user-visible change in this phase; the end-of-slice manual gate lives in Phase 2.

**Implementation Note**: After completing this phase and all automated verification passes, commit per the ritual. No manual gate here — proceed to Phase 2 in the same session if time allows.

---

## Phase 2: History presentation — kebab menu, confirmation dialog, delete state

### Overview

Surface the delete on the History screen: per-row kebab menu, confirmation dialog with irreversibility copy, in-flight spinner, inline failure + Retry. ViewModel drives everything; the list refresh arrives via the existing `changes` collection.

### Changes Required:

#### 1. ViewModel delete state machine

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/history/HistoryViewModel.kt`

**Intent**: Add a delete-prompt state alongside the existing list state (the list `HistoryUiState` stays untouched), plus intent methods, injecting `GameDeleter`.

**Contract**: A second `StateFlow<DeletePromptState?>` where `data class DeletePromptState(val game: GameSummary, val deleting: Boolean = false, val failed: Boolean = false)`. Intents: `requestDelete(game)` (opens the prompt), `dismissDelete()` (closes it; ignored while `deleting`), `confirmDelete()` (idempotent while in flight; sets `deleting`, calls `GameDeleter.delete`, on success clears the prompt — the list refreshes itself via the repository `changes` signal; on failure sets `failed = true`, `deleting = false`; also serves as Retry). Failure handling must `catch (Throwable)` and rethrow `CancellationException` (wasm lesson).

#### 2. Row affordance + confirmation dialog

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/history/HistoryScreen.kt`

**Intent**: `GameRow` is currently a single clickable `Column` wrapping its `Text`/`Spacer` children — restructure it into a `Row` (the existing text content in a `Modifier.weight(1f)` inner `Column` retaining the row's click, plus a trailing kebab (⋮) `IconButton` as a sibling) opening a `DropdownMenu` with a single "Delete" item (menu-expanded state is local `remember` state per row; the kebab must not trigger the row's own click — Compose's nested-clickable hit-testing already gives the `IconButton` first claim on its own taps once it's a sibling, not nested inside the clickable text `Column`). Add a `DeleteGameDialog` composable modeled on `EndGamePicker`: title ("Delete game?"), body naming the matchup and stating permanence ("This permanently deletes <white> vs <black>. This can't be undone."), Cancel `TextButton` + Delete `Button` in `colorScheme.error`/`onError`; while `deleting`, buttons disable and the Delete button shows the `NewGame`-style `CircularProgressIndicator`; when `failed`, an error line renders in `colorScheme.error` and the button text flips to "Retry".

**Contract**: Dialog visibility is driven solely by the ViewModel's `DeletePromptState?` (state-flag pattern, like `EndGamePicker` on PhysicalPlayScreen). Strings hardcoded per convention. The menu item is available for every row regardless of status or mode.

#### 3. ViewModel tests

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/HistoryViewModelTest.kt` (+ a small `GameDeleter`-facing fake or the real one over fakes)

**Intent**: Pin the state machine: request opens the prompt; dismiss closes it and deletes nothing; confirm success calls the deleter with the right id, closes the prompt, and the list drops the row after the `changes` emission; confirm failure keeps the prompt with `failed = true`; retry succeeds after a failure; `dismissDelete` during `deleting` is ignored; a non-`Exception` `Throwable` (wasm shape) lands in the failed state, not a crash.

**Contract**: Same test harness as today (`StandardTestDispatcher`, fakes with call capture).

### Success Criteria:

#### Automated Verification:

- JVM tests pass: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- iOS tests pass: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:iosSimulatorArm64Test --console=plain --no-daemon`
- Web tests pass: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:wasmJsTest --console=plain --no-daemon`
- ktlint clean: `ktlint` from `SmartChessboard/` reports no violations on touched files

#### Manual Verification:

- Android: delete a finished game and an in-progress game via kebab → confirm; rows disappear without a manual refresh; cancel leaves the game; airplane-mode delete shows the inline error, Retry succeeds after reconnect
- iOS: same flow as Android (kebab, confirm, cancel, offline error + retry)
- Web: same flow (mouse-driven kebab menu works; delete succeeds; offline failure surfaces gracefully — no crash, per the wasm `Throwable` lesson)
- Cross-device: a game deleted on device A no longer appears on device B after its next History refresh
- No resurrection: delete an in-progress game that has local moves (dirty journal entry), restart the app, confirm the game does not reappear in History or in the cloud

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before closing the slice. Phase blocks use plain bullets — the corresponding `- [ ]` checkboxes live in the `## Progress` section at the bottom.

---

## Testing Strategy

### Unit Tests:

- `GameDeleterTest` (Phase 1): cloud-then-journal ordering; failure leaves journal intact (including dirty entries); no-journal-entry delete succeeds; journal keys fully removed on success.
- `HistoryViewModelTest` extensions (Phase 2): full prompt state machine (request/dismiss/confirm/fail/retry/in-flight guard), list refresh via `changes` after delete, non-`Exception` `Throwable` failure shape.
- `FakeGamesRepository.deleteGame`: call capture + failure injection + `changes` emission, matching the real implementation's observable behavior.

### Integration Tests:

- None new — the Supabase call itself is covered by the manual gate (consistent with how `createGame`/`finishGame` are verified in this codebase).

### Manual Testing Steps:

1. On each surface (Android, iOS, web): open History → kebab on a finished game → Delete → confirm → row disappears.
2. Repeat for an in-progress game (digital; and physical on mobile) — same result.
3. Open the dialog and Cancel — game remains, nothing logged as deleted.
4. Go offline → attempt delete → inline error + Retry appears; reconnect → Retry succeeds.
5. Sign in on a second device/surface — the deleted game is absent after refresh.
6. Delete an in-progress game that has unsynced local moves, restart the app — the game does not reappear (journal was cleared).

## Performance Considerations

Single-row `DELETE` plus the existing list re-fetch — no new hot paths. The kebab adds one `IconButton` per row; no measurable list-rendering impact at MVP data volumes (hundreds of games).

## Migration Notes

No schema or data migration. Stale journal entries from games deleted *before* this feature ships cannot exist (there was no delete). The only doc change is the §3.4 amendment (Phase 1, change #7).

## References

- Change identity: `context/changes/delete-game-from-history/change.md`
- Roadmap item: `context/foundation/roadmap.md` §S-11
- PRD: FR-021, US-04 (`context/foundation/prd.md`)
- Contract surfaces: `docs/reference/contract-surfaces.md` §2.4, §3.2, §3.4
- Confirmation-dialog pattern: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/play/EndGamePicker.kt`
- In-flight mutation pattern: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/newgame/NewGameScreen.kt`
- Repo+journal domain precedent: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Domain & data delete path

#### Automated

- [x] 1.1 JVM tests pass (`:shared:testAndroidHostTest`)
- [x] 1.2 iOS tests pass (`:shared:iosSimulatorArm64Test`)
- [x] 1.3 Web tests pass (`:shared:wasmJsTest`)
- [x] 1.4 ktlint clean on touched files

### Phase 2: History presentation — kebab menu, confirmation dialog, delete state

#### Automated

- [ ] 2.1 JVM tests pass (`:shared:testAndroidHostTest`)
- [ ] 2.2 iOS tests pass (`:shared:iosSimulatorArm64Test`)
- [ ] 2.3 Web tests pass (`:shared:wasmJsTest`)
- [ ] 2.4 ktlint clean on touched files

#### Manual

- [ ] 2.5 Android: delete finished + in-progress, cancel, offline error + retry
- [ ] 2.6 iOS: delete finished + in-progress, cancel, offline error + retry
- [ ] 2.7 Web: full flow with mouse; offline failure degrades gracefully (no crash)
- [ ] 2.8 Cross-device: deleted game absent on second device after refresh
- [ ] 2.9 No resurrection: delete in-progress game with unsynced local moves, restart app, game stays gone
