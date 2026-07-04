# Replay Seeded Games (S-02) — Plan Brief

> Full plan: `context/changes/replay-seeded-games/plan.md`

## What & Why

A signed-in player opens a saved game from their history and replays it with start / back /
forward / end controls on a new shared chessboard view (FR-016, US-03). The first increment runs
over seeded snapshot game records — no play mode yet — so the review loop (S-01 → S-02 → S-03
north star) keeps moving with zero hardware and zero gameplay code. The board view and PGN
handling born here are the reuse contracts for play mode (S-04) and evaluations (S-03).

## Starting Point

S-01 shipped auth + the history list (no row click yet, navigation is a session-state switch —
the nav library choice was deliberately deferred to S-02). F-01's rules engine is implemented and
perft-verified, but FEN/SAN/PGN were explicitly left to consuming slices. The `games` table (with
`pgn`, RLS) is deployed; `config.toml` already points at a not-yet-existing `seed.sql`. There is
no board UI and no piece assets anywhere.

## Desired End State

On Android, iOS, and web, tapping a seeded game in History opens a Replay screen: board at the
start position, SAN move list, transport controls; stepping/jumping updates the board; system
back returns to History. Seeded famous games replay to their known final positions.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Stored-record shape (roadmap unknown) | Parse PGN (SAN) via the F-01 engine into an in-memory position list | Keeps FR-014 / contract §5.4 intact ("FEN not stored per move"); S-04 games will replay identically to seeds | Plan (interview) |
| Seeding mechanism | `seed.sql` (local stack) + documented one-off cloud SQL script for real user UUIDs | Zero dev tooling in the app, project-as-code conventions; one small manual console gate like S-01's OAuth step | Plan (interview) |
| Navigation | Navigation 3 multiplatform (**stable** `navigation3-ui:1.1.1`; companion `lifecycle-viewmodel-navigation3` rides the `androidx-lifecycle` 2.11.0-beta01 pin) | User choice, verified 2026-06-12: covers Android/iOS/wasm since CMP 1.10; web browser-history (since 1.1.0) wired this slice — browser Back/Forward → nav stack | Plan (interview + verification) |
| Piece rendering | Bundled vector assets (Cburnett set → 12 Compose vector drawables + attribution) | Crisp and identical on all targets; unicode glyphs are font-roulette on wasm/iOS | Plan (interview) |
| Replay UX scope | Controls + SAN move list with current-move highlight and tap-to-jump; opens at start, white at bottom | Standard replay UX; the move list visually verifies the parser and is the natural surface for S-03 evals | Plan (interview) |
| Malformed PGN | Replay-up-to-error with a non-blocking truncation banner | Graceful degradation — most of the game stays visible; the error is surfaced, not masked | Plan (interview) |
| Presentation pattern | MVVM (no MVI justification needed) | Replay is a load-then-navigate-an-index screen; lessons.md default applies | Lessons |
| Single-game read | `GamesRepository.getGame(id): GameRecord` (summary + pgn) | Contract §3.2 "Get one game" already specifies it; list stays lightweight | Contract |

## Scope

**In scope:** PGN/SAN parser in `domain/chess/pgn` (resolved via `legalMoves`) + test corpus;
`GameRecord` + `getGame` in domain/data; `supabase/seed.sql` + cloud seed script; piece assets +
stateless `ChessBoardView`; Navigation 3 deps, typed routes, App.kt restructure, History row
click; `ReplayViewModel` + `ReplayScreen` (+ Koin registration, VM tests); cloud seeding gate,
three-surface E2E, lessons/roadmap write-backs.

**Out of scope:** play mode / board input (S-04), evals + FEN serialization (S-03), SAN
generation (S-04), schema changes or per-move FEN storage, Room/offline-first (S-04), board
flip/autoplay, PGN variations, a designed/shareable web URL scheme (browser Back/Forward IS
wired — see Phase 4 §9), game deletion.

## Architecture / Approach

Bottom-up along reuse boundaries: pure-domain PGN parsing (engine-backed, contract §5.4 intact) →
seeded data + single-game read → render-only board view → Nav3 + Replay screen composing all
three → cloud seeding + E2E. `ReplayGame` invariant: `positions.size == sanMoves.size + 1`;
replay navigation is just an index over derived positions.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. PGN replay domain | Parser + `ReplayGame` proven by corpus on 3 targets | SAN edge cases (disambiguation, promotion) — mitigated by engine-backed resolution |
| 2. Seeds & single-game read | `seed.sql`, cloud script, `getGame` | Seed/fixture drift — mitigated by fixture parity |
| 3. Board view & assets | 12 vector pieces + stateless `ChessBoardView` | Orientation/indexing off-by-one (a1=0 convention) |
| 4. Nav3 & replay screen | First push navigation + full replay UX | iOS/wasm NavKey serialization registration; lifecycle-group version alignment |
| 5. Cloud seeding & E2E | Real games in real history on 3 surfaces; write-backs | Manual UUID/console step; web back-button limitation |

**Prerequisites:** S-01 merged surfaces (auth + history), F-01 engine API available in
`domain/chess` (present), local Docker for `supabase db reset`/`test db`, hosted SQL editor
access for the one-off seeding gate.
**Estimated effort:** ~3–4 sessions across 5 phases; Phase 1 (parser + corpus) and Phase 4
(Nav3 + screen) are the bulk.

## Open Risks & Assumptions

- Navigation 3 multiplatform `navigation3-ui` is stable at 1.1.1; the companion
  `lifecycle-viewmodel-navigation3` rides the project's `androidx-lifecycle 2.11.0-beta01` pin to
  keep the lifecycle group aligned (no new beta surface — the project already runs that pin).
  `@Serializable` NavKeys + explicit polymorphic registration remain mandatory on iOS/wasm
  (no-reflection constraint, not a stability caveat).
- Web target: browser-history is wired this slice via Nav3 1.1.x's browser-navigation binding
  (`wasmJsMain`, route ↔ URL fragment) — browser Back/Forward maps to the nav stack. Only a
  designed/shareable URL scheme is left out. Verify-first: confirm the exact binding API in 1.1.1
  before relying on it (version intel is snapshot-level).
- Cburnett piece set is CC-BY-SA — attribution recorded in `docs/attributions.md`; fine for
  non-store MVP distribution.
- Cloud seeding duplicates rows if re-run — documented delete-and-rerun guidance, acceptable for
  a small-circle MVP.

## Success Criteria (Summary)

- A seeded famous game opened from History replays to its known final position with all four
  controls and tap-to-jump, on Android, iOS, and web.
- Per-target test suites (host JVM, iOS simulator, wasm) are green including the PGN corpus and
  ReplayViewModel tests; `supabase db reset` + pgTAP stay green with seeds.
- The Navigation 3 commitment and its caveats are recorded in `lessons.md`; roadmap S-02 status
  reflects delivery.
