<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Game End and Result (S-05)

- **Plan**: context/changes/game-end-and-result/plan.md
- **Scope**: Phases 1–5 of 5 (all phases complete; Phase 5 three-surface E2E landed 4 production fixes + doc write-backs)
- **Date**: 2026-06-16 (Phases 1–4); re-reviewed after Phase 5 on 2026-06-17
- **Verdict**: APPROVED
- **Findings**: 0 critical · 0 warnings · 4 observations

## Verification re-run during this review

| Check | Result |
|-------|--------|
| `:shared:testAndroidHostTest` | BUILD SUCCESSFUL at Phase 5 head (45aeb82); new GameAutoSaverTest + HistoryViewModelTest cases green |
| ktlint (source files) | clean — the 115 reported hits were all under `build/generated/*` |
| `:shared:iosSimulatorArm64Test` / `:shared:wasmJsTest` | not re-run; recorded green at commit SHAs (e266455, bbedd14, 18df392, b1e8a95, then through Phase 5: 64937c7, 653b013, 8c00bac, 7f541ab) |

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS (3 observations) |
| Architecture | PASS |
| Pattern Consistency | PASS (1 observation) |
| Success Criteria | PASS (Phase 5 three-surface E2E ran; deferred manual checks 2.6/2.7, 4.4–4.6 exercised) |

### Why APPROVED

All five critical-attention items from the plan were verified correct:

1. **Single-fire auto-finish** — guard `autoResult != null && state.result == null` plus an early `return` (`PlayViewModel.kt:284`). `result` is written into state synchronously before any suspension, so a second tap cannot re-enter. No re-entrancy hole.
2. **Offline-safe finish ordering** — journal-write → flush → `journal.clear()` only after `finishGame` returns successfully and the re-loaded PGN still matches (`GameAutoSaver.kt:76-79`); `reconcile` re-flushes a journaled-but-unsynced finished entry via `sync` (`GameAutoSaver.kt:100`). A finished result is not lost offline.
3. **finishGame DTO serialization** — `toResultColumn()` produces `white|black|draw`, the exact inverse of `parseResult` (`SupabaseGamesRepository.kt:174-179`).
4. **reconcile journal-ahead FINISHED path** — correctly re-closes the cloud copy; cloud-wins branch unchanged (LWW per §3.4).
5. **"What We're NOT Doing" guardrails** — no violations (no migration/schema/pgTAP, no draw-by-rule auto-detection, no un-finish/resume, no resignation/termination tag, no physical-mode code, no deletion/takeback/animations). Through Phase 4 the only cross-surface touch was `App.kt` routing; Phase 5's three-surface E2E then landed bounded, test-covered fixes that the plan pre-authorized ("fixes land where they belong if found") in History, Auth, NewGame, Play, Replay, and wasm BrowserNavigation — none of which crosses a NOT-doing boundary (see the Phase 5 sub-section).

`finishGame`/`updatePgn` call supabase-kt 3.6.0 `update()`, which is itself `suspend` and throws on failure — so the absence of a trailing `.decode*()` is **not** a silent-write bug. As of Phase 5 (8c00bac, 7f541ab) `sync` rethrows `CancellationException` then catches `Throwable` (a wasm Ktor fetch failure is a `kotlin.Error`, not an `Exception`). On failure an **in-progress** entry retries the bounded window then gives up and stays dirty for its next move's sync; a **finished** entry keeps retrying (backoff capped at the last delay) until it lands or the screen closes (see Phase 5 sub-section / Finding F3).

The manual checks 2.6/2.7 and 4.4–4.6 were deferred to Phase 5's three-surface E2E, which has now run (see below).

## Phase 5 — three-surface E2E fixes (added 2026-06-17)

Phase 5's three-surface E2E (Android, iOS, web) was not a no-op doc pass: it surfaced four real cross-platform defects in the closure flow, each fixed where it belongs. A 5-dimension re-review (one reviewer per fix + a scope/docs reviewer, each adversarially checked) returned **PASS on all five with zero CRITICAL/WARNING** — every Phase 5 finding is OBSERVATION/LOW. The fixes are pre-authorized by the plan's "fixes land where they belong if found" clause and none crosses a "What We're NOT Doing" boundary.

