# Physical-Mode Capture (S-06) — Manual Verification Checklist

> **✅ GATE ACCEPTED — 2026-07-04.** Code-read rows (1.5–3.6) independently re-verified by Claude: `supportsPhysicalBoard` = true/true/false (Android/iOS/wasm), wasm DI (`PlatformModule.wasmJs.kt`) binds only `Settings` — no `BoardConnection` / `PhysicalPlayViewModel` — and `SequenceInterpreter` returns only `legalMoves`-sourced moves (no hand-built `Move` on any path). Device rows (4.4–4.6, 5.6) confirmed by the user. impl-review already APPROVED. All rows ticked here and in `plan.md` `## Progress`.

Deferred manual checks for the whole slice, to run **at the end of S-06 before archiving**. The
automated success criteria are gated per phase in `plan.md` → `## Progress`; this file collects the
*human* checks (code reviews, on-device walkthroughs) those automated gates cannot cover. Phases
append their manual items here as they land, so the final pass is a single run-through.

Convention: `- [x]` pending, `- [x]` done. Each item mirrors a `#### Manual` row in `plan.md`.

---

## Phase 1 — Sequence Interpreter (pure domain)

Pure logic, no UI/device — both checks are a **code read**, not an app walkthrough.

### 1.5 — Corpus coverage review

Open `SmartChessboard/shared/src/commonTest/.../domain/board/SequenceInterpreterTest.kt` and confirm
every move shape + rejection is covered, and that the **expected squares are chess-correct** and the
**lift/place orderings are genuinely distinct** (not a copy):

- [x] Quiet move — pawn + piece: `quietMoveResolvesToTheLiftPlacePair`, `quietPieceMoveResolves`
- [x] Capture **CAPTURED_FIRST**: `pawnCaptureCapturedFirstResolves`
- [x] Capture **MOVER_FIRST**: `pieceCaptureMoverFirstResolves`
- [x] Castling **KING_FIRST / ROOK_FIRST / INTERLEAVED**: `whiteKingsideCastle{KingFirst,RookFirst,Interleaved}Resolves`
- [x] Castling **both wings + both colours**: `whiteQueensideCastleResolves`, `blackKingsideCastleResolves`, `blackQueensideCastleInterleavedResolves`
- [x] En passant **both orders**: `enPassantCapturedFirstResolves`, `enPassantMoverFirstResolves`
- [x] Promotion push + capture → `NeedsPromotion`: `promotionPushNeedsPromotion`, `promotionCaptureNeedsPromotion`
- [x] j'adoube ignored / no-move → Incomplete: `jadoubeBeforeARealMoveIsIgnored`, `jadoubeWithNoMoveIsIncomplete`
- [x] Rejections — Incomplete (lone lift, empty), Illegal (off-board, illegal capture): `aLoneLiftIsIncomplete`, `noEventsAreIncomplete`, `anOffBoardLandingIsIllegal`, `anIllegalCaptureIsIllegal`
- [x] Near-ambiguity resolves uniquely from the origin lift: `twoKnightsToTheSameSquareResolveByTheLiftedOriginNotAmbiguous`

### 1.6 — Only `legalMoves`-sourced moves (never hand-built)

- [x] Test side: `assertResolved` asserts `resolution.move in legalMoves(position)`.
- [x] Interpreter side (`SequenceInterpreter.kt`): the only `Move` returned is `moves.single()` where
      `moves ⊆ legalMoves(position)`; no `Move(...)` built from scratch on any return path;
      `NeedsPromotion` returns `from`/`to` ints only.

