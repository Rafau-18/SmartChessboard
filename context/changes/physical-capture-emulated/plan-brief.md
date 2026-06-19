# Physical-Mode Capture Against the Emulator (S-06) — Plan Brief

> Full plan: `context/changes/physical-capture-emulated/plan.md`

## What & Why

Add physical-mode play to the Smart Chessboard app: a player creates a `mode='physical'` game and
moves by lifting/placing pieces on the (emulated) reed board, confirming each move with the side
button. A new **sequence interpreter** resolves the lift/place stream into exactly one legal move —
captures, castling, and en passant read from the whole sequence, not a final snapshot — validated by
F-01 and auto-saved into the **same canonical PGN record** as digital play. This is roadmap **S-06**
(FR-005/006/008/009, US-02) and the project's hardest bet: that a magnet-only occupancy stream maps
unambiguously to one move. Proven end-to-end on the emulator, **no hardware**.

## Starting Point

All three prerequisites are done. F-02 ships the typed `BoardConnection` port + `BoardEvent`s
(LIFT/PLACE, button, snapshot) and an `EmulatedBoard` + scenario DSL (in `commonTest`, designed to
promote to `commonMain` for its first consumer). F-01 gives `legalMoves`/`validate`/`status` and an
immutable `Position`; `Move` is flag-free (capture/castle/ep derived from the position). S-04's
durable back half — `GameAutoSaver`, `GameJournal`, `writePgn`/`sanForMove`, the §6.2 acceptance
ordering — is **mode-agnostic and reused verbatim**. The model is already mode-aware; only
`createGame` and the New-game form hardcode digital.

## Desired End State

On Android/iOS, the player picks Physical in New game, lands on a physical-play screen that connects
to the board, verifies the opening position, and renders the canonical board (White-bottom + flip)
with lifted squares highlighted. The correct side's button resolves the sequence into a validated,
durably-saved move (captures/castling/en passant included); a promotion push raises the in-app picker
and blocks confirmation until a piece is chosen. Mate/stalemate auto-closes; manual end works; the
finished record replays in S-02. On web, no Physical option exists and synced physical games open in
Replay. The hardest bet is proven by an emulator-driven E2E test green on all three KMP targets.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Acceptance surface | Full stack + emulator-driven E2E; real-board interactive play = S-09 | Roadmap wants the picker/screen in S-06; PRD OQ-1 forbids a GUI simulator, so tests are the E2E proof | Plan |
| Rejection scope | Minimal (message + no save); diagnostics/recovery = S-07 | Keeps the roadmap S-06/S-07 boundary; guardrail "never save illegal" still met | Plan |
| Disconnect scope | Pause on `DISCONNECTED` only; reconcile = S-07, resume = S-08 | Minimal, honors the slice boundaries | Plan |
| Presentation pattern | **MVI** for `PhysicalPlayViewModel` (digital stays MVVM) | lessons.md names the live board / BLE flows as MVI candidates; pure reducer centralizes event×state transitions S-07/S-08 extend | Lessons |
| Web exclusion | `expect/actual supportsPhysicalBoard` gates picker + routing; board/VM DI Android/iOS only | Shared route graph needs an active gate; lessons.md forbids a web physical route | Lessons |
| Confirm button rule | Must match `sideToMove`; wrong button = no-op | Chess-clock semantics; robust to accidental other-side press | Plan |
| Start position | Light occupancy compare; mismatch → "set up the board" | Cheap guardrail (Position→bitmap); full diagnostics = S-07 | Plan |
| In-progress preview | Highlight lifted squares (small `ChessBoardView` add) | Tasteful echo without the S-07 reed grid | Plan |
| Promotion timing | Picker on detection of last-rank place; block confirm until picked | Literal contract §1.5 | Plan |
| Orientation | Reuse digital: White-bottom + flip | Consistency; already built | Plan |
| Resolution method | Match full-sequence occupancy signature against `legalMoves` footprints | Snapshot can't tell a capture from a quiet move; lift-on-destination is the discriminator | Research |
| DB migration | None | `games.mode` already accepts `'physical'` | Research |

## Scope

