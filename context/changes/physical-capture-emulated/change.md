---
change_id: physical-capture-emulated
title: Physical-mode capture against the emulator (S-06)
status: impl_reviewed
created: 2026-06-13
updated: 2026-06-19
archived_at: null
---

## Notes

- 2026-06-19: planned. Roadmap S-06 — the project's hardest bet (lift/place sequence → exactly one
  legal move; captures/castling/ep read from the full sequence). Built on F-01 (rules), F-02
  (emulator), S-04 (durable record, reused verbatim). Key decisions: emulator-driven E2E is the
  acceptance proof (real-board interactive play = S-09); minimal rejection (diagnostics/recovery =
  S-07); pause-only on disconnect (reconcile = S-07, resume = S-08); **MVI** for
  `PhysicalPlayViewModel` (digital stays MVVM); `expect/actual supportsPhysicalBoard` gates web;
  confirm button must match side-to-move (wrong = no-op); light start-position occupancy check;
  highlight lifted squares; promotion picker on detection; White-bottom + flip. No DB migration
  (`games.mode` already accepts `'physical'`). See `plan-brief.md` then `plan.md`.
- 2026-06-19: Phase 1 (sequence interpreter, pure domain) implemented & committed (b969215).
  Automated 1.1–1.4 green on JVM/iOS/wasm + ktlint; manual 1.5/1.6 deferred to end-of-slice
  (`manual-verification.md`). Two adaptations: `Resolution` split into `Resolution.kt` for the ktlint
  `standard:filename` rule (resolver fn stays in `SequenceInterpreter.kt`); `Resolution.Ambiguous` is
  a defensive/unreachable branch (no two legal moves share a footprint under a full lift/place stream)
  — candidate lesson for Phase 5.
