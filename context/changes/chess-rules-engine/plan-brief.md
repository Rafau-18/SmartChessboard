# Chess Rules Engine (F-01) — Plan Brief

> Full plan: `context/changes/chess-rules-engine/plan.md`

## What & Why

Build a pure-logic chess rules engine in the mobile app's `domain/chess/` layer: full move
legality (check, pins, king safety, castling, en passant, promotion) plus checkmate/stalemate
detection. It is the foundation every later play channel validates through, and it backs the
PRD Guardrail *"the product never saves an illegal move"* (FR-005, FR-007). It runs identically
on Android, iOS, and WasmJS because it lives entirely in `commonMain` with zero platform deps.

## Starting Point

`domain/auth/` and `domain/games/` exist; `domain/chess/` is greenfield. `GameSummary` models
high-level game *metadata* only — nothing models board state today. Test discipline (kotlin-test
in `commonTest`, no mocking lib), DI (Koin), and pattern (MVVM) are all settled from S-01, and
the engine needs none of the latter two — it is stateless pure functions consumers call directly.

## Desired End State

A `domain/chess` package exposes an immutable `Position`, a `Move`, `legalMoves`, a `validate`
returning a sealed `Legal/Illegal(reason)`, and a `status(position)` for terminal state — all
proven by a perft suite (start position + Kiwipete) plus curated edge cases, green on all three
KMP targets. A downstream slice (S-04) can enumerate legal moves, attempt a move, and classify a
position using only the public API.

## Key Decisions Made

| Decision | Choice | Why | Source |
| --- | --- | --- | --- |
| Scope boundary | Legality core only | SAN/FEN-serialization/PGN belong to consuming slices (S-04/S-02/S-03); engine stays focused | Plan |
| Position state | FEN-complete (incl. castling, en passant, both counters) | Exact FEN for eval cache key (§4.4) + future draw rules, with no Position rework later | Plan |
| Square indexing | a1 = 0, `index = file + 8*rank` (contract §1.3) | No translation layer at the physical/BLE boundary (S-06); one convention project-wide | Plan |
| Public API | Enumeration + validation | `legalMoves` is the primitive; mate/stalemate and physical sequence resolution derive from it | Plan |
| Move result | Sealed `Legal/Illegal(reason)` | Explicit error paths, idiomatic KMP, reasons feed S-07 diagnostics | Plan |
| Mutability | Immutable `Position` | Replay = list of positions; deterministic tests; no search loop to optimize | Plan |
| Terminal state | Pure `status(position)` | Works on loaded positions too, not only post-move; directly realizes FR-007 | Plan |
| Promotion | Explicit `promoteTo`; missing = incomplete | Matches FR-006 / §1.5 (player must choose); keeps PGN faithful | Plan |
| Board representation | 8×8 mailbox (not bitboards) | No engine search; clarity over speed; 500 ms NFR met trivially | Plan |
| Test rigor | Perft + curated edges | Perft catches whole classes of generation bugs; edges document per-rule intent | Plan |

## Scope

**In scope:** immutable `Position` (FEN-complete), `Move` with promotion, pseudo-legal + legal
move generation, `isSquareAttacked`/`isInCheck`, `applyMove` state transitions, sealed
`MoveOutcome`, `status(position)` for mate/stalemate, perft + edge-case test corpus on all three
targets.

**Out of scope:** SAN generation, FEN serialization/parsing, PGN round-trip, draw-by-rule
detection (50-move/threefold/insufficient material), engine search/evaluation, DI/Koin wiring,
ViewModels, UI, bitboard optimization.

## Architecture / Approach

Classic readable pipeline (nothing is perf-bound — no search): immutable `Position` value model →
pseudo-legal generation per piece → king-safety filter (one filter handles pins + check evasion +
castling-through-check uniformly) → `applyMove` producing the next `Position` → `status` derived
from `legalMoves` + `isInCheck`. `isSquareAttacked` is the shared spine. Correctness proven
statistically by perft and per-rule by curated tests.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Model & conventions | Immutable types + a1=0 square convention (the consumable contract) | Square-indexing off-by-one freezing the wrong convention |
| 2. Pseudo-legal gen + attacks | Per-piece moves + `isSquareAttacked`/`isInCheck` | Pawn attack-vs-move asymmetry; missed special-move geometry |
| 3. Legality + applyMove | King-safety filter, castling/en-passant rules, state transitions, public API | En-passant lifecycle / castling-rights revocation bugs |
| 4. Terminal state | `status(position)` mate/stalemate/check (FR-007) | Misclassifying stalemate as checkmate or vice versa |
| 5. Perft + edge corpus | Perft (startpos, Kiwipete) + curated edges, green on 3 targets | Asserting wrong reference numbers; slow perft on host/CI |

**Prerequisites:** none — F-01 has no upstream dependencies (parallel to the S-01→S-02→S-03
review loop). No new libraries, no schema, no DI.
**Estimated effort:** ~4–5 sessions across 5 phases; phases 1–2 are quick, phase 3 is the bulk,
phase 5 is verification-heavy.

## Open Risks & Assumptions

- Perft on the host JVM target may be slow at depth 5+; capping depth (documented in-comment) is
  the accepted mitigation rather than optimizing the board representation.
- Assumes the §1.3 square convention stays stable; it is BLE-derived but treated as binding.
- Engine is consumed directly (no interface/DI); if a later slice needs to swap implementations,
  an interface can be introduced then — not pre-built now.

## Success Criteria (Summary)

- All three per-target test tasks (`testAndroidHostTest`, `iosSimulatorArm64Test`, `wasmJsTest`)
  pass with the perft + edge-case suite.
- Perft node counts match published reference values for the start position and Kiwipete.
- A downstream consumer can enumerate legal moves, attempt a move (getting a structured
  legal/illegal answer), and detect checkmate/stalemate — using only the public API.
