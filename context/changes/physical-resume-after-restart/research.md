---
date: 2026-06-20T00:02:00+02:00
researcher: Rafał Urbaniak
git_commit: b4b281017855db88e1ddaf3e7c1a46386322d470
branch: main
repository: smartchessboard (<user>/smartchessboard on Bitbucket)
topic: "Physical resume after restart (S-08 / FR-013)"
tags: [research, codebase, physical-mode, resume, persistence, reconcile, fr-013, s-08, fr-012]
status: complete
last_updated: 2026-06-20
last_updated_by: Rafał Urbaniak
---

# Research: Physical resume after restart (S-08 / FR-013)

**Date**: 2026-06-20T00:02:00+02:00
**Researcher**: Rafał Urbaniak
**Git Commit**: b4b281017855db88e1ddaf3e7c1a46386322d470
**Branch**: main
**Repository**: smartchessboard (<user>/smartchessboard on Bitbucket — no GitHub remote, so file references are local clickable `path:line`, not permalinks)

## Research Question

For roadmap slice **S-08 `physical-resume-after-restart`** (PRD **FR-013**, US-02): *resume an in-progress physical-mode game after an app restart on the same device — load the last persisted move, render the expected position, ask the player to confirm the physical board matches before re-enabling move acceptance, and route to live diagnostics (FR-011) to restore manually on mismatch, with no accepted move lost.*

Research scope agreed with the user (2026-06-20), comprehensive across four dimensions plus a sibling mapping:
1. **Reuse surface** from S-06/S-07 — how much of "render expected position + confirm board matches + recover via diagnostics" already exists.
2. **Process-death / cold-start lifecycle** — where the resume entry point hooks into app startup; what board/session state must be re-established.
3. **Durable persistence & crash-safety** — what survives a process kill mid physical game vs what is lost.
4. **Resume-specific risks & edge states** — promotion-pending at kill, paused/rejected state, the interpreter baseline.
5. **FR-012 sibling** (BLE reconnect-reconcile, parked nice-to-have) — map the shared "reconcile board vs expected position" machinery so S-08 builds it reusably for S-09/FR-012.

## Summary

**S-08 is mostly a wiring + gating slice, not a from-scratch build.** Nearly every primitive FR-013 needs already exists and is test-proven by the just-merged S-07 (`reject-recover-diagnostics`) and by S-06 (`physical-capture-emulated`). The contract's §6.3 ("App crash mid-game") is the canonical spec, and the current code implements it only partially.

What already exists and is directly reusable:

- **The crash-safety guardrail is already met.** Every accepted move is written to a **synchronous, durable local journal before the UI advances** (Android `commit = true`), so the last accepted move = the last journaled PGN = the resume point. The expected position is rebuilt from that PGN on every screen entry (`getGame → reconcile → parsePgn`).
- **The board↔expected-position reconcile primitive exists and is h8-safe.** `Position.toOccupancy(): Long` derives expected occupancy; the reducer compares it to a fresh board snapshot (`snapshot.occupancy == position.toOccupancy()`); the per-square `ReedDiagnosticsGrid` renders the diff; an exact match clears the recovery gate. This is exactly FR-013's "does the physical board match?" — no new domain code.
- **The `PhysicalPlay` screen can already open an in-progress, mid-PGN game with only a `gameId`**, and rebuilds the mid-game position correctly. Navigation keys are serializable and survive process death on Android/iOS.

The three precise gaps S-08 must close (each confirmed against the live code):

- **G1 — Acceptance is not gated on resume.** `acceptanceBlocked = paused || recovering` ([`PhysicalPlayContract.kt:92`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayContract.kt:92)). A resume/connect mismatch only sets `setupMismatch`, which auto-opens the diagnostics grid (`diagnosticsVisible`) but **does not block move acceptance**. The `Loaded` reducer arm renders the position and immediately allows play. FR-013 requires blocking acceptance until the board is confirmed/restored — today only a *rejected confirmation* enters that blocking state (`recovering`), never a resume.
- **G2 — Resume discovery and offline-open are cloud-gated.** Resume is user-initiated from the History list (`listMyGames()` = cloud fetch), and `PhysicalPlayViewModel.load()` does `getGame(gameId)` (cloud) *before* `reconcile`. Offline at launch → History fails / `LoadFailed`, and the durable journal PGN is never consulted. The "no accepted move lost" guardrail still holds, but the contract's "Mobile **detects** in-progress games and **offers to resume**" (§6.3) and offline resilience are unbuilt.
- **G3 — No explicit "confirm the board matches" affordance on resume**, distinct from recovering-from-a-reject. FR-013's user-facing confirmation step does not exist yet; the auto-confirm-on-match vs always-require-confirm UX is an open decision.