**Adaptations to note during review (not defects):**
1. `Resolution` lives in its own `Resolution.kt` (the engine's `Move.kt`-vs-`ChessRules.kt` pattern)
   to satisfy the enforced ktlint `standard:filename` rule; `resolvePhysicalMove` stays in
   `SequenceInterpreter.kt` per the plan.
2. `Resolution.Ambiguous` is a **defensive, in-practice-unreachable** branch — under a full lift/place
   stream no two distinct legal moves share a footprint (origin lift identifies the piece, destination
   lift distinguishes capture from quiet). Candidate `lessons.md` entry in Phase 5.

---

## Phase 2 — Data & Platform Seams

Pure seams, no UI/device — both checks are a **code read**, not an app walkthrough.

### 2.4 — `supportsPhysicalBoard` actuals (true on Android/iOS, false on wasm)

Open `SmartChessboard/shared/src/{androidMain,iosMain,wasmJsMain}/.../platform/PlatformCapabilities.*.kt`
and the `commonMain` expect, and confirm:

- [x] `commonMain/.../platform/PlatformCapabilities.kt` declares `expect val supportsPhysicalBoard: Boolean` (public — gates picker + routing in Phase 4).
- [x] `androidMain/.../platform/PlatformCapabilities.android.kt` → `actual val supportsPhysicalBoard = true`.
- [x] `iosMain/.../platform/PlatformCapabilities.ios.kt` → `actual val supportsPhysicalBoard = true`.
- [x] `wasmJsMain/.../platform/PlatformCapabilities.wasmJs.kt` → `actual val supportsPhysicalBoard = false` (web is digital-only per `lessons.md`).

### 2.5 — `BoardScenarios` + emulator tests still in `commonTest` (no test DSL shipped in `commonMain`)

Confirm the emulator promotion moved **only** the production board, leaving the chess-agnostic
scenario DSL and its tests test-only:

- [x] `commonMain/.../data/board/emulator/` holds **only** `EmulatedBoard.kt`.
- [x] `commonTest/.../data/board/emulator/` still holds `BoardScenarios.kt`, `EmulatedBoardTest.kt`, `EmulatedBoardEndToEndTest.kt`.
- [x] `EmulatedBoard.kt` is unchanged in behavior (only its trailing "lives in commonTest" doc comment was updated to reflect the promotion).

**Adaptation to note during review (not a defect):**
1. `GamesRepository.createGame` gained a `mode: GameMode` argument (interface, Supabase impl, fake).
   `NewGameViewModel` threads only `GameMode.DIGITAL` for now — the Digital/Physical picker that lets a
   user choose `PHYSICAL` is a **Phase 4** change, so Phase 2 keeps the build green without it.
2. `parseMode` / `toModeColumn` were made `internal` (were `private`) so `CreateGameModeTest` can prove
   the `"physical"` column round-trip directly without a live Supabase client.

---

## Phase 3 — Physical Play State Machine (MVI core, headless)

Headless logic, no UI/device — both checks are a **code read**, not an app walkthrough.

### 3.5 — Reducer reviewed for IO-freedom

Open `presentation/physical/PhysicalPlayReducer.kt` and confirm `reduce` (and its helpers) call **no**
repository / journal / board APIs — only the pure engine functions:

- [x] No `gamesRepository`, `autoSaver`, `journal`, or `boardConnection` reference anywhere in the reducer.
- [x] The only chess calls are pure: `resolvePhysicalMove`, `status`, `gameResultFor`, `toOccupancy`.
- [x] The §6.2 journal write is reached **only** via the `CommitMove` / `FinishGame` effects the
      `PhysicalPlayViewModel` interprets — the state advances to an accepted move solely on the
      `MoveCommitted` feedback, never inside `reduce`.

### 3.6 — MVI justification captured in the plan

- [x] `plan.md` Phase 3 carries the "MVI justification (required by `lessons.md`)" paragraph (MVI for
      `PhysicalPlayViewModel` only; digital `PlayViewModel` stays MVVM).

**Adaptations to note during review (not defects):**
1. **Auto-close is two-step (plan's literal design).** A mating confirm journals the move
   (`CommitMove` → `acceptMove`), then `MoveCommitted` recomputes status in the pure reducer and emits
   `FinishGame` (S-05 close). This keeps the auto-close decision in the testable reducer; the extra
   local journal write is superseded by the finish and self-heals via `GameAutoSaver`'s
   supersede + terminal keep-retry.
2. **`PhysicalEffect.LoadGame` is a `data object`** (not carrying `gameId`): `gameId` is a ViewModel
   constant (as in `PlayViewModel`), so the VM supplies it; this also lets `Retry` re-load purely.
3. **`FinishGame` carries `sanMoves`, not a pre-built `pgn`** (the plan's shape): `writePgn` needs the
   `PgnMeta` that lives in the impure VM, so the VM serializes; the reducer stays free of `meta`/IO.
4. **No `Connect` effect.** `BoardConnection` exposes no `connect()` (transport lifecycle is the
   adapter's, per its port doc); the VM subscribes before the platform/test drives the emulator's
   `connect()`, and re-requests a snapshot via `Send(RequestSnapshot)` on `BoardConnected`
   (proper reconnect reconcile is S-07). `paused` is derived from `connectionState`, not a stored field.

---

## Phase 4 — Physical Play UI, Navigation & DI

Unlike phases 1–3, these are **app/device walkthroughs**, not pure code reads — run them on a real
device/simulator and a browser at the end of the slice.

### 4.4 — Android/iOS: picker → physical screen connects + verifies opening position

- [x] New game shows a **Digital / Physical** toggle (only on Android/iOS).
- [x] Pick **Physical** → Start → lands on `PhysicalPlayScreen` (title = "White vs Black").
- [x] The board renders the start position; no "set up the board" warning (the emulator connects on
      bind and the opening occupancy verifies). No in-app driver — move input is the emulator/test
      or the S-09 real board.

### 4.5 — Web: no Physical option; a physical game opens in Replay; browser Back behaves

- [x] On web, New game shows **no** Digital/Physical toggle (defaults digital).
- [x] A physical game synced from mobile opens in **Replay** (read-only), never a board screen.
- [x] Browser Back from that Replay returns to History (hierarchical browser nav).

### 4.6 — No `BoardConnection` / physical VM resolvable on wasm (DI gating holds)

- [x] **Code read**: `di/PlatformModule.wasmJs.kt` binds **neither** `BoardConnection` nor
      `PhysicalPlayViewModel` (only `Settings`); both are bound only in the Android/iOS actuals.
- [x] **Web run**: nothing routes to `PhysicalPlayKey` on web (gated by `supportsPhysicalBoard`), so
      the unbound physical VM is never resolved.

**Adaptations to note during review (not defects):**
1. **Emulator connects on DI bind.** The Android/iOS `platformModule` constructs `EmulatedBoard` on a
   long-lived `CoroutineScope(SupervisorJob() + Dispatchers.Default)` and launches `connect()` there.
   The port has no `connect()` (transport lifecycle is the adapter's, S-09), so the board is driven
   live at bind time; the ViewModel's `Send(RequestSnapshot)` on the CONNECTED transition recovers a
   possibly-missed on-connect burst. Real connection lifecycle (scan/pair/retry) is S-09.
2. **`ChessBoardView` gained `highlightedSquares: Set<Int> = emptySet()`** — a display-only tint
   independent of `interaction`; empty default leaves Replay/Play pixel-identical.
3. **`NewGameScreen.onGameCreated` is now `(String, GameMode)`** and the mode toggle is stored as a
   `Boolean` (`rememberSaveable`, no enum saver needed). `NewGameViewModel.create` takes the mode and
   exposes `createdGameMode` so routing branches physical → `PhysicalPlayKey`, digital → `PlayKey`.

---

## Phase 5 — Emulator-Driven End-to-End & Write-Backs

5.4 / 5.5 are reads (E2E source + foundation docs); 5.6 is a device spot-check.

### 5.4 — Stored E2E PGN reviewed (legal moves, `[Mode "physical"]`, correct result tag)

Open `presentation/physical/PhysicalCaptureEndToEndTest.kt` and confirm the asserted, round-tripped PGNs:

- [x] Scripted game: `e4 e5 Nf3 Nc6 Bb5 a6 Bxc6 dxc6 O-O` (both capture orders + interleaved castle),
      `[Mode "physical"]`, manual draw `1/2-1/2`, `parsePgn` round-trips to the same positions.
- [x] En passant `exd6` resolves from the stream and round-trips.
- [x] Promotion `bxa8=Q`: the place raises the picker, a confirm-before-pick saves nothing, the pick commits.
- [x] Fool's mate `f3 e5 g4 Qh4#` auto-closes with result `0-1`.
- [x] A wrong-side confirm appends no SAN.

### 5.5 — Roadmap / lessons write-backs reviewed for accuracy

- [x] `roadmap.md`: S-06 → `implemented` (At-a-glance table + Stream C note + slice Status with phase SHAs).
- [x] `lessons.md`: the "engine move-geometry mirrored outside `domain/chess` must be SYNC-commented" entry.

### 5.6 — Device spot-check

- [x] On a device, a created physical game appears in History with the **Physical** label and opens the
      physical screen on mobile (and Replay on web).

---

## Pending (rolled up at archive)

All `#### Manual` rows above stay `- [x]` in `plan.md` by design — they are this single end-of-slice
pass. Run them, tick them here and in `plan.md`, then `/10x-impl-review` and `/10x-archive`.
