<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Physical-Mode Capture Against the Emulator (S-06)

- **Plan**: context/changes/physical-capture-emulated/plan.md
- **Scope**: Phases 1–5 of 5 (full plan)
- **Commits reviewed**: 3dbf1cf..c9442c7 (p1–p5 + epilogue)
- **Date**: 2026-06-19
- **Verdict**: APPROVED
- **Findings**: 0 critical · 1 warning · 0 observations
- **Triage**: F1 FIXED (2026-06-19 — comment-only `TODO(S-09)` on both `BoardConnection` bindings)

> Phase 1 (3dbf1cf) was reviewed separately in `impl-review-phase-1.md` (APPROVED; F1/F2/F3
> all FIXED — comment/doc only). This full review covers phases 1–5 holistically and does not
> re-open the resolved phase-1 findings.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING (1 finding — F1, low/forward-looking) |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

**Overall: APPROVED** — all PASS plus one low, forward-looking warning (fix is comment-only).

### Live verification (this review, tree at c9442c7)

- `:shared:testAndroidHostTest` — BUILD SUCCESSFUL (covers interpreter, reducer, VM, and E2E — all live in `commonTest`).
- ktlint — clean on all new S-06 files (`presentation/physical/**`, `domain/board/{Occupancy,Resolution,SequenceInterpreter}.kt`).
- iOS / wasm — relying on the phase-5 recorded green (`f8903b5` ran the full `:shared` suite on `:shared:iosSimulatorArm64Test` and `:shared:wasmJsTest`); the only commit since is the docs-only epilogue (`c9442c7`).

## Focus areas verified (requested)

1. **§6.2 acceptance gate lives in the `CommitMove` effect, not the reducer — CORRECT.** `reduce` does no IO;
   `confirm()` (PhysicalPlayReducer.kt:249-254) only emits `PhysicalEffect.CommitMove`; state advances solely in
   `commit()` on `MoveCommitted`. The VM (PhysicalPlayViewModel.kt:152-171) runs
   `validate → sanForMove → writePgn → autoSaver.acceptMove` then dispatches `MoveCommitted`; a throw dispatches
   `MoveRejected(SAVE_FAILED)`. Proven by `aForcedJournalWriteFailureRejectsTheMoveAndDoesNotAdvance`. The Android
   DI also sets `Settings(commit = true)` (PlatformModule.android.kt:28) so the journal write is durable before a
   move counts as accepted.
2. **Two-step auto-close (reducer emits `FinishGame` on `MoveCommitted`) — CORRECT.** `commit()`
   (PhysicalPlayReducer.kt:298-322) recomputes `status` and, when `gameResultFor(...) != null`, emits `FinishGame`;
   the VM's `finishGame()` writes the finished PGN and supersedes the local entry (adaptation #1). E2E
   `aMatingMoveAutoClosesTheGame` is green.
3. **No `Connect` effect (port has no `connect()`) — CORRECT.** `BoardConnection` exposes only
   `connectionState`/`events`/`send`; `connect()` is a concrete `EmulatedBoard` method driven by the DI layer. The
   VM subscribes to all streams in `init` before `load()` (PhysicalPlayViewModel.kt:60-78); `paused` is derived
   (`connectionState == DISCONNECTED`). Satisfies the hot/replay=0 subscribe-before-connect rule.
4. **Adaptations in `manual-verification.md` — all accurate and defensible** when reconciled against code:
   Connect-dropped, `LoadGame` as `data object`, `FinishGame(sanMoves)`, `MoveRejected` on the `RejectionReason`
   enum, `highlightedSquares` tint, `onGameCreated(String, GameMode)`, emulator connect-on-bind, and the E2E
   asserting the repository (cloud) PGN because `GameAutoSaver` clears a finished entry after the finish flush.

## Scope reconciliation

- **In plan AND in diff**: every file across phases 1–5 present and implemented as intended (verified by the drift
  sub-agent, item-by-item).