**FR-012 sibling:** FR-013 (resume-after-restart) and FR-012 (BLE reconnect-reconcile) are the *same* reconcile-against-expected-`Position` capability, specified word-for-word identically in `contract-surfaces.md` §1.7 and §6.3. S-08 should build the reconcile as a **shared transition keyed off "expected occupancy vs fresh snapshot"** (not inlined into the load path), so FR-012/S-09 reuses it by wiring the identical call into the `BoardConnected` reducer arm. S-07 explicitly chose the unified `acceptanceBlocked` gate as the reusable foundation for S-08/FR-012.

> **Headline correction to a stale prior:** there is **no Room / SQLite / OPFS local database** in the code. `lessons.md`'s "Room 3.0 + OPFS" entry describes a *planned* web-persistence surface that was never built — S-04 introduced a **multiplatform-settings key-value journal** as the first (and only) local durable store. Any S-08 plan assuming a queryable local `games` table is wrong; the durable local substrate is a KV journal keyed by `gameId` storing only PGN (+ `dirty` + `result`), and everything else (mode, status, labels) lives only in the Supabase cloud row.

## Detailed Findings

### Area 1 — App startup, session restore, and the resume entry point

**Root composable + session gate.** [`App.kt:38`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt:38) gates all navigation on `authViewModel.uiState.sessionState`, a `when` over a sealed `SessionState`:
- `Restoring` → `RestoringScreen()` spinner while the Supabase Auth plugin consumes a persisted session/OAuth callback ([`App.kt:42-44`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt:42)).
- `SignedOut` → `SignInScreen` ([`App.kt:47-55`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt:47)).
- `SignedIn(userId)` → the full nav stack, **rooted at `HistoryKey`** ([`App.kt:58-159`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt:58)).

`SessionState` is defined in [`domain/auth/AuthRepository.kt:1-27`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/auth/AuthRepository.kt:1); mapped from `client.auth.sessionStatus` in [`data/auth/SupabaseAuthRepository.kt:15-39`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/auth/SupabaseAuthRepository.kt:15) (`Initializing → Restoring`, `Authenticated → SignedIn`, else `SignedOut`).

**Why it matters for S-08:** the `Restoring → SignedIn` transition is the natural hook for "detect an in-progress physical game and offer to resume" (contract §6.3). Today the branch unconditionally lands on `HistoryKey`.

**Navigation 3 + back-stack persistence.** Routes in [`Routes.kt:15-39`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/navigation/Routes.kt:15): `HistoryKey`, `NewGameKey`, `ReplayKey(gameId)`, `PlayKey(gameId)`, **`PhysicalPlayKey(gameId)`**. All are `@Serializable` with **explicit polymorphic registration** in `navSavedStateConfiguration` ([`Routes.kt:48-60`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/navigation/Routes.kt:48)); the back stack is `rememberNavBackStack(navSavedStateConfiguration, HistoryKey)` ([`App.kt:59`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt:59)).

- **Android/iOS:** the serialized NavKeys survive process death — a `[HistoryKey, PhysicalPlayKey(id)]` stack is restorable. But only the **key** (the `gameId`) is restored; the ViewModel state is not, so the screen re-loads the game on recomposition. In practice the root is always seeded as `HistoryKey`, so the stack is effectively rebuilt from History.
- **Web (WasmJS):** a page reload discards the JS heap; the nav stack does **not** survive reload (the `HierarchicalBrowserNavigation` fragment maps Back/Forward only) — but physical mode is mobile-only, so web resume is out of scope (FR-020 / lessons.md).