- 2026-06-19: impl-review (phase 1) — APPROVED. Automated 1.1–1.4 re-verified green on
  JVM/iOS/wasm + ktlint clean. F1 (warning): `footprintOf` re-derives castle/en-passant/capture
  geometry rather than reusing `SanWriter`/`PgnParser` (a 3rd copy of `applyMove`'s arithmetic);
  literal reuse blocked (those helpers are `private`/`internal`, return SAN/Boolean/`Position`, not a
  footprint). Resolved via SYNC cross-reference comments binding `footprintOf` ↔ `applyMove`.
  Candidate Phase-5 lesson: engine geometry hand-mirrored for physical resolution must be
  SYNC-commented until a shared `squaresTouched(position, move)` helper exists.
- 2026-06-19: Phase 2 (data & platform seams) implemented. Automated 2.1–2.3 green on JVM/iOS/wasm +
  ktlint; manual 2.4/2.5 (code-read only) deferred to end-of-slice (`manual-verification.md`).
  `createGame` gained a `mode: GameMode` arg (interface + Supabase impl + fake `FakeGamesRepository`);
  `NewGameViewModel` threads only `DIGITAL` for now — the Digital/Physical picker is Phase 4.
  `expect/actual supportsPhysicalBoard` added under `platform/` (true Android/iOS, false wasm).
  `EmulatedBoard` promoted to `commonMain` via `git mv` (history preserved); `BoardScenarios` + emulator
  tests stay in `commonTest`. `parseMode`/`toModeColumn` made `internal` so `CreateGameModeTest` proves
  the `"physical"` column round-trip without a live client. Contract write-backs: `contract-surfaces.md`
  §3.2 create-op note + `prd.md` Implementation Decisions (both `updated` bumped).
- 2026-06-19: Phase 3 (physical-play MVI core, headless) implemented. Automated 3.1–3.4 green on
  JVM/iOS/wasm + ktlint; manual 3.5/3.6 (code-read only) deferred to end-of-slice
  (`manual-verification.md`). New `presentation/physical/`: `PhysicalPlayContract` (Msg/State/Effect),
  pure `PhysicalPlayReducer`, impure `PhysicalPlayViewModel` (stream collectors + effect interpreter),
  + headless `PhysicalPlayReducerTest`/`PhysicalPlayViewModelTest`. §6.2 gate lives in the `CommitMove`
  effect (state advances only on `MoveCommitted`; forced journal-write failure → `MoveRejected`, no
  advance — proven). Reuses S-04 back half (`GameAutoSaver`/journal/`writePgn`/`sanForMove`) verbatim;
  `EndGamePrompt`/`PendingPromotion` reused from `presentation/play`. Adaptations (see
  `manual-verification.md`): two-step auto-close (reducer emits `FinishGame` on `MoveCommitted`);
  `LoadGame` is a `data object` (VM owns `gameId`); `FinishGame` carries `sanMoves` (VM owns `meta`);
  no `Connect` effect (port has no `connect()`; VM subscribes-before-connect, `paused` derived from
  `connectionState`).
- 2026-06-19: Phase 4 (physical-play UI, navigation & DI) implemented. Automated 4.1–4.3 green:
  three `:shared` suites + `NewGameViewModel` `create(..., PHYSICAL)`; `:androidApp`/`:webApp` compile
  + `:shared:linkDebugFrameworkIosSimulatorArm64`; ktlint clean. Manual 4.4–4.6 (app/device + web
  walkthroughs) deferred to end-of-slice (`manual-verification.md`). `ChessBoardView` gained a
  display-only `highlightedSquares` tint; new `PhysicalPlayScreen` reuses `ChessBoardView`/
  `PromotionPicker`/`EndGamePicker`/`MoveList` + a connection/setup/paused/rejection surface;
  `NewGameScreen` shows a Digital/Physical toggle only when `supportsPhysicalBoard`, threading the
  mode through `create(white, black, mode)` and `onGameCreated(gameId, mode)`; new `PhysicalPlayKey`
  route (registered polymorphically) with `App.kt` History/creation routing (physical+capable →
  physical screen, else Replay); Android/iOS `platformModule` bind `EmulatedBoard` (connect-on-bind)
  + `PhysicalPlayViewModel`, wasm binds neither (web gating). See `manual-verification.md` adaptations.
- 2026-06-19: Phase 5 (emulator-driven E2E & write-backs) implemented. Automated 5.1–5.3 green:
  `PhysicalCaptureEndToEndTest` drives full scripted games through the real `PhysicalPlayViewModel` +
  reused journal/auto-saver on JVM/iOS/wasm (quiet, both capture orders, interleaved castle, en
  passant, promotion push→picker→pick→confirm, confirm-before-pick rejection, wrong-side no-op, mate
  auto-close, manual draw), asserting one SAN per confirm, `[Mode "physical"]`, and `parsePgn`
  round-trip; full `:shared` suite green on all three targets; ktlint clean. Write-backs: `roadmap.md`
  S-06 → implemented (table + Stream C + slice Status w/ phase SHAs); `lessons.md` entry (hand-mirrored
  engine geometry must be SYNC-commented). One adaptation: the E2E asserts the canonical PGN from the
  repository (cloud), because `GameAutoSaver` clears a finished game's journal entry after a confirmed
  finish flush. Manual 5.4–5.6 (E2E/doc reads + one device spot-check) deferred to end-of-slice; all
  `#### Manual` rows across phases 1–5 are the single pre-archive pass collected in `manual-verification.md`.
- 2026-06-19: full impl-review (phases 1–5) — **APPROVED** (`reviews/impl-review.md`). Verified live:
  `:shared:testAndroidHostTest` green + ktlint clean; iOS/wasm rely on the phase-5 recorded green (docs-only
  commit since). Focus areas confirmed correct: §6.2 gate in the `CommitMove` effect (state advances only on
  `MoveCommitted`; forced save-failure → `MoveRejected`), two-step auto-close (reducer emits `FinishGame` on
  `MoveCommitted`), no `Connect` effect (port has no `connect()`; subscribe-before-connect; `paused` derived). All
  "NOT doing" boundaries held; documented adaptations are API-forced, not scope creep. One finding **F1**
  (WARNING/LOW, PENDING): the `single<BoardConnection>` scope is never cancelled — harmless for the emulator
  (plan asked for a long-lived app scope) but a forward-looking leak for the S-09 BLE swap; fix is a one-line
  SYNC/TODO. Triage deferred (save-and-triage-later).
- 2026-06-19: F1 triaged → **FIXED** (comment-only). Added a `TODO(S-09)` block above both
  `single<BoardConnection>` bindings (`PlatformModule.android.kt` / `PlatformModule.ios.kt`) tying
  scope-cancellation / `disconnect()` to the BLE adapter swap and cross-referencing each module. No behavior
  change; `:shared:testAndroidHostTest` + ktlint re-verified green. Polish-language review mirror also saved
  (`reviews/impl-review.pl.md`). All findings now resolved.
- 2026-07-04: **manual gate ACCEPTED** — code-read rows (1.5–3.6) re-verified by Claude (capability flags true/true/false, wasm DI binds only `Settings`, interpreter returns only `legalMoves`-sourced moves); device rows (4.4–4.6, 5.6) user-confirmed. impl-review already APPROVED. Closing via `/10x-archive`.
