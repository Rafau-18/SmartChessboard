# Delete Game from History — Plan Brief

> Full plan: `context/changes/delete-game-from-history/plan.md`

## What & Why

Add the first destructive user action to the app: permanently delete one of your own games from the History list (FR-021, US-04 — promoted to must-have 2026-07-02 to complete CRUD for the durable game record). Hard delete, no trash/undo, any game status, identical on Android, iOS, and web.

## Starting Point

The backend has been ready since S-01: RLS policy `games_delete_own` and the `DELETE FROM games WHERE id = $1` contract surface exist; no schema change is needed. The client has nothing: no `deleteGame` in `GamesRepository`, no row-level actions on the History list, no delete-aware journal cleanup. The building blocks are all in place — the `changes: SharedFlow<Unit>` push-refresh signal, `GameJournal.clear(gameId)`, the `EndGamePicker` confirmation-dialog pattern, and the `NewGame` in-flight/failure pattern.

## Desired End State

Every History row has a kebab (⋮) menu with "Delete". Confirming in an explicit dialog deletes the cloud row, clears the game's local journal entry, and the row disappears from the list on all signed-in devices. Cancelling changes nothing; failures show inline in the dialog with Retry.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Delete scope & semantics | Hard delete, one game, any status, confirmation required, history surface only | Settled upstream; completes CRUD with a guardrail on the irreversible action | PRD / Roadmap |
| Row affordance | Kebab (⋮) + DropdownMenu | User choice: leaves room for future per-game actions (e.g. digital↔physical conversion) | Plan |
| Confirmation surface | Dialog modeled on `EndGamePicker` | Existing proven pattern with irreversibility copy; zero new conventions | Plan |
| Offline behavior | Online-only delete; amend contract §3.4 | History itself is cloud-only, so delete is unreachable offline; a tombstone queue is big machinery for no MVP gain | Plan |
| Failure UX | Inline error + Retry inside the dialog | Matches the `NewGame` creating/failed pattern; no snackbar infra exists | Plan |
| Journal safety | `GameDeleter` domain orchestrator: cloud delete first, then `journal.clear` | One entry point so no caller can forget the journal half; failed delete leaves durability intact | Plan |

## Scope

**In scope:** `GamesRepository.deleteGame` + Supabase impl (+ `changes` emission), `GameDeleter` (repo + journal), Koin wiring, HistoryViewModel delete-prompt state machine, kebab menu + confirmation dialog on HistoryScreen, unit tests on 3 targets, `contract-surfaces.md` §3.4 amendment + matching dated note in `prd.md` Implementation Decisions.

**Out of scope:** offline delete queue/tombstones, bulk delete, trash/undo/restore, delete from Play/Replay, `position_evals` cleanup, snackbar infrastructure, localization.

## Architecture / Approach

One shared path across all three targets. UI (kebab → dialog) drives `HistoryViewModel`, which calls `GameDeleter.delete(gameId)` — cloud `DELETE` (RLS-scoped) first, then `journal.clear(gameId)` so the deleted game cannot resurrect via reconcile. On success the repository emits the existing `changes` signal and the retained History screen refreshes itself (established push-driven-refresh pattern). The delete-vs-in-flight-sync race is benign: sync uses `UPDATE` only, which silently matches zero rows after a delete.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Domain & data delete path | `deleteGame` + `GameDeleter` + DI + tests + §3.4 amendment (invisible) | Ordering bug (journal cleared on a failed delete) — pinned by unit test |
| 2. History presentation | Kebab menu, confirmation dialog, in-flight/failure states, tests + manual gate | First DropdownMenu in the app; web mouse-driven behavior — covered by 3-surface manual gate |

**Prerequisites:** none — S-01 shipped the backend; no schema work.
**Estimated effort:** ~1–2 sessions across 2 phases.

## Open Risks & Assumptions

- Assumes Postgrest `delete` on zero matching rows succeeds silently (idempotent double-delete) — consistent with observed `update` behavior; verify casually during the manual gate.
- The kebab is the app's first row-level menu; if it feels cramped on small phones, spacing tweaks stay within Phase 2 scope.
- The wasm failure shape (`kotlin.Error`, not `Exception`) is handled by contract (`catch (Throwable)`) and covered by a dedicated test, per the standing lesson.

## Success Criteria (Summary)

- A player can delete any of their own games from History after an explicit confirmation — and only their own (RLS).
- The deleted game disappears from the list without manual refresh, from other signed-in devices, and never resurrects after an app restart (journal cleared).
- Cancelling or failing deletes nothing; failures are visible with a working Retry — on all three surfaces.