**How an in-progress game is opened today.** `HistoryScreen.onGameClick` routes by status + mode ([`App.kt:81-97`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt:81)):
```kotlin
game.status == IN_PROGRESS && game.mode == PHYSICAL && supportsPhysicalBoard -> backStack.add(PhysicalPlayKey(game.id))
game.status == IN_PROGRESS && game.mode == DIGITAL              -> backStack.add(PlayKey(game.id))
else                                                            -> backStack.add(ReplayKey(game.id))
```
The list driving this is `listMyGames()` — a **cloud fetch**. There is **no auto-resume path on cold start**; resume is always a manual tap from History. Grep confirms "resume" appears only in comments; the actual mechanism is `reconcile` (Area 2).

**Platform entry points.** Android [`MainActivity.kt:11-19`](SmartChessboard/androidApp/src/main/kotlin/org/rurbaniak/smartchessboard/MainActivity.kt:11) receives but **ignores** `savedInstanceState` (only `handleAuthDeeplink` + `setContent { App() }`); iOS `MainViewController` and web `Main.kt` wrap `App()` with no platform-specific game-state restore. Nav3 saved-state is handled by the Compose framework, not by these entry points.

### Area 2 — Durable persistence & crash-safety (what survives a kill)

**No local database — a KV journal is the only durable local store.** Local durability = `SettingsGameJournal` over `com.russhwolf:multiplatform-settings`, with synchronous per-platform backing:
- Android `SharedPreferencesSettings(..., commit = true)` ([`PlatformModule.android.kt:25-30`](SmartChessboard/shared/src/androidMain/kotlin/org/rurbaniak/smartchessboard/di/PlatformModule.android.kt:25)) — the comment is load-bearing: *"the journal write must be durable before a move counts as accepted (§6.2); the default async apply() can lose the write on process death."*
- iOS `NSUserDefaultsSettings` ([`PlatformModule.ios.kt:20`](SmartChessboard/shared/src/iosMain/kotlin/org/rurbaniak/smartchessboard/di/PlatformModule.ios.kt:20)); web `StorageSettings()` (localStorage, [`PlatformModule.wasmJs.kt:11`](SmartChessboard/shared/src/wasmJsMain/kotlin/org/rurbaniak/smartchessboard/di/PlatformModule.wasmJs.kt:11)).

The journal stores **only PGN per game id** — `JournalEntry(pgn, dirty, result?)` ([`GameJournal.kt:9-36`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameJournal.kt:9)) under three keys (`journal.<id>.pgn|dirty|result`, [`SettingsGameJournal.kt:56-60`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/journal/SettingsGameJournal.kt:56)). It does **not** store mode, labels, status, or board/position state. The full record (incl. `mode`, `status`, `pgn`, labels) is the cloud `public.games` table ([`contract-surfaces.md:194-207`](docs/reference/contract-surfaces.md:194)).

**The write-ahead journal (§6.2 invariant).** `GameAutoSaver.acceptMove(gameId, pgn)` calls `journal.save(gameId, pgn, dirty = true)` **synchronously, before** the UI advances and before launching cloud sync ([`GameAutoSaver.kt:32-38`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt:32)). The save writes `dirty → result → pgn` so the PGN (the presence gate `load` reads first) lands **last** — a crash mid-save can only re-flush the prior document, never a half-written one ([`SettingsGameJournal.kt:8-41`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/journal/SettingsGameJournal.kt:8)). In the physical path the ordering is enforced at [`PhysicalPlayViewModel.kt:158-177`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayViewModel.kt:158) (validate → SAN → writePgn → journal write → UI advance → launch sync).

**`reconcile` — the closest existing analog to resume.** [`GameAutoSaver.kt:111-121`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt:111):
```kotlin
suspend fun reconcile(game: GameRecord): String {
    val entry = journal.load(game.id)
    return if (entry != null && entry.dirty && isAhead(entry.pgn, game.pgn)) {
        sync(game.id); entry.pgn          // journal ahead → play from journal, re-flush best-effort
    } else {
        journal.save(game.id, game.pgn, dirty = false); game.pgn   // cloud wins (LWW §3.4)
    }
}
```
Invoked on **every game open** inside `load()`, after a cloud `getGame`. It recovers the *moves* of a journaled-but-unsynced in-progress game — but only once you already know the `gameId` and have a successful cloud read.