| Commit | Fix | Verdict |
|--------|-----|---------|
| `64937c7` | Push-driven History refresh via a new `GamesRepository.changes: SharedFlow<Unit>` (emitted on create/finish, **not** updatePgn), collected by the retained History ViewModel — replaces a composition effect that iOS skipped. No missed-refresh race: the repo is a Koin singleton and `HistoryKey` is the never-popped back-stack root, so the collector stays subscribed while Play/NewGame are on top. | PASS |
| `653b013` | wasm browser nav `Chronological → Hierarchical` so browser Back pops the live stack (previously Back landed on replaced-away screens). One-shot `BrowserHistoryIsInUse` limitation and "no URL→state restore on reload" both unchanged and consistent with Non-Goals. | PASS |
| `8c00bac` | Broadened `catch (Exception)` → `catch (Throwable)` across 5 ViewModels (a wasm Ktor fetch failure is a `kotlin.Error`, not an `Exception`) so offline no longer crashes the app — see **F4**. | PASS |
| `7f541ab` | A finished game's cloud flush keeps retrying (capped backoff) until reconnect/screen-close instead of giving up after ~7s — see **F3**. | PASS |

Doc write-backs (`c0e26a0`) verified accurate: roadmap S-05 → implemented (+ Stream B note); contract-surfaces §3.2 "Mark finished" now carries `pgn` (atomic status+result+pgn), dated; four well-formed lessons.md entries; plan Progress 5.1–5.5 + deferred 2.6/2.7/4.4–4.6 checked with SHAs. Scoping note: the new `GamesRepository.changes` is a mobile-internal reactive surface, correctly absent from contract-surfaces.md (which scopes out mobile-internal architecture).

## Findings

### F1 — Asymmetric movetext normalization in isAhead/reconcile

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Reliability)
- **Location**: SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt:136 (`movetext`); `isAhead` at :125. (Line numbers refreshed for Phase 5 — the finding itself is unchanged; 7f541ab did not touch either function.)
- **Detail**: `movetext()` strips only the in-progress terminator (`removeSuffix("*")`), never the finished tokens (`1-0` / `0-1` / `1/2-1/2`). So in `reconcile`/`isAhead` a finished journal (`"1. e4 e5 0-1"`) is compared against an in-progress cloud doc (`"1. e4 e5"`) and is judged "ahead" only because `journalMoves.startsWith("$cloudMoves ")` happens to hold. It works for every reachable path today (`finishGame` clears the journal on the one path that writes a finished cloud row, so a "finished journal vs finished cloud, same moves, different terminator" comparison never occurs). It is a latent fragility: the prefix invariant silently depends on which terminator is present, not on the moves. If a future path ever reconciles two finished docs, `isAhead` would return `false` and discard the journal copy.
- **Fix**: Strip all four result tokens in `movetext()` (or compare on the parsed move list) so the prefix check depends only on moves; add a reconcile test for "journal finished, cloud finished, same moves, different terminator".
- **Decision**: FIXED (2026-06-17) — `movetext()` now strips all result terminators and `isAhead` adds an explicit `isFinished()` check, so two finished docs with the same moves resolve by status (LWW, cloud wins) instead of by an accidental terminator prefix; same-moves finished-journal-vs-in-progress-cloud still re-flushes (the load-bearing case the naive strip-all would have broken). Regression test `reconcileWithTwoFinishedDocsSameMovesPrefersCloud` added. Green on Android host + iOS Native + wasm.

