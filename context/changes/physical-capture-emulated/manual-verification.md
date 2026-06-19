# Physical-Mode Capture (S-06) — Manual Verification Checklist

Deferred manual checks for the whole slice, to run **at the end of S-06 before archiving**. The
automated success criteria are gated per phase in `plan.md` → `## Progress`; this file collects the
*human* checks (code reviews, on-device walkthroughs) those automated gates cannot cover. Phases
append their manual items here as they land, so the final pass is a single run-through.

Convention: `- [ ]` pending, `- [x]` done. Each item mirrors a `#### Manual` row in `plan.md`.

---

## Phase 1 — Sequence Interpreter (pure domain)

Pure logic, no UI/device — both checks are a **code read**, not an app walkthrough.

### 1.5 — Corpus coverage review

Open `SmartChessboard/shared/src/commonTest/.../domain/board/SequenceInterpreterTest.kt` and confirm
every move shape + rejection is covered, and that the **expected squares are chess-correct** and the
**lift/place orderings are genuinely distinct** (not a copy):

- [ ] Quiet move — pawn + piece: `quietMoveResolvesToTheLiftPlacePair`, `quietPieceMoveResolves`
- [ ] Capture **CAPTURED_FIRST**: `pawnCaptureCapturedFirstResolves`
- [ ] Capture **MOVER_FIRST**: `pieceCaptureMoverFirstResolves`
- [ ] Castling **KING_FIRST / ROOK_FIRST / INTERLEAVED**: `whiteKingsideCastle{KingFirst,RookFirst,Interleaved}Resolves`
- [ ] Castling **both wings + both colours**: `whiteQueensideCastleResolves`, `blackKingsideCastleResolves`, `blackQueensideCastleInterleavedResolves`
- [ ] En passant **both orders**: `enPassantCapturedFirstResolves`, `enPassantMoverFirstResolves`
- [ ] Promotion push + capture → `NeedsPromotion`: `promotionPushNeedsPromotion`, `promotionCaptureNeedsPromotion`
- [ ] j'adoube ignored / no-move → Incomplete: `jadoubeBeforeARealMoveIsIgnored`, `jadoubeWithNoMoveIsIncomplete`
- [ ] Rejections — Incomplete (lone lift, empty), Illegal (off-board, illegal capture): `aLoneLiftIsIncomplete`, `noEventsAreIncomplete`, `anOffBoardLandingIsIllegal`, `anIllegalCaptureIsIllegal`
- [ ] Near-ambiguity resolves uniquely from the origin lift: `twoKnightsToTheSameSquareResolveByTheLiftedOriginNotAmbiguous`

### 1.6 — Only `legalMoves`-sourced moves (never hand-built)

- [ ] Test side: `assertResolved` asserts `resolution.move in legalMoves(position)`.
- [ ] Interpreter side (`SequenceInterpreter.kt`): the only `Move` returned is `moves.single()` where
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

- [ ] `commonMain/.../platform/PlatformCapabilities.kt` declares `expect val supportsPhysicalBoard: Boolean` (public — gates picker + routing in Phase 4).
- [ ] `androidMain/.../platform/PlatformCapabilities.android.kt` → `actual val supportsPhysicalBoard = true`.
- [ ] `iosMain/.../platform/PlatformCapabilities.ios.kt` → `actual val supportsPhysicalBoard = true`.
- [ ] `wasmJsMain/.../platform/PlatformCapabilities.wasmJs.kt` → `actual val supportsPhysicalBoard = false` (web is digital-only per `lessons.md`).

### 2.5 — `BoardScenarios` + emulator tests still in `commonTest` (no test DSL shipped in `commonMain`)

Confirm the emulator promotion moved **only** the production board, leaving the chess-agnostic
scenario DSL and its tests test-only:

- [ ] `commonMain/.../data/board/emulator/` holds **only** `EmulatedBoard.kt`.
- [ ] `commonTest/.../data/board/emulator/` still holds `BoardScenarios.kt`, `EmulatedBoardTest.kt`, `EmulatedBoardEndToEndTest.kt`.
- [ ] `EmulatedBoard.kt` is unchanged in behavior (only its trailing "lives in commonTest" doc comment was updated to reflect the promotion).

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

- [ ] No `gamesRepository`, `autoSaver`, `journal`, or `boardConnection` reference anywhere in the reducer.
- [ ] The only chess calls are pure: `resolvePhysicalMove`, `status`, `gameResultFor`, `toOccupancy`.
- [ ] The §6.2 journal write is reached **only** via the `CommitMove` / `FinishGame` effects the
      `PhysicalPlayViewModel` interprets — the state advances to an accepted move solely on the
      `MoveCommitted` feedback, never inside `reduce`.

### 3.6 — MVI justification captured in the plan

- [ ] `plan.md` Phase 3 carries the "MVI justification (required by `lessons.md`)" paragraph (MVI for
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

## Phase 4+ — appended as phases land