**Cloud flush retry.** `sync()` ([`GameAutoSaver.kt:60-102`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt:60)) uses `retryDelaysMs = [1000, 2000, 4000]`; the **in-progress** flush is bounded (a later move re-enters sync) while the **terminal/finish** flush retries until it lands or the screen closes (lessons.md). All failures caught as `Throwable` (wasm Ktor failures are `kotlin.Error`).

**Repository surface** ([`GamesRepository.kt:5-49`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GamesRepository.kt:5)): `changes: SharedFlow<Unit>` (emitted after `createGame`/`finishGame`, **not** `updatePgn`); `listMyGames()` (cloud, no PGN); `getGame(id)` (cloud, incl. PGN); `createGame(white, black, mode)` (cloud-only); `updatePgn(id, pgn)`; `finishGame(id, result, pgn)` (atomic). **There is no local read-by-id, no latest-in-progress, no list-by-status locally** — every "which game / what mode" read goes to the cloud.

**What survives a kill mid-physical-game:**

| State | Where it lives | Survives kill? | Recovered by |
| --- | --- | --- | --- |
| PGN of last **accepted** move | journal (`commit`) + cloud `games.pgn` | **YES** | `reconcile` → journal if ahead, else cloud |
| `dirty` flag (unsynced) | journal | **YES** | drives re-flush |
| Finished `result` token | journal + cloud | **YES** | finish re-flush on next load |
| `mode = physical` | **cloud only** (`games.mode`) + PGN `[Mode]` tag | local journal: **NO** | `getGame(id)` cloud read |
| status / labels / created_at | **cloud only** | local: **NO** | `getGame(id)` cloud read |
| "which game to resume" (discovery) | **cloud only** (`listMyGames`) | local: **NO** | History list = cloud fetch — **gap (G2)** |
| pending lift/place sequence (`eventsSinceConfirm`) | in-memory | **NO** | reset empty; board re-snapshotted |
| `liftedSquares` | in-memory | **NO** | reset empty |
| `pendingPromotion` | in-memory | **NO** | reset null (move was never accepted) |
| `recovering` / `setupMismatch` / `latestOccupancy` | in-memory | **NO** | recomputed from fresh `RequestSnapshot` |

### Area 3 — Physical-mode runtime & board session lifecycle

**Headless MVI core.** Pure reducer `reduce(state, msg): ReduceResult` ([`PhysicalPlayReducer.kt:28-36`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:28)) over `PhysicalPlayState = Loading | Error | Playing`; intents `PhysicalMsg` and effects `PhysicalEffect` in [`PhysicalPlayContract.kt:123-223`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayContract.kt:123). The §6.2 journal write is gated in the `CommitMove` effect, interpreted by `PhysicalPlayViewModel.commitMove()`; the reducer does no IO and advances only on the `MoveCommitted` feedback ([`PhysicalPlayReducer.kt:352-379`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:352)).

**Expected position is held in memory and rebuilt from PGN.** `Playing.position get() = positions.last()` ([`PhysicalPlayContract.kt:84`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayContract.kt:84)) — a domain `Position`, never a FEN. `load()` does `getGame → reconcile → parsePgn → Loaded(positions = …)` ([`PhysicalPlayViewModel.kt:122-149`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayViewModel.kt:122)); it advances purely in the reducer's `commit()` (`positions + nextPosition`). The S-04 force-quit E2E already proved this PGN-rebuild path survives a kill on all three targets.

**SequenceInterpreter is stateless/pure.** `resolvePhysicalMove(position, events): Resolution` ([`SequenceInterpreter.kt:37-64`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/SequenceInterpreter.kt:37)) takes the confirmed baseline `position` (= `positions.last()`, re-established for free by load) and the `eventsSinceConfirm` stream (in-memory, correctly discarded on kill). **Nothing stateful to re-seed.** (SYNC caveat: `footprintOf` hand-mirrors castling/en-passant geometry from `ChessRules.applyMove` — keep both sides in sync per lessons.md, but resume never touches footprint geometry.)

**Board adapter lifecycle — re-established fresh on cold start.** `interface BoardConnection { connectionState; events; suspend fun send(command) }` ([`BoardConnection.kt:18-27`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/BoardConnection.kt:18)) — deliberately no `connect()`/`disconnect()` on the port (transport lifecycle belongs to the concrete adapter); `events` is **hot, `replay = 0`**, so subscribe before connecting. The ViewModel `init` subscribes to `events`/`connectionState` *first*, then `load()` ([`PhysicalPlayViewModel.kt:60-79`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayViewModel.kt:60)). On the `CONNECTED` transition the reducer emits `Send(RequestSnapshot)` and re-arms `SetMode(DIAGNOSTIC)` if the grid is open ([`PhysicalPlayReducer.kt:82-95`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:82)).