### F2 — File named EndGamePicker.kt, exported composable is EndGameDialog

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/play/EndGamePicker.kt
- **Detail**: The sibling baseline `PromotionPicker.kt` exports a composable named `PromotionPicker` — file name == public composable. The new file `EndGamePicker.kt` exports `EndGameDialog` instead, so a reader grepping for "EndGamePicker" finds the file but not the entry-point composable. Behaviour is correct; this is a discoverability nit only.
- **Fix**: Rename the file to `EndGameDialog.kt` (or rename the composable to `EndGamePicker`) so file and symbol match the `PromotionPicker` convention.
- **Decision**: FIXED (2026-06-17) — renamed the composable `EndGameDialog` → `EndGamePicker` and the inner surface `EndGameDialogSurface` → `EndGamePickerSurface` (file stays `EndGamePicker.kt`), and updated the call site in `PlayScreen.kt`. File == composable, matching the `PromotionPicker` convention and the plan's name. Shared module compiles; Android host tests green.

### F3 — Finished-game cloud flush now retries unbounded until reconnect (race-free by domain rule)

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — informational; no action needed
- **Dimension**: Safety & Quality (Reliability)
- **Location**: SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt:69-98 (give-up guard ~:94, backoff ~:95); commit 7f541ab
- **Detail**: The give-up guard changed from `if (failures == retryDelaysMs.size) return` to `if (entry.result == null && failures == retryDelaysMs.size) return`. An **in-progress** entry still gives up after `retryDelaysMs.size` (3) failures (= 4 cloud attempts) and stays dirty for its next move's sync; a **finished** entry never satisfies the left conjunct, so it retries indefinitely with backoff capped via `delay(retryDelaysMs[minOf(failures, retryDelaysMs.lastIndex)])` (no out-of-bounds) until it lands or the screen closes. Reason: a finished game has no next move to re-trigger sync, so a slow reconnect previously left "Saving…" spinning until the next load's reconcile. **Safe**: `catch (CancellationException) { throw }` precedes the broadened `Throwable` catch, so viewModelScope cancellation on screen-close breaks the loop at the cloud-call or `delay()` suspension point (no infinite spin on a dead screen). **Race-free**: a finished game accepts no further moves, so there is only ever one finished PGN and nothing newer to overwrite. Covered by `offlineFinishKeepsRetryingPastTheBoundedWindow…` (asserts 6 finishGame calls) and `offlineInProgressSaveStillGivesUpAtTheBoundedWindow` (asserts 4 updatePgn calls then dirty). Supersedes the Phase-1–4 "bounded 4 attempts / keep the entry dirty" framing for the finished path.
- **Fix**: None — behaviour is correct, intentional, and test-covered.
- **Decision**: ACCEPTED (informational)

### F4 — wasm network catch broadened from Exception to Throwable across data/presentation layers

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — informational; no action needed
- **Dimension**: Safety & Quality (Reliability) / Pattern Consistency
- **Location**: commits 8c00bac (AuthViewModel sign-in+sign-out, HistoryViewModel load+refresh, PlayViewModel load, ReplayViewModel load, NewGameViewModel create) and 7f541ab (GameAutoSaver.sync flush) — 8 call sites
- **Detail**: A wasm Ktor/Supabase network failure surfaces as `kotlin.Error` (a `Throwable`), not an `Exception`, so `catch (Exception)` missed it and it escaped as an uncaught coroutine exception — offline, any networked click crashed the app. Every network call site now catches `Throwable`. **Verified correct**: each `Throwable` catch is immediately preceded by `catch (CancellationException) { throw }`, so structured-concurrency cancellation is never swallowed; each maps to a coherent atomically-assigned state (load→Error, create→failed) or an intentional documented swallow (refresh keeps the loaded list; signOut relies on `sessionState`). A repo-wide grep finds zero remaining `catch (Exception)` in `presentation/` or `domain/games/`, and a regression test (`HistoryViewModelTest.loadMapsANonExceptionThrowableToError`, throwing `kotlin.Error`) was added. Matches the lessons.md "wasm fetch failure is Throwable" rule.
- **Fix**: None — correct and consistent. (Cosmetic-only: the ViewModels import `kotlin.coroutines.cancellation.CancellationException` while GameAutoSaver imports the `kotlinx.coroutines` typealias — same type, not worth a change.)
- **Decision**: ACCEPTED (informational)
