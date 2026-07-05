# Digital Pass-and-Play with Durable Record (S-04) Implementation Plan

## Overview

Implement the digital pass-and-play slice: a signed-in player creates a game (digital mode,
White/Black labels), plays on an interactive on-screen board where every move is validated by the
F-01 rules engine before execution, resolves promotions through a piece picker, and has every
accepted move durably auto-saved with PGN as the source of truth (FR-003, FR-004, FR-005, FR-006,
FR-014). The finished record replays through S-02's view. Per the planning interview (2026-06-12),
the slice is verified on **Android, iOS, and web** — the user deliberately pulled the FR-020
digital-play sliver forward (roadmap had parked web; this mirrors the S-01 precedent of pulling a
web sliver with a recorded decision).

## Current State Analysis

What exists (verified in code, 2026-06-12):

- **F-01 engine** (`shared/src/commonMain/.../domain/chess/`) is complete and perft-verified:
  `legalMoves(position)` (ChessRules.kt:12), `validate(position, move): MoveOutcome`
  (ChessRules.kt:25) with a dedicated `IllegalReason.PROMOTION_PIECE_REQUIRED`, and
  `status(position): GameStatus` (Ongoing/Check/Checkmate/Stalemate, ChessRules.kt:53).
  `applyMove` is internal — consumers get the next position from `MoveOutcome.Legal(position)`.
- **The engine has no SAN generation and no FEN/PGN serialization** — explicitly deferred to S-04
  by the F-01 and S-02 plans ("SAN generation (S-04)" in both out-of-scope lists). S-02's
  `parsePgn(pgn): ReplayGame` (PgnParser.kt:27) reads PGN by resolving SAN against `legalMoves`;
  nothing writes PGN today.
- **Data layer is read-only**: `GamesRepository` exposes only `listMyGames()` and `getGame(id)`
  (domain/games/GamesRepository.kt:3-9). No INSERT/UPDATE path exists anywhere in the app.
- **No local persistence of any kind** — no Room/SQLDelight/multiplatform-settings dependency is
  declared. History and replay read straight from Supabase. Contract §3.4/§6.2 however requires:
  *"every accepted move is durably stored locally before the next move is accepted; cloud sync is
  best-effort"* — this slice introduces the first local durable store.
- **`ChessBoardView(position, modifier)`** (presentation/board/ChessBoardView.kt:48) is stateless
  and display-only: no gestures, white fixed at bottom, 12 bundled vector piece assets.
- **Navigation**: Nav3 multiplatform with `HistoryKey` / `ReplayKey(gameId)` in Routes.kt,
  explicit polymorphic NavKey registration (mandatory on iOS/wasm), browser-history fragments on
  wasmJs via terrakok `navigation3-browser` (`#history`, `#replay?id=`).
- **Supabase**: `games` table deployed with RLS (`supabase/migrations/20260611103324_games.sql`).
  **Gap found during planning**: `user_id uuid NOT NULL` has **no default**, while contract §3.2
  promises "mobile does not pass user_id explicitly on any write". The first INSERT in the
  project needs `default auth.uid()` — a new migration plus a contract write-back.
- **Patterns settled in lessons.md**: MVVM default (MVI needs written justification), Koin KMP
  only, Nav3 with explicit polymorphic registration, commonMain parser code is not green until it
  passes on a Native target, yarn.lock must be actualized after adding npm-backed wasmJs deps.

## Desired End State

A signed-in player on Android, iOS, or web taps "New game" in History, optionally edits the
White/Black labels, and starts a digital game. They play pass-and-play with tap-tap input and
legal-target highlights; illegal taps cannot produce a move; pawn promotion opens a picker before
the move is accepted. Every accepted move is synchronously written to a local journal, then synced
to Supabase best-effort (visible non-blocking "sync pending" state when offline). Killing the app
mid-game and reopening the game from History resumes play at the last accepted move with nothing
lost. On checkmate/stalemate the board blocks further input and shows a banner (result recording
stays in S-05; the record remains `in_progress`). The game's PGN parses with the existing
`parsePgn` and replays correctly in the S-02 Replay view.