The `BoardConnection` is a process-lifetime Koin `single` that `connect()`s at bind time and is **never** `disconnect()`ed ([`PlatformModule.android.kt:38-49`](SmartChessboard/shared/src/androidMain/kotlin/org/rurbaniak/smartchessboard/di/PlatformModule.android.kt:38); a `TODO(S-09)` notes this). On kill the whole process + emulator dies; on relaunch Koin re-binds a **fresh** `EmulatedBoard` that `connect()`s and reports start-position occupancy ([`EmulatedBoard.kt:85-93`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoard.kt:85), `:251`). **There is no special re-init path beyond normal screen entry — which is exactly what a cold start produces.** For the *emulator* "fresh = start position" hides the real-board problem (a real mid-game board holds actual occupancy that may not match `positions.last()`) — precisely why FR-013 needs the confirmation step.

**PhysicalPlay screen can already open an in-progress game.** `koinViewModel(key = "physical-$gameId") { parametersOf(gameId) }` ([`PhysicalPlayScreen.kt:66`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayScreen.kt:66)); the **only start input is `gameId`** — `load()` rebuilds an arbitrary mid-game position from the stored PGN (and even handles a FINISHED record defensively). **S-08 does not need to add an "open in-progress physical game" path; it needs the resume-confirmation gate.**

**Promotion-pending gate (FR-006).** `Playing.pendingPromotion` ([`PhysicalPlayContract.kt:66`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayContract.kt:66)) blocks `confirm()` with `PROMOTION_REQUIRED` until `PromotionPicked`/`PromotionDismissed` ([`PhysicalPlayReducer.kt:135-162`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:135)). It is **in-memory, lost on kill** — and the promotion only commits via the `CommitMove` effect, so a kill mid-pick correctly discards it. But the physical board may have a pawn lifted / on the last rank, so post-resume occupancy won't match `positions.last()` → the mismatch check routes to restore (desired; S-08 should test this case).

### Area 4 — S-07 recover/diagnostics reuse surface (the FR-013 core)

**The board-match primitive exists and is h8-safe.** `fun Position.toOccupancy(): Long` ([`Occupancy.kt:21`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/Occupancy.kt:21), a1 = bit 0 … h8 = bit 63, color/type discarded). The compare appears in three shipped spots:
- `matchesExpected = msg.occupancy == state.position.toOccupancy()` ([`PhysicalPlayReducer.kt:106`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:106)) — drives both `setupMismatch` and `restoreVerified`.
- the `INCONSISTENT` fork compare ([`PhysicalPlayReducer.kt:327`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:327)).
- per-square diff `occupancyDiffers(observed, expected, square)` ([`ReedDiagnosticsGrid.kt:120`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/ReedDiagnosticsGrid.kt:120)).

**The diagnostics UI exists.** `ReedDiagnosticsGrid(observed, expected)` ([`ReedDiagnosticsGrid.kt:42`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/ReedDiagnosticsGrid.kt:42)) renders 64 cells with per-square mismatch tint (four readings: unexpected piece / missing piece / correct / correctly-empty). Rendered under the board in `PhysicalPlayScreen` when `diagnosticsVisible` ([`PhysicalPlayScreen.kt:291`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayScreen.kt:291)); the expected position is the live `ChessBoardView(position = state.position)` ([`PhysicalPlayScreen.kt:155`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayScreen.kt:155)).

**The recover gate + the exact re-enable transition.** `acceptanceBlocked get() = paused || recovering` ([`PhysicalPlayContract.kt:92`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayContract.kt:92)). `confirm()` sets `recovering = true` on Illegal/Ambiguous/Inconsistent; `accumulate()` no-ops while recovering. The **only** thing that clears `recovering` is an exact occupancy match in `SnapshotReceived`: `restoreVerified = recovering && matchesExpected` → clears `recovering`/`rejection`/`manualDiagnostics`, emits `SetMode(GAME)` ([`PhysicalPlayReducer.kt:101-121`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:101)). There is **no user "I'm done" button** — restoration is verified by the board snapshot. This is FR-013's "confirm the board matches before re-enabling" — *as a transition*, already enforced.