- **"What We're NOT Doing" boundaries — all held**: no real BLE (only `EmulatedBoard` bound); no diagnostics/recovery
  UI (rejection = message only); no reconnect-reconcile / resume (disconnect → `paused` only); no GUI board simulator
  (`interaction = null`); no DB migration (`git diff -- supabase/` empty); no `GameJournal`/`GameAutoSaver` changes
  (absent from the diff).
- **Deviations are documented, API-forced adaptations (not scope creep)**: `Connect` effect dropped, `LoadGame`/
  `FinishGame` reshaped, `PhysicalMsg.SyncChanged` added (the plan's contract was an explicit sketch — "exact fields
  finalized in code").
- **`StatusBanner`/`SyncIndicator` re-implemented locally** in `PhysicalPlayScreen` (PhysicalPlayScreen.kt:257,:298)
  rather than imported: the digital versions are `private fun` typed on `PlayState`, so literal reuse is blocked —
  and per-screen private composables *is* the repo convention (PlayScreen does the same). Examined; not a defect.
- **Phase 1 (F1/F2/F3)** already reviewed and FIXED in `impl-review-phase-1.md`; not re-opened here.

## Findings

### F1 — BoardConnection DI scope is never cancelled (no teardown hook for the S-09 swap)

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Reliability)
- **Location**: SmartChessboard/shared/src/androidMain/.../di/PlatformModule.android.kt:35-36 ; SmartChessboard/shared/src/iosMain/.../di/PlatformModule.ios.kt:24-25
- **Detail**: The `single<BoardConnection>` creates `CoroutineScope(SupervisorJob() + Dispatchers.Default)`,
  launches `connect()` on it, and never cancels the scope; `EmulatedBoard.disconnect()` is never called from
  production. For the emulator this is plan-adherent (the Phase-4 DI contract asked for a "long-lived app scope")
  and harmless — one process-lifetime singleton, `SupervisorJob`-isolated, the only cost an idle ~30s status loop.
  The gap is forward-looking: when S-09 binds a real BLE adapter on this exact shape, "never cancelled" becomes a
  real connection/resource leak with nothing (compiler or comment) flagging it. The existing comment explains
  connect-on-bind but not the missing teardown — and this repo's own culture (lessons.md SYNC-comment +
  terminal-flush rules) is to flag precisely these forward couplings.
- **Fix**: Add a one-line SYNC/TODO comment on both `single<BoardConnection>` blocks tying scope-cancellation /
  `disconnect()` to the S-09 BLE adapter swap (optionally register Koin `onClose { (it as? EmulatedBoard)?.disconnect() }`).
  Comment-only; no behavior change, three-target green holds.
- **Decision**: FIXED (2026-06-19) — added a `TODO(S-09)` block above both `single<BoardConnection>` bindings
  (PlatformModule.android.kt / PlatformModule.ios.kt) tying scope-cancellation / `disconnect()` to the BLE
  adapter swap and cross-referencing each module. Comment-only; `:shared:testAndroidHostTest` + ktlint stay green.

## Notes

- The slice is genuinely high quality: the §6.2 gate, bit math (sign-bit at bit 63 handled via `and … != 0L`),
  subscribe-before-connect ordering, wasm `Throwable` discipline, single-fire finish guard, and the bidirectional
  SYNC comments (`footprintOf` ↔ `applyMove`) are all correct and test-covered.
- Success Criteria: automated 5/5 phases green (recorded SHAs + live JVM-host re-run + ktlint clean). All `#### Manual`
  rows remain `[ ]` by the project's documented convention (single end-of-slice pass collected in
  `manual-verification.md`). This review substantively confirms the code-read manual items (corpus coverage 1.5/1.6,
  reducer IO-freedom 3.5, `supportsPhysicalBoard` actuals 2.4, DI gating 4.6, write-backs 5.4/5.5); the on-device
  walkthroughs (4.4 / 4.5 / 5.6) remain genuine human gates not performed in this review.