### Key Discoveries:

- `validate()` already models the promotion UX hook: a legal-shaped pawn move without `promoteTo`
  returns `Illegal(PROMOTION_PIECE_REQUIRED)` (ChessRules.kt:25) — the picker flow keys off this.
- `ReplayGame`'s invariant (`positions.size == sanMoves.size + 1`) is exactly the state shape the
  play screen needs; `PlayViewModel` can maintain the same parallel lists and reuse `parsePgn`
  for resume (ReplayGame.kt:47).
- `GameRecord.createdAt` (ISO-8601 from the server's `created_at default now()`) is the natural
  source for the PGN `[Date]` tag — reformatting the string avoids adding a datetime dependency.
- `games.user_id` has no column default → `createGame` INSERT requires a
  `default auth.uid()` migration to honor contract §3.2's "never pass user_id" rule.
- Two `GameStatus` types will be in scope in play code: `domain.games.GameStatus`
  (IN_PROGRESS/FINISHED) and `domain.chess.GameStatus` (Ongoing/Check/…) — import aliasing needed.
- The wasmJs journal can use `localStorage` (synchronous, no SharedArrayBuffer) — the COOP/COEP
  lesson does not apply to this slice.

## What We're NOT Doing

- **Game end & result recording (S-05)**: no `status='finished'`, no `result` writes, no PGN
  `[Result]` other than `"*"`, no manual end-of-game UI. Mate/stalemate is detected and blocks
  input with a banner, nothing more (interview decision).
- **Physical mode (S-06+)**: no mode picker UI — the creation form implies digital and writes
  `mode='digital'` (interview decision). No BLE, no sequence interpretation.
- **Takeback/undo** (interview decision): accepted moves are final.
- **Full offline-first mirror (contract §3.4's end state)**: no local DB as the authoritative
  source for the history list; history stays cloud-read. The local store is a write-ahead journal
  for in-progress games only (interview decision).
- **Offline game creation**: `createGame` requires connectivity (form shows a retryable error);
  only move persistence is offline-safe.
- **Game deletion, draw-by-rule detection, eval integration (S-03), board flip animation,
  drag-and-drop input, shareable web URLs.**
- **Auto-flip board per move** — fixed White-bottom default with a manual flip toggle only.

## Implementation Approach

Bottom-up along the same reuse boundaries S-02 used, so each phase is independently verifiable:

1. Pure-domain PGN *writing* (SAN writer + PGN serializer), proven by round-tripping through the
   existing parser on the existing corpus — the inverse of S-02 Phase 1.
2. The write path: Supabase INSERT/UPDATE, the `auth.uid()` default migration, and the local
   journal + auto-saver that realizes the §6.2 invariant (journal write is synchronous and
   ordered before cloud sync).
3. Board interactivity as a backwards-compatible extension of `ChessBoardView` (replay call sites
   compile unchanged) plus the promotion picker.
4. The Play and NewGame screens, MVVM ViewModels, navigation routes, and history routing — the
   phase where everything composes.
5. Resume/crash-safety verification and three-surface E2E, then write-backs.

**Presentation pattern justification (per lessons.md)**: `PlayViewModel` stays **MVVM**. The play
screen state (position list + selection + pending promotion + sync state + terminal status) is a
single coherent UiState updated by a handful of intent methods; it does not need MVI's
reducer/intent ceremony. The lesson permits MVI for the live board but does not require it —
MVVM matches Auth/History/Replay and keeps the codebase uniform.

## Critical Implementation Details

- **§6.2 ordering invariant (state sequencing)**: a move is "accepted" only after the journal
  write returns. Sequence per move: `validate()` → SAN append → PGN serialize → **synchronous
  journal write** → UI state update → async cloud sync. The cloud update must never be the only
  copy of a new move.
- **Journal durability on Android**: use synchronous commit (`SharedPreferencesSettings(...,
  commitValues = true)` or equivalent). The default async `apply()` can lose the write on process
  death — which is exactly the window the guardrail protects.
- **Parser must accept in-progress PGN**: `parsePgn` was built against finished famous games.
  Phase 1 must verify (and fix if needed) that it accepts `[Result "*"]`, a `*` termination
  token, an odd number of plies, and an empty movetext (`pgn = ''` for a fresh game).
- **multiplatform-settings wasmJs artifact**: planned journal backend is
  `com.russhwolf:multiplatform-settings` (SharedPreferences / NSUserDefaults / localStorage).
  Verify the wasmJs artifact resolves at the chosen version before building on it; fallback is a
  hand-rolled 3-actual `expect/actual` over the same interface. If the dependency pulls an npm
  package on wasmJs, run `./gradlew kotlinWasmUpgradeYarnLock` (lessons.md).
- **`GameStatus` name collision**: `domain.games.GameStatus` vs `domain.chess.GameStatus` meet in
  play/history routing code — use import aliases (e.g. `ChessGameStatus`) consistently.
- **navigation3-browser binds once per page session** (lessons.md): new fragments (`#new`,
  `#play?id=`) extend the existing binding; the sign-out → sign-in degradation stays accepted.

## Phase 1: SAN Generation & PGN Writing (Domain)

### Overview

Add the inverse of S-02's parser: generate standards-compliant SAN for a legal move and serialize
a complete PGN document (headers §5.2 + movetext). Prove correctness by round-tripping against
the existing parser and corpus, green on all three targets.

### Changes Required:

#### 1. SAN writer

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/pgn/SanWriter.kt`

**Intent**: Produce the SAN token for a move that is already known to be legal in a position, so
the play flow can append to the movetext after each accepted move.

**Contract**: `fun sanForMove(position: Position, move: Move): String`. Rules it must honor
(§5.3): castling as `O-O`/`O-O-O`; piece letter (none for pawns); minimal disambiguation computed
from `legalMoves(position)` (file first, then rank, then both); `x` for captures (pawn captures
prefixed with the from-file, en passant included); promotion suffix `=Q|R|B|N`; `+`/`#` derived
from `status()` of the resulting position (via `validate`). Precondition: the move is legal —
behavior for illegal moves may throw (documented), it is never called on that path.

#### 2. PGN serializer

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/pgn/PgnWriter.kt`

**Intent**: Serialize header tags + SAN move list into the canonical PGN document stored in
`games.pgn`, matching §5.2 exactly so S-02 replay and future S-03/S-05 consumers read it as-is.

**Contract**: `data class PgnMeta(event: String, date: String, white: String, black: String,
result: String, mode: String)` and `fun writePgn(meta: PgnMeta, sanMoves: List<String>): String`.
Emits the seven-tag header (`Event`, `Date` as `YYYY.MM.DD`, `White`, `Black`, `Result`, `Mode`),
movetext with move numbers, and the termination marker equal to `result` (always `"*"` in S-04).
Date input is derived from `GameRecord.createdAt` (ISO-8601 → dots) by the caller — no datetime
dependency.

#### 3. Parser acceptance of in-progress PGN

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/pgn/PgnParser.kt`

**Intent**: Guarantee `parsePgn` round-trips S-04-shaped documents: `[Result "*"]`, `*`
termination token, odd ply counts, and empty movetext. Fix the tokenizer/resolver only if the new
tests show a gap.

**Contract**: existing signature unchanged; `parsePgn(writePgn(meta, sans))` yields the same
positions/sanMoves with `truncation == null`.

#### 4. Tests

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/domain/chess/pgn/SanWriterTest.kt`, `PgnWriterTest.kt` (+ extend `PgnParserTest.kt`)

**Intent**: Cover the SAN matrix (disambiguation by file/rank/both, captures, en passant,
castling with check, promotion with check/mate, mate suffix) plus two round-trip layers:
(a) parse each `PgnFixtures` game → re-serialize → re-parse → identical positions and movetext
tokens; (b) seeded-random legal playouts (~40 plies × several seeds via `legalMoves` +
`kotlin.random.Random(seed)`) → serialize → parse → identical positions.

### Success Criteria:

#### Automated Verification:

- Host JVM suite passes: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- Native suite passes (lessons.md rule for parser-shaped code): `... ./gradlew :shared:iosSimulatorArm64Test --console=plain --no-daemon`
- Web suite passes: `... ./gradlew :shared:wasmJsTest --console=plain --no-daemon`

#### Manual Verification:

- None (pure domain; corpus + round-trip tests carry the proof).

**Implementation Note**: After completing this phase and all automated verification passes,
commit per the ritual and proceed — no manual gate is needed for Phase 1.

---

## Phase 2: Write Path & Local Journal (Data)

### Overview

Open the first write path: game creation and PGN auto-save against Supabase, the `auth.uid()`
column-default migration with contract write-back, and the local write-ahead journal + auto-saver
that realizes the §6.2 invariant.

### Changes Required:

#### 1. Migration: `user_id` default

**File**: `supabase/migrations/<timestamp>_games_user_id_default.sql`

**Intent**: Let authenticated INSERTs omit `user_id` (contract §3.2: "mobile does not pass
user_id explicitly on any write") by defaulting it server-side.

**Contract**: `alter table public.games alter column user_id set default auth.uid();` — RLS
policies are untouched; `games_insert_own` (`with check auth.uid() = user_id`) still holds.

#### 2. pgTAP coverage

**File**: `supabase/tests/` (extend the existing pgTAP suite)

**Intent**: Prove an authenticated INSERT without `user_id` lands owned by the caller, and an
owner UPDATE of `pgn` succeeds while a non-owner UPDATE is invisible (RLS).

**Contract**: new pgTAP assertions in the existing test layout; `supabase test db` green.

#### 3. Contract & PRD write-back

**File**: `docs/reference/contract-surfaces.md` (§2.2 + frontmatter `updated`), `context/foundation/prd.md` (Implementation Decisions, dated one-liner)

**Intent**: Record the column default per the contract's change-control rule, so §2.2 matches the
deployed schema.

**Contract**: §2.2 `user_id` row gains `default auth.uid()`; PRD gets a dated note (no
user-facing behavior change).

#### 4. Repository write methods

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GamesRepository.kt`, `.../data/games/SupabaseGamesRepository.kt`

**Intent**: Add creation and auto-save operations per contract §3.2; creation returns the full
row (id + server `created_at` feed navigation and the PGN `[Date]` tag).

**Contract**:
```kotlin
suspend fun createGame(whiteLabel: String, blackLabel: String): GameRecord  // INSERT mode='digital', status='in_progress', pgn=''; select row back
suspend fun updatePgn(id: String, pgn: String)                             // UPDATE games SET pgn = $1 WHERE id = $2 (status/result untouched in S-04)
```
`FakeGamesRepository` in commonTest grows matching recording fakes.

#### 5. Local journal

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameJournal.kt`, `.../data/journal/SettingsGameJournal.kt` (+ per-platform Settings provisioning)

**Intent**: Durable, synchronous local store of the latest PGN per in-progress game — the
write-ahead half of the §6.2 invariant. Backed by multiplatform-settings (SharedPreferences with
`commitValues = true` / NSUserDefaults / localStorage); fallback per Critical Implementation
Details if the wasmJs artifact disappoints.

**Contract**:
```kotlin
interface GameJournal {
  fun load(gameId: String): JournalEntry?      // JournalEntry(pgn: String, dirty: Boolean)
  fun save(gameId: String, pgn: String, dirty: Boolean)
  fun markSynced(gameId: String)
}
```
Entries persist across process death; S-05 will own cleanup on game finish.

#### 6. Auto-saver

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt` (+ test)

**Intent**: One testable unit owning the accept-move persistence sequence and retry policy:
synchronous journal write (dirty), then best-effort cloud `updatePgn` with retry; `markSynced` on
success. Exposes sync state for the UI indicator and a reconciliation helper for game load
(journal ahead → flush journal to cloud and play from it; cloud ahead or diverged → cloud wins,
journal overwritten — LWW per §3.4).

**Contract**: constructor-injected `GamesRepository` + `GameJournal`; suspend API consumed by
`PlayViewModel`; no Supabase types leak through.

#### 7. DI wiring

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/di/AppModules.kt` (+ platform Settings provisioning where needed)

**Intent**: Register `GameJournal` and `GameAutoSaver` in Koin per the lessons.md DI rule; Android
provisioning gets a `Context` via `androidContext` in the existing `initKoin` config hook.

**Contract**: `dataModule` additions only; no parallel service locator.

### Success Criteria:

#### Automated Verification:

- Local stack migrates + pgTAP green: `cd supabase && supabase db reset && supabase test db`
- All three per-target suites green (commands as Phase 1) including `GameAutoSaver` ordering/retry tests and journal fakes.
- yarn.lock actualized if the wasmJs dependency required it: `./gradlew kotlinWasmUpgradeYarnLock` then a clean `:shared:wasmJsTest` run.

#### Manual Verification:

- Cloud migration applied to the hosted project (`supabase db push` — manual gate, same pattern as S-01/S-02 console steps).

**Implementation Note**: After completing this phase and all automated verification passes, pause
for manual confirmation that the cloud migration is applied before proceeding.

---

## Phase 3: Interactive Board & Promotion Picker (UI)

### Overview

Extend `ChessBoardView` with tap interaction, highlights, and orientation — backwards-compatible
so Replay renders unchanged — and add the promotion picker dialog on the existing piece assets.

### Changes Required:

#### 1. ChessBoardView interaction & orientation

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/board/ChessBoardView.kt`

**Intent**: Let a host render selection + legal-target highlights, receive square taps, and flip
the board, while display-only call sites (Replay) compile and render exactly as before.

**Contract**: signature other phases depend on:
```kotlin
@Composable fun ChessBoardView(
  position: Position,
  modifier: Modifier = Modifier,
  orientation: Color = Color.WHITE,            // color rendered at the bottom
  interaction: BoardInteraction? = null,       // null = display-only (replay unchanged)
)
data class BoardInteraction(
  val selectedSquare: Int?,
  val targetSquares: Set<Int>,                 // legal destinations of the selection
  val onSquareTap: (Int) -> Unit,
)
```
Highlight styling: selected-square tint, dot on empty targets, ring on capture targets — derived
from the existing palette. Tap→square mapping must respect `orientation`.

#### 2. Promotion picker

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/board/PromotionPicker.kt`

**Intent**: Modal dialog showing the four `PROMOTION_TARGETS` pieces of the moving color
(existing vector assets); picking completes the move, dismissing cancels it (no move is saved —
FR-006).

**Contract**: `@Composable fun PromotionPicker(color: Color, onPick: (PieceType) -> Unit, onDismiss: () -> Unit)`.

#### 3. Geometry & preview coverage

**File**: `SmartChessboard/shared/src/commonTest/.../presentation/board/ChessBoardGeometryTest.kt` (extend), `shared/src/androidMain/.../presentation/board/ChessBoardPreviews.kt` (extend)

**Intent**: Pin the tap→square index mapping under both orientations (a1=0 convention; the S-02
plan flagged orientation off-by-one as the board's key risk) and preview the highlight states.

**Contract**: pure geometry tests in commonTest; previews are Android-only as today.

### Success Criteria:

#### Automated Verification:

- All three per-target suites green (commands as Phase 1), including orientation-mapping tests.

#### Manual Verification:

- Android Studio previews show selection tint, target dots/rings, flipped orientation, and the promotion picker correctly.

**Implementation Note**: After completing this phase and all automated verification passes, pause
for manual confirmation of the previews before proceeding.

---

## Phase 4: Play & New-Game Screens + Navigation

### Overview

Compose everything into the user-facing flow: `NewGameScreen` (minimal form) and `PlayScreen`
(interactive board + move list + banners), MVVM ViewModels, new routes with browser-history
fragments, and history routing by status/mode.

### Changes Required:

#### 1. PlayViewModel

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/play/PlayViewModel.kt`

**Intent**: Own the play state machine (MVVM — justification in Implementation Approach): load →
reconcile journal/cloud → playing; tap selection/movement; promotion pending; terminal blocking;
sync state. Reuses `parsePgn` for resume and maintains the `ReplayGame`-shaped parallel lists
(`positions`, `sanMoves`) during play.

**Contract**: constructor `(gameId: String, gamesRepository: GamesRepository, autoSaver:
GameAutoSaver, parseDispatcher: CoroutineDispatcher = Dispatchers.Default)`; exposes
`StateFlow<PlayUiState>` with `Loading / Error / Playing(position, sanMoves, selection,
targetSquares, pendingPromotion, terminal, orientation, syncPending)`. Intent methods:
`onSquareTap(square)`, `onPromotionPick(pieceType)`, `onPromotionDismiss()`, `flipBoard()`,
`retry()`. Accept-move sequence follows the Critical Implementation Details ordering; after
acceptance `status()` decides `terminal` (Checkmate/Stalemate → input blocked, record untouched —
S-05 boundary). Koin registration mirrors `ReplayViewModel`'s `parametersOf(gameId)` pattern.

#### 2. NewGameViewModel + screen

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/newgame/NewGameViewModel.kt`, `NewGameScreen.kt`

**Intent**: Minimal creation form (interview decision): two label fields prefilled
"White"/"Black", Start button calling `createGame`; creating/error-with-retry states (creation
requires connectivity); on success hand the new `gameId` to navigation.

**Contract**: MVVM; `NewGameUiState(creating: Boolean, failed: Boolean)`; labels default to
schema defaults when blank.

#### 3. PlayScreen

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/play/PlayScreen.kt`

**Intent**: Compose `ChessBoardView` (interactive, `BOARD_MAX_WIDTH` cap like Replay), turn
indicator, SAN move list (extract/reuse the Replay move-list composable), flip button,
non-blocking "sync pending" indicator, check/mate/stalemate banner, and the `PromotionPicker`
when pending.

**Contract**: `@Composable fun PlayScreen(gameId: String, onBack: () -> Unit)`; the shared
move-list composable moves to a common location consumable by both Replay and Play without
changing Replay behavior.

#### 4. Routes & App wiring

**File**: `.../presentation/navigation/Routes.kt`, `.../App.kt`, `.../presentation/history/HistoryScreen.kt`

**Intent**: Add `NewGameKey` (data object) and `PlayKey(gameId)` with explicit polymorphic
registration (iOS/wasm constraint); History gains a "New game" action and routes row taps by
status/mode — `IN_PROGRESS && DIGITAL → PlayKey`, otherwise `ReplayKey` (interview decision: web
included, so the same routing applies on all targets). After creation, `NewGameKey` is replaced
by `PlayKey` on the back stack (back from Play returns to History).

**Contract**: `HistoryScreen`'s `onGameClick` carries enough of `GameSummary` (mode + status) for
routing; both new keys are `@Serializable` and registered in `navSavedStateConfiguration`.

#### 5. Browser-history fragments

**File**: `SmartChessboard/shared/src/wasmJsMain/kotlin/org/rurbaniak/smartchessboard/presentation/navigation/BrowserNavigation.wasmJs.kt`

**Intent**: Extend the fragment mapping with `#new` and `#play?id=<uuid>` so browser Back/Forward
keeps driving the stack (binds-once caveat stays accepted per lessons.md).

**Contract**: same `buildBrowserHistoryFragment()` pattern as `#replay?id=`.

#### 6. ViewModel tests

**File**: `SmartChessboard/shared/src/commonTest/.../presentation/PlayViewModelTest.kt`, `NewGameViewModelTest.kt` (+ `FakeGameJournal`)

**Intent**: Cover selection/deselection/reselection, legal move acceptance, illegal tap no-ops,
promotion flow (pick and dismiss), journal-before-cloud ordering (via recording fakes), sync
failure → `syncPending` + retry, resume from mid-game PGN, reconciliation (journal ahead / cloud
ahead / diverged), terminal blocking after mate/stalemate, creation failure → retryable error.

**Contract**: same fake-based style as existing VM tests; `TestDispatcher` for parse determinism.

### Success Criteria:

#### Automated Verification:

- All three per-target suites green (commands as Phase 1) including the new VM tests.

#### Manual Verification:

- Android emulator: create game → play several moves (incl. castling) → promotion via picker → flip board → kill app → reopen game from History at the last move.
- Airplane mode mid-game: moves keep being accepted, "sync pending" shows, reconnect flushes (PGN visible in Supabase).
- Web dev server: same create→play flow; browser Back/Forward navigates History ↔ Play sanely.
- Checkmate position reached → banner shows, input blocked, game stays `in_progress` in history.

**Implementation Note**: After completing this phase and all automated verification passes, pause
for manual confirmation of the play flow before proceeding to E2E.

---

## Phase 5: Resume Hardening, Three-Surface E2E & Write-backs

### Overview

Prove the crash-safety guardrail and the full loop on all three surfaces against the hosted
backend, then write back roadmap/lessons/change state.

### Changes Required:

#### 1. Three-surface E2E (manual, hosted backend)

**File**: (no code by default — fixes only if E2E surfaces defects)

**Intent**: On Android device/emulator, iOS simulator, and the deployed/dev web target: sign in →
create → play (castling + promotion + a capture) → force-quit mid-game → relaunch → resume with
zero lost moves → verify the same game replays correctly in Replay on a *different* surface
(cloud copy proves sync). Verify `[Mode "digital"]`, `[Result "*"]`, labels and date in the
stored PGN via Supabase table editor.

**Contract**: PRD US-01 acceptance criteria (minus eval, which is S-03) demonstrably pass.

#### 2. Roadmap & lessons write-back

**File**: `context/foundation/roadmap.md`, `context/foundation/lessons.md` (only if new lessons emerged), `context/changes/digital-pass-and-play/change.md`

**Intent**: Record S-04 delivery status and the deliberate web-scope decision (FR-020 digital-play
sliver pulled forward, mirroring the S-01 precedent note in Parked); capture any new recurring
rule (e.g. journal durability semantics) via the lessons format.

**Contract**: roadmap S-04 entry + Parked section note; `change.md` status per process.

### Success Criteria:

#### Automated Verification:

- Full per-target suites green (regression): commands as Phase 1.
- `cd supabase && supabase db reset && supabase test db` green (regression).

#### Manual Verification:

- E2E script above passes on Android, iOS, and web against the hosted backend.
- Cross-surface check: a game played on Android replays correctly on web (and vice versa).
- Roadmap/lessons/change.md write-backs reviewed.

**Implementation Note**: After all verification passes, this change is ready for `/10x-impl-review`.

---

## Testing Strategy

### Unit Tests:

- **SAN writer**: disambiguation matrix (file/rank/both; knights and rooks), pawn captures +
  en passant, castling (incl. with check), promotions (incl. with check/mate), `+`/`#` suffixes.
- **PGN writer**: header shape §5.2, move numbering, `*` termination, empty movetext.
- **Round-trips**: corpus (`PgnFixtures`) parse→write→parse identity; seeded-random legal
  playouts write→parse identity (the strongest single check — it exercises writer and parser
  against the engine itself).
- **GameAutoSaver**: journal-write-before-cloud ordering, retry/backoff, `markSynced`,
  reconciliation rules (journal ahead / cloud ahead / diverged → LWW cloud).
- **PlayViewModel / NewGameViewModel**: full intent surface with recording fakes (see Phase 4).
- **Board geometry**: tap→index under both orientations.

### Integration Tests:

- pgTAP: INSERT-without-user_id ownership, owner/non-owner UPDATE visibility (RLS).
- Per-target suites on host JVM, iOS simulator (Native — mandatory for parser-shaped code), wasm.

### Manual Testing Steps:

1. Phase 4 play-flow script (create, play, promote, flip, kill+resume, airplane-mode sync).
2. Phase 5 three-surface E2E + cross-surface replay verification.
3. Checkmate/stalemate banner + input block; record stays `in_progress`.

## Performance Considerations

- Move acceptance is local and synchronous (validate + SAN + serialize + journal write) — well
  under the 500 ms NFR; cloud sync is off the acceptance path by design.
- `parsePgn` on game load runs on `parseDispatcher` (same pattern as Replay). PGN re-serialization
  per move is O(plies) string work on short documents — negligible.

## Migration Notes

- One additive migration (`user_id` default). No data backfill. Local stack via `db reset`;
  hosted project via `supabase db push` (manual gate, Phase 2).
- No app-data migration: the journal is new, empty-start state.

## References

- Change folder: `context/changes/digital-pass-and-play/`
- Roadmap item: S-04 in `context/foundation/roadmap.md`
- PRD: FR-003–FR-006, FR-014, FR-019, US-01, NFRs in `context/foundation/prd.md`
- Contracts: §2.2, §3.2, §3.4, §5, §6.2–6.3 in `docs/reference/contract-surfaces.md`
- Engine API: `shared/src/commonMain/.../domain/chess/` (F-01 brief: `context/changes/chess-rules-engine/plan-brief.md`)
- Replay reuse surfaces: `context/changes/replay-seeded-games/plan-brief.md`
- Recurring rules: `context/foundation/lessons.md`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: SAN Generation & PGN Writing (Domain)

#### Automated

- [x] 1.1 Host JVM suite passes (`:shared:testAndroidHostTest`) — 5c6b327
- [x] 1.2 Native suite passes (`:shared:iosSimulatorArm64Test`) — 5c6b327
- [x] 1.3 Web suite passes (`:shared:wasmJsTest`) — 5c6b327

### Phase 2: Write Path & Local Journal (Data)

#### Automated

- [x] 2.1 Local stack migrates + pgTAP green (`supabase db reset && supabase test db`) — 10d502f
- [x] 2.2 All three per-target suites green incl. GameAutoSaver tests — 10d502f
- [x] 2.3 yarn.lock actualized if wasmJs dep required it; clean `:shared:wasmJsTest` — 10d502f

#### Manual

- [x] 2.4 Cloud migration applied to hosted project (`supabase db push`) — 10d502f

### Phase 3: Interactive Board & Promotion Picker (UI)

#### Automated

- [x] 3.1 All three per-target suites green incl. orientation-mapping tests — 9b613ea

#### Manual

- [x] 3.2 Previews show selection, targets, flip, and promotion picker correctly — 9b613ea

### Phase 4: Play & New-Game Screens + Navigation

#### Automated

- [x] 4.1 All three per-target suites green incl. PlayViewModel/NewGameViewModel tests — 407ab2f

#### Manual

- [x] 4.2 Android: create → play → promote → flip → kill app → resume at last move — 407ab2f
- [x] 4.3 Airplane mode: play continues, sync-pending shows, reconnect flushes to Supabase — 407ab2f
- [x] 4.4 Web dev server: create → play flow; browser Back/Forward sane — 407ab2f
- [x] 4.5 Checkmate → banner, input blocked, record stays in_progress — 407ab2f

### Phase 5: Resume Hardening, Three-Surface E2E & Write-backs

#### Automated

- [x] 5.1 Full per-target suites green (regression) — 9724eae
- [x] 5.2 Supabase reset + pgTAP green (regression) — 9724eae

#### Manual

- [x] 5.3 E2E passes on Android, iOS, and web against hosted backend — 9724eae
- [x] 5.4 Cross-surface check: game played on one surface replays on another — 9724eae
- [x] 5.5 Roadmap/lessons/change.md write-backs reviewed — 9724eae