**The verified gap (G1), confirmed in code.** On resume/connect the mismatch only sets `setupMismatch` (which feeds `diagnosticsVisible` and auto-opens the grid) — it does **not** set `recovering`, so **acceptance is not blocked**. The `Loaded` arm ([`PhysicalPlayReducer.kt:40-58`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:40)) renders `positions.last()`, sets `setupMismatch = false`, and does not request a snapshot or block acceptance. So S-08 must route resume into the blocking gate (reuse `recovering` semantics, or add an `awaitingResumeConfirm` flag ORed into `acceptanceBlocked`), set at load, cleared by the same `matchesExpected` snapshot check; match-on-resume can clear immediately (no friction) or require an explicit confirm (G3, open decision).

**Reuse-surface ledger:**

| Already exists (reuse) | File:line |
| --- | --- |
| Expected-occupancy derivation (`Position.toOccupancy`) | [`Occupancy.kt:21`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/Occupancy.kt:21) |
| Board-match compare `snapshot.occupancy == toOccupancy()` | [`PhysicalPlayReducer.kt:106`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:106) |
| Per-square diagnostics grid `(observed, expected)` | [`ReedDiagnosticsGrid.kt:42`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/ReedDiagnosticsGrid.kt:42) |
| Acceptance gate + re-enable-on-match transition | [`PhysicalPlayContract.kt:92`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayContract.kt:92), [`PhysicalPlayReducer.kt:101-121`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:101) |
| Snapshot-on-(re)connect + re-arm diagnostics | [`PhysicalPlayReducer.kt:82-95`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:82) |
| Expected-position rebuild from PGN (`getGame → reconcile → parsePgn`) | [`PhysicalPlayViewModel.kt:122-149`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayViewModel.kt:122) |
| In-progress physical open with only `gameId` | [`PhysicalPlayScreen.kt:66`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayScreen.kt:66), [`App.kt:81-97`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt:81) |
| Recovery-loop E2E template | [`PhysicalRecoverEndToEndTest.kt`](SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalRecoverEndToEndTest.kt) |

| S-08 must add | Note |
| --- | --- |
| **G1** Resume gate that blocks acceptance until board confirmed/restored | resume mismatch must enter the blocking gate, not just open the grid |
| **G2** Resume discovery + offline-open (local-first) | detect in-progress game on launch (§6.3); consult journal when cloud unreachable |
| **G3** Explicit "confirm the board matches" affordance on resume | distinct from a reject; auto-confirm-on-match vs require-confirm UX decision |
| Resume-specific E2E | matching-board → resume; mismatched-board → diagnostics → restore → resume |

### Area 5 — FR-012 sibling mapping (BLE reconnect-reconcile)

The contract specifies FR-012 and FR-013 with the **same recipe** ([`contract-surfaces.md:601-613`](docs/reference/contract-surfaces.md:601), §6.3):
> On relaunch (PRD FR-013): Mobile **detects in-progress games and offers to resume**. In physical mode: reconnect BLE, send `REQUEST_SNAPSHOT`, compare to expected position derived from PGN. **Match → resume; Mismatch → enter diagnostic mode, prompt to restore.** Invariant: accepted-and-persisted moves survive any crash.

§1.7 (BLE disconnect/reconnect) states the identical reconcile. The `BoardConnected` arm already re-requests a snapshot and re-arms diagnostic mode on every (re)connect ([`PhysicalPlayReducer.kt:82-95`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:82)) — but, like resume, a reconnect mismatch only sets `setupMismatch`, never `recovering`, so it does not block-pending-confirm. **The blocking reconcile is the unbuilt half of *both* FR-012 and FR-013.**

**Design directive:** build the reconcile as a **shared transition keyed off "expected occupancy vs fresh snapshot → match | mismatch → route to diagnostics gate"**, not inlined into the load path. Then FR-012/S-09 wires the identical call into `BoardConnected`. One component, two triggers (process-restart load vs `BoardConnected`); the BLE central auto-reconnect transport stays in the S-09 adapter and is out of S-08 scope. S-07's `plan-brief.md:25` explicitly chose the unified `acceptanceBlocked` gate as "a clean reusable foundation for S-08 / FR-012", and S-07's `research.md:162` already sketched modeling resume as entering this gate.

