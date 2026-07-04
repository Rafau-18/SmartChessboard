# Digital Pass-and-Play with Durable Record (S-04) — Plan Brief

> Full plan: `context/changes/digital-pass-and-play/plan.md`

## What & Why

A signed-in player creates a digital game, plays pass-and-play on an interactive on-screen board
with every move validated by the F-01 engine before execution, resolves promotions via a piece
picker, and has every accepted move durably auto-saved with PGN as the source of truth (FR-003–
FR-006, FR-014, US-01). This is the heaviest user-facing slice — it turns the read-only review
loop (S-01→S-02) into a product that actually records new games.

## Starting Point

F-01's engine is complete (legalMoves/validate/status, incl. a dedicated
`PROMOTION_PIECE_REQUIRED` outcome) but deliberately has **no SAN generation or PGN writing** —
both were deferred to S-04. S-02 ships PGN *parsing*, a display-only `ChessBoardView`, Nav3
routes, and a read-only `GamesRepository` (list + get). The app has **zero local persistence**,
and `games.user_id` has no column default, so the project's first INSERT needs a small migration.

## Desired End State

On Android, iOS, **and web**, the player taps "New game", plays with tap-tap input and
legal-target highlights, promotes via picker, and can kill the app mid-game and resume from
History with zero lost moves. Offline play keeps working (local journal; non-blocking "sync
pending"); mate/stalemate blocks input with a banner (record stays `in_progress` — closing it is
S-05). The stored PGN replays identically in S-02's Replay view.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Durable auto-save split (roadmap unknown) | Local write-ahead journal (in-progress games) + best-effort cloud sync with retry; history stays cloud-read | Satisfies §6.2 ("durably stored locally before the next move") and the crash guardrail with minimal infra; full offline-first mirror deferred | Interview |
| Journal backend | multiplatform-settings (SharedPreferences `commitValues=true` / NSUserDefaults / localStorage) | Synchronous small-string store on all 3 targets; no DB lift for one journal | Plan |
| Mate/stalemate in S-04 | Detect & block input + banner; no status/result write | Respects the S-05 boundary (FR-007) while avoiding a dead-end UX — engine gives `status()` free | Interview |
| In-progress digital game from History | Continues play (finished → Replay) | Completes the crash-safety UX; `parsePgn` already rebuilds the state | Interview |
| Move input | Tap-tap with legal-target highlights | Simplest touch model; visually surfaces FR-005 validation; drag deferred | Interview |
| Board orientation | Fixed White-bottom + manual flip toggle | Works for both seating styles at the cost of one small UI state | Interview |
| Takeback | None in MVP | PRD is silent and "accepted move is durable" stays crystal clear | Interview |
| Creation form | Minimal: two prefilled label fields; `mode='digital'` implied (picker UI arrives with S-06) | No dead UI; FR-003 mode lives in data from day one | Interview |
| Web scope | **Fully in scope** — gameplay + journal + E2E on web | User pulled the FR-020 digital-play sliver forward (S-01 precedent); roadmap Parked note gets a write-back | Interview |
| Game creation offline | Not supported (retryable error); only moves are offline-safe | Avoids queued-INSERT complexity; §6.2 concerns accepted moves, not creation | Plan |
| `user_id` on INSERT | New migration: `default auth.uid()` | Honors contract §3.2 "mobile never passes user_id"; column currently has no default | Plan (gap found) |
| PGN `[Date]` tag | Derived from server `created_at` (ISO → dots) | Single time source, zero new datetime dependency | Plan |
| Presentation pattern | MVVM for Play/NewGame (no MVI) | Play state fits one UiState + intent methods; lessons.md default applies | Lessons |
| SAN/PGN correctness proof | Round-trip vs existing `parsePgn`: corpus + seeded-random playouts; green on Native | Writer and parser check each other against the engine; lessons.md Native rule | Plan |

## Scope

**In scope:** SAN writer + PGN serializer (`domain/chess/pgn`); parser acceptance of in-progress
PGN; `createGame`/`updatePgn` + `user_id` default migration + pgTAP + contract write-back;
`GameJournal` + `GameAutoSaver` (§6.2 ordering, retry, LWW reconciliation); interactive
`ChessBoardView` extension (selection/targets/tap/orientation) + `PromotionPicker`;
`PlayViewModel`/`PlayScreen`, `NewGameViewModel`/`NewGameScreen`; `NewGameKey`/`PlayKey` routes +
browser fragments; history routing by status/mode; three-surface E2E; roadmap/lessons write-backs.

**Out of scope:** game end & result (S-05), physical mode (S-06+), takeback, full offline-first
mirror (§3.4 end state), offline game creation, deletion, drag-and-drop, auto-flip, evals (S-03),
shareable web URLs.

## Architecture / Approach

Bottom-up along reuse boundaries, mirroring S-02: pure-domain PGN *writing* proven by
round-tripping through the existing parser → first write path (Supabase INSERT/UPDATE + local
journal + auto-saver owning the §6.2 ordering) → backwards-compatible board interactivity →
screens/VMs/navigation composing it all → E2E. During play, `PlayViewModel` maintains the same
`positions`/`sanMoves` parallel lists as `ReplayGame`; per accepted move: validate → SAN append →
serialize → **synchronous journal write** → UI update → async cloud sync.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. SAN & PGN writing | `sanForMove` + `writePgn`, round-trip-proven on 3 targets | SAN disambiguation edge cases; parser gaps on `[Result "*"]` PGN |
| 2. Write path & journal | `createGame`/`updatePgn`, `auth.uid()` migration, journal + auto-saver | Android journal durability (commit vs apply); wasmJs settings artifact |
| 3. Interactive board | Tap/highlights/orientation on `ChessBoardView` + promotion picker | Orientation off-by-one in tap mapping; replay regression |
| 4. Screens & navigation | Play + NewGame screens, routes, history routing, sync indicator | Heaviest phase — play state machine + back-stack/browser-fragment wiring |
| 5. E2E & write-backs | Crash-resume proof, 3-surface E2E, roadmap/lessons updates | Cross-surface sync surprises against hosted backend |

**Prerequisites:** F-01 + S-01 + S-02 surfaces merged (present); local Docker for
`supabase db reset`/`test db`; hosted-project access for `db push` (one manual gate).
**Estimated effort:** ~4–5 sessions across 5 phases; Phases 1 and 4 are the bulk.

## Open Risks & Assumptions

- multiplatform-settings wasmJs artifact assumed available; fallback is a hand-rolled 3-actual
  `expect/actual` journal (same interface, contained blast radius).
- `parsePgn` may need small fixes to accept in-progress documents (`*`, odd plies, empty
  movetext) — surfaced and fixed by Phase 1 round-trip tests, not discovered later in UI.
- Web play rides the binds-once navigation3-browser caveat (accepted in lessons.md) and adds two
  fragments; no COOP/COEP exposure (journal uses localStorage, not OPFS).
- LWW reconciliation (cloud wins on divergence) is contract §3.4's accepted MVP policy;
  single-device play makes divergence rare.

## Success Criteria (Summary)

- A player can create, play (castling, promotion), force-quit, and resume a digital game with
  zero lost moves — on Android, iOS, and web.
- Offline play keeps accepting moves; reconnect flushes the PGN to Supabase; the same game
  replays correctly on a different surface.
- All per-target suites + pgTAP green; the stored PGN round-trips through `parsePgn` and carries
  the §5.2 header set with `[Result "*"]`.
