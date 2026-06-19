---
change_id: physical-capture-emulated
title: Physical-mode capture against the emulator (S-06)
status: implementing
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