## Code References

- [`App.kt:38-159`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt:38) — session gate (Restoring/SignedOut/SignedIn); back stack rooted at `HistoryKey`; in-progress routing at `:81-97` (the S-08 resume-entry decision point)
- [`Routes.kt:15-60`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/navigation/Routes.kt:15) — `PhysicalPlayKey` + serializable polymorphic registration (survives process death)
- [`GameJournal.kt:9-36`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameJournal.kt:9) / [`SettingsGameJournal.kt:8-61`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/journal/SettingsGameJournal.kt:8) — the only durable local store (PGN/dirty/result; dirty→result→pgn write order)
- [`GameAutoSaver.kt:32-159`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt:32) — `acceptMove` (sync journal write), `sync` (bounded vs terminal retry), `reconcile` (`:111-121`, closest analog to resume), `isAhead`
- [`GamesRepository.kt:5-49`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GamesRepository.kt:5) — all reads cloud-bound; no local read-by-id / list-by-status (G2 root cause)
- [`PhysicalPlayContract.kt:44-120`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayContract.kt:44) — `Playing` state; `acceptanceBlocked = paused || recovering` (`:92`); `diagnosticsVisible = manualDiagnostics || setupMismatch` (`:95`); `RejectionReason` incl. `INCONSISTENT`
- [`PhysicalPlayReducer.kt:40-121`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayReducer.kt:40) — `Loaded` arm (no gate, no snapshot — G1); `BoardConnected` (snapshot re-request); `SnapshotReceived` (`matchesExpected`/`setupMismatch`/`restoreVerified`); `commit` at `:352-379`
- [`PhysicalPlayViewModel.kt:60-189`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalPlayViewModel.kt:60) — subscribe-then-load; `load` (`:122-149`, cloud `getGame` before `reconcile` — G2); `commitMove`; `send` effect interpreter
- [`Occupancy.kt:21`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/Occupancy.kt:21) — `Position.toOccupancy(): Long` (the FR-013 board-match primitive)
- [`ReedDiagnosticsGrid.kt:42-120`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/physical/ReedDiagnosticsGrid.kt:42) — per-square diff UI
- [`BoardConnection.kt:18-27`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/BoardConnection.kt:18) / [`EmulatedBoard.kt:85-93`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoard.kt:85) — adapter port + emulator connect (snapshot+status, GAME mode)
- [`PlatformModule.android.kt:25-49`](SmartChessboard/shared/src/androidMain/kotlin/org/rurbaniak/smartchessboard/di/PlatformModule.android.kt:25) — `commit = true` journal; `BoardConnection` process-lifetime single (connect-at-bind, never disconnect — `TODO(S-09)`); physical VM factory (Android/iOS only)
- [`SequenceInterpreter.kt:37-64`](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/SequenceInterpreter.kt:37) — stateless resolver; baseline = `positions.last()`
- [`PhysicalRecoverEndToEndTest.kt`](SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/presentation/physical/PhysicalRecoverEndToEndTest.kt) — recovery-loop E2E (template for the S-08 resume E2E)
- [`contract-surfaces.md:601-613`](docs/reference/contract-surfaces.md:601) — §6.3 App crash mid-game = the canonical S-08 spec; §1.7 (`:148-171`) the FR-012 sibling; games schema `:194-207`

## Architecture Insights