**In scope:** pure sequence interpreter + `Position.toOccupancy()`; `createGame(mode)` across
interface/impl/fake; `supportsPhysicalBoard` expect/actual; emulator promotion to `commonMain`; MVI
`PhysicalPlayViewModel` (reducer + effects + stream collector) reusing the S-04 back half;
`PhysicalPlayScreen` + New-game mode picker + `PhysicalPlayKey` route + History routing + per-platform
DI; display-only `ChessBoardView` highlight; emulator-driven E2E; roadmap/lessons/contract write-backs.

**Out of scope:** real BLE/hardware (S-09); diagnostics + guided recovery + reed grid (S-07); reconnect
reconcile (S-07) and resume-after-restart (S-08); any GUI board simulator (PRD OQ-1); DB migration;
web physical mode; changes to journal/auto-saver internals; eval/SAN/FEN-parser/takeback.

## Architecture / Approach

```
BoardConnection.events ─┐                      ┌─ resolvePhysicalMove(position, events)   [pure, domain/board]
 (LIFT/PLACE/Button/     ├── PhysicalMsg ─────▶ reduce(State, Msg)  [pure, MVI, no IO]
  Snapshot/connState)    │                      └─ Effect: CommitMove · FinishGame · Send · Connect
user intents (pick/end) ─┘                                  │
                              PhysicalPlayViewModel runs effects ─▶ GameAutoSaver (reused) ─▶ same canonical PGN row
```

Confirmation is two-step so the reducer never does IO: `ConfirmPressed` → effect
(`resolve→validate→sanForMove→writePgn→journal.acceptMove`) → `MoveCommitted | MoveRejected`. State
advances only on `MoveCommitted`, preserving the §6.2 "journal write is the acceptance gate" invariant.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Sequence interpreter (pure) | `resolvePhysicalMove` + `Resolution` + `toOccupancy`, 3-target corpus | The hardest bet — capture/castle/ep + j'adoube disambiguation from occupancy only |
| 2. Data & platform seams | `createGame(mode)`, capability flag, emulator → `commonMain` | Emulator promotion regressing F-02 tests |
| 3. MVI core (headless) | Reducer + effects + VM reusing S-04 back half | Heaviest logic; keeping the reducer IO-free while honoring §6.2 |
| 4. UI + navigation + DI | Physical screen, mode picker, route, per-platform DI | Web exclusion (active gate) — a physical row must not reach a board screen on web |
| 5. Emulator E2E + write-backs | Scripted full-game proof on 3 targets; roadmap/lessons updates | E2E asserting too loosely; hot-stream subscribe-before-connect |

**Prerequisites:** F-01 + F-02 + S-04 merged (present). No new backend, no migration, no new
libraries. Local Gradle + Android SDK for the three test tasks.
**Estimated effort:** ~4–5 sessions across 5 phases; Phases 1 and 3 are the bulk.

## Open Risks & Assumptions

- **Hardest bet (roadmap):** occupancy-only resolution of captures/castling/ep + noise tolerance.
  Mitigated by isolating the interpreter in Phase 1 with an exhaustive corpus before any UI.
- Binding the (test-born) `EmulatedBoard` as the production `BoardConnection` on Android/iOS is the
  F-02-intended promotion; S-09 swaps it for the real BLE adapter under the same port.
- Web exclusion relies on the capability flag being checked at every new physical touchpoint (picker,
  routing, DI) — a discipline, not a compile-time wall, since the route graph is shared in `commonMain`.
- No human interactive gate on-device for move-making in S-06 (the emulator has no in-app driver);
  the E2E test is the move-resolution proof. Real interactive play arrives with S-09.

## Success Criteria (Summary)

- A scripted physical game (quiet, capture ×2 orders, castling incl. interleaved, en passant,
  promotion) resolves move-by-move into a canonical PGN that round-trips through `parsePgn` — green on
  Android host, iOS sim, and web.
- Physical moves land in the same record/journal as digital play, with `[Mode "physical"]`; mate
  auto-closes and manual end records the result.
- On mobile the picker offers Physical and a physical game opens the board screen; on web no Physical
  option appears and a physical game opens in Replay.
