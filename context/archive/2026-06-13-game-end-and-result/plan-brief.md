# Game End and Result (S-05) — Plan Brief

> Full plan: `context/changes/game-end-and-result/plan.md`

## What & Why

Close a digital game and record its result. Checkmate/stalemate close the game automatically with
the correct result (FR-007); any in-progress game can be ended manually with win/loss/draw (FR-018)
— the only way draw-by-rule games and resignations close in MVP. Closing writes one canonical
transition: `status → finished`, `result`, and the PGN `[Result]` tag, completing the after-game
record promised by US-01.

## Starting Point

S-04 already **detects** mate/stalemate, blocks input, and shows a "Checkmate — White wins" banner —
but the record stays `in_progress` and every PGN carries `[Result "*"]` (`meta.result` is hardcoded
`"*"`). The `GamesRepository` has no finalization method, and the write-ahead journal flushes only
PGN. The `status`/`result` columns (with CHECK constraints) and the finished→Replay routing already
exist; the engine's `status()` and the PGN writer's result handling are ready.

## Desired End State

Deliver mate (or stalemate) → the game closes itself: final banner, frozen board, result persisted
locally then cloud. Or tap **End game** → pick White/Black/Draw → confirm → same closure. A finished
game shows Analyse / Back-to-history actions, reopens read-only in Replay with the right result, and
shows as finished in History. Finishing works offline and survives a crash before flush. Verified on
Android, iOS, and web.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Manual-end entry point | "End game" button under the board (in-progress only) | One tap, discoverable, no new layout scaffold | Plan |
| Result picker | 3 options + confirmation step | Guards the durable record against an accidental close | Plan |
| End-state presentation | Inline final banner + actions (auto + manual) | Extends the existing `StatusBanner`; no new overlay | Plan |
| Post-finish navigation | Stay on a frozen Play screen + Analyse/History actions | Player sees the outcome and chooses where to go next | Plan |
| Offline finalization | Offline-safe via the journal (carries the result) | Matches §6.2 and "auto-detect closes the game"; survives crashes | Plan |
| Reversibility | Irreversible, read-only (opens in Replay) | Simple canonical-record model; matches finished→Replay routing | Plan |
| Persistence shape | `finishGame(id, result, pgn)` — one atomic UPDATE | Cloud row never half-finished; widens contract §3.2 | Plan |
| Result mapping | Pure tested helpers (state→result, result→token) | Winner inference out of the VM; green on Native (lessons.md) | Plan |
| Testing | Full set + 3-surface E2E | Covers Native/web/iOS regression classes seen in prior slices | Plan |

## Scope

**In scope:** result mapping + PGN result serialisation; `finishGame` repo method; offline-safe
journal/auto-saver finish path + cleanup + reconcile; ViewModel auto + manual closure; End-game
button + result/confirm dialog + finished banner/actions + nav callbacks; three-surface E2E;
roadmap/contract/change write-backs.

**Out of scope:** schema/migration (columns exist); draw-by-rule auto-detection; un-finish / resume /
edit-result; resignation tag in PGN; physical mode (S-06); deletion, takeback, animations.

## Architecture / Approach

Bottom-up along reuse boundaries (as S-02/S-04): pure mapping + PGN → durable/offline-safe
persistence → ViewModel choreography → screen + nav → E2E. Closure end-to-end: map state →
`GameResult`, rebuild `PgnMeta` with the token, `writePgn`, synchronous **finish-aware** journal
write (the §6.2 gate), frozen finished UI, best-effort flush via `finishGame` (atomic
status+result+pgn). Journal entry cleared only after a confirmed flush; `reconcile` re-flushes a
journaled-but-unsynced finish so an offline close survives a crash.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Mapping & PGN result | Tested state→result→token + finished-PGN round-trip | Native vs JVM divergence; wrong winner inference |
| 2. Finalization persistence | `finishGame` + offline-safe journal/auto-saver + cleanup | Reconcile/cleanup ordering on a crashed offline finish |
| 3. ViewModel (auto + manual) | Auto-finish + manual intents + frozen finished state | Double-close / single-fire; terminal-vs-finished modelling |
| 4. UI & navigation | End-game button, result/confirm dialog, banner + nav | Discoverability vs board-action confusion; Play→Replay swap |
| 5. E2E & write-backs | 3-surface proof + roadmap/contract/change updates | Offline/sync surprises against the hosted backend |

**Prerequisites:** S-04 merged (present); local Docker not needed (no migration); hosted-project
access for the offline + E2E gates.
**Estimated effort:** ~3–4 sessions across 5 phases; Phases 2 and 4 are the bulk.

## Open Risks & Assumptions

- Extending `JournalEntry` with the result is assumed cheaper than re-deriving status from the PGN
  `[Result]` tag at sync time; if the settings backend makes a third key awkward, parsing the tag is
  the fallback.
- Finished-on-load is defensive only (routing already sends finished games to Replay).
- The Play→Replay "Analyse" action should replace Play on the back stack so Back doesn't return to a
  frozen Play; web browser-history fragments inherit S-02's binds-once caveat (accepted in
  lessons.md).
- Irreversibility means a mis-tapped manual result has no in-app correction — the confirmation step
  is the only guard (accepted).

## Success Criteria (Summary)

- Checkmate/stalemate auto-close with the correct result; manual end records win/loss/draw — both
  persist `status`/`result`/PGN `[Result]` and survive offline + crash.
- A finished game is read-only, shows its result in History and Replay, and reopens in Replay.
- Per-target suites (incl. Native) and a three-surface E2E pass.