- **Durability is journal-first, cloud-eventual.** The crash-safety guardrail ("a crash must not erase accepted moves") is structurally satisfied by the synchronous write-ahead journal — independent of S-08. S-08's risk is **not** lost moves; it is (a) not gating acceptance against the physical board on resume, and (b) depending on the cloud to *find* and *open* the game.
- **"Resume" already exists for move content; it does not exist for discovery or for the physical board.** `reconcile` recovers the PGN once a game is open with a cloud record. S-08 adds the *board* reconcile (snapshot vs expected occupancy) and the *entry* path (detect + open, ideally offline).
- **The reconcile is a transition, not a screen.** FR-013's "confirm the board matches" is the `recovering`-cleared-by-`matchesExpected` transition, already enforced as a hard gate. S-08 should fire that gate on resume (and design it so FR-012 fires it on reconnect) rather than invent a parallel mechanism — consistent with lessons.md's "one navigation mechanism / one gate" discipline.
- **Cold start re-establishes the board connection for free** (DI re-bind + VM resubscribe + auto `RequestSnapshot`); the emulator's "fresh = start position" masks the real-board mismatch case that FR-013 exists to handle — so the resume E2E must inject a mismatched snapshot (the `PhysicalRecoverEndToEndTest` fault-injection pattern), not rely on the emulator's default.
- **In-flight physical state is correctly volatile.** Lifted pieces, pending sequence, and pending promotion are never journaled, so a kill discards a half-made move safely; the physical board being "ahead" of the durable PGN is exactly what the board-match check catches and routes to restore.

## Historical Context (from prior changes)

- [`context/changes/digital-pass-and-play/plan.md`](context/changes/digital-pass-and-play/plan.md) — origin of the write-ahead journal, `GameAutoSaver`, and the §6.2 ordering invariant; the S-04 force-quit-mid-game → resume E2E precedent (the PGN-rebuild half of resume, proven on three targets).
- [`context/changes/game-end-and-result/plan.md`](context/changes/game-end-and-result/plan.md) — `reconcile` re-flush on next load; the "terminal flush needs its own retry" fix; finish-aware journal.
- [`context/changes/physical-capture-emulated/`](context/changes/physical-capture-emulated/) (S-06) — the headless MVI core, `SequenceInterpreter`, `EmulatedBoard` promoted to commonMain, `createGame(mode)`, the PhysicalPlay screen + load path.
- [`context/changes/reject-recover-diagnostics/`](context/changes/reject-recover-diagnostics/) (S-07, just merged) — the recover gate, `Position.toOccupancy`, the board-match compare, `ReedDiagnosticsGrid`, and the `acceptanceBlocked` gate; `plan-brief.md:25` names the unified gate as the reusable foundation for S-08/FR-012; `research.md:162` sketches modeling resume as entering this gate.
- [`context/foundation/lessons.md`](context/foundation/lessons.md) — Nav3 multiplatform (serializable NavKeys); the SequenceInterpreter SYNC-comment rule; `Throwable`-catch on wasm; the **stale** Room/OPFS entry (`:29-35`, never built).

## Related Research

- [`context/changes/reject-recover-diagnostics/research.md`](context/changes/reject-recover-diagnostics/research.md) — the immediate predecessor; its forward-notes anticipate S-08 reusing the recover gate.

## Open Questions

1. **Offline resume scope (G2).** Does S-08 require resume to work **offline / without a cloud round-trip**? The "no accepted move lost" guardrail is already met, but the contract's "detect in-progress games and offer to resume" (§6.3) and an offline-open path need new **local-first plumbing** (enumerate journaled in-progress games; open from journal when `getGame` is unreachable). Today the journal stores only PGN per id — no local index of *which* ids are in-progress or their mode. Decide in `/10x-plan`: minimal (cloud-gated detect, journal only backstops move content) vs full (local index of in-progress physical games + journal-only open).
2. **Auto-resume vs manual resume (entry UX).** Should the app **auto-navigate** to the in-progress physical game on cold start (seed `[HistoryKey, PhysicalPlayKey(id)]`), or just surface a resume affordance on the History screen? §6.3 says "offers to resume" — leaning toward an offer, not a forced jump.
3. **Confirm-on-match friction (G3).** FR-013 literally says "asks the player to confirm that the physical board matches." When the snapshot **already matches** on resume, do we still require an explicit user confirm, or auto-resume silently (match → resume) and only prompt on mismatch? Auto-resume-on-match is lower friction and matches §6.3's "Match → resume normal play."
4. **Gate representation.** Reuse the existing `recovering` flag for resume, or add a distinct `awaitingResumeConfirm` flag that ORs into `acceptanceBlocked`? A distinct flag keeps "resume confirmation" and "reject recovery" separable in the UI copy and analytics, at the cost of one more state field. Decide in `/10x-plan`.
5. **Same-device guarantee.** FR-013 is same-device only; cross-device handoff of an active physical game is out of MVP (PRD OQ-4). No cross-device work in S-08 — confirm the plan states this boundary explicitly.
