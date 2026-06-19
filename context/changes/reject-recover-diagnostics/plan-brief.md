# Reject, Recover & Diagnostics (S-07) — Plan Brief

> Full plan: `context/changes/reject-recover-diagnostics/plan.md`
> Research: `context/changes/reject-recover-diagnostics/research.md`

## What & Why

Roadmap slice **S-07**: when a physical-board sequence is rejected (illegal / ambiguous / inconsistent), pause the game and let the player restore the previous legal position with a **live per-square reed diagnostics** view before retrying — FR-010 + FR-011, mobile-only, against the F-02 emulator. This is the trust-building slice for hobbyist reed hardware: rejection without a visible recovery path would dead-end games.

## Starting Point

S-06 (`physical-capture-emulated`, implemented) was deliberately built to grow into S-07. The MVI state machine, the `RejectionReason` enum + banner, the reject branch, `Position.toOccupancy()`, the absolute occupancy compare (`setupMismatch`), the `SetMode(DIAGNOSTIC)` / `RequestSnapshot` commands + the `Send` effect channel, and the emulator's ~10 Hz diagnostic stream **all already exist and are tested** — the app just never sends `SetMode` and never renders raw occupancy. S-07 is a **wiring + UI** slice, not a new-domain slice.

## Desired End State

A rejected confirmation pauses the game with a category-specific message; the banner offers "Show diagnostics", which (or a setup mismatch automatically) opens a live 8×8 reed grid highlighting the squares that differ from the expected position. The player restores the board guided by the grid; when occupancy exactly matches `positions.last()`, the gate clears, diagnostic mode exits, and they retry. No move is ever saved from a rejected or unrestored board.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Diagnostics grid content | Observed-vs-expected diff | Makes restoration trivial — player sees exactly which squares to fix; reuses `toOccupancy()`, still "raw". | Plan |
| Diagnostics entry | Reject = manual "Show diagnostics" tap; setup/reconnect mismatch = auto | Matches FR-010 ("manually restore") and contract §1.7 (auto on mismatch); no mode-flicker on trivial errors. | Plan |
| Restore gate | Hard — `confirm()` blocked until observed `==` `positions.last().toOccupancy()` | Enforces FR-010; impossible to accept a move from a wrong board; the same transition S-08 reuses. | Plan |
| Acceptance model | Unified `acceptanceBlocked = paused ∪ recovering` + a `recovering` flag | One gate, not scattered; clean reusable foundation for S-08 / FR-012. | Plan |
| "Inconsistent" | Distinct `RejectionReason.INCONSISTENT` via reducer absolute compare; interpreter stays delta-only | FR-010 lists three categories; no second footprint consumer → no `lessons.md` SYNC-extract threshold tripped. | Plan |
| Layout | Phone-first, single column (grid under board) | Physical is mobile-only/phone-first; least code, no breakpoints. | Plan |
| Guided restore / why-illegal | Out — raw diagnostics only | PRD non-goal; the reed grid is the explanation. | Research / PRD |
| Recovery test fixtures | Emulator `setOccupancy` + lift/place in tests | No production emulator change; deterministic inconsistent boards. | Research |

## Scope

**In scope:** `INCONSISTENT` rejection; pause→recover gate; live reed diagnostics grid (FR-011); hard restore-verification + retry; setup-mismatch auto-entry; emulator-driven E2E for both reject categories.

**Out of scope:** guided step-by-step restoration; "why illegal" classifier; interpreter changes; production emulator changes; `Resolution.Ambiguous` retirement; S-08 resume (FR-013) and FR-012 BLE reconnect loop (designed-for-reuse, not wired); two-pane adaptive layout; any web/physical exposure.

## Architecture / Approach

Grow the existing flat-state MVI monotonically — add fields (`latestOccupancy`, `recovering`, `manualDiagnostics`), the `INCONSISTENT` reason, the `acceptanceBlocked` / `diagnosticsVisible` derived predicates, and two intents (`ShowDiagnostics` / `HideDiagnostics`); the reducer stays pure (absolute compares via `toOccupancy()`), and diagnostics commands flow through the existing `Send(BoardCommand)` effect. A new lightweight `ReedDiagnosticsGrid(observed, expected)` composable renders the bitfield (h8 sign-bit safe), separate from the piece-rendering `ChessBoardView`.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Headless MVI core | Gate, `INCONSISTENT` fork, restore-verify, diagnostics-mode transitions — pure, unit-tested | Snapshot staleness in GAME mode + the lift/place-during-recovery rule (both in Critical Implementation Details) |
| 2. Diagnostics UI + wiring | `ReedDiagnosticsGrid`, banner CTA + `INCONSISTENT` copy, VM intents | h8 sign-bit in the grid; keeping the grid off web |
| 3. Emulator E2E + manual | Full reject→recover→retry test (both categories) + device checklist | Fabricating a deterministic inconsistent board via `setOccupancy` |

**Prerequisites:** S-06 implemented (done). Android device/emulator for the manual gate.
**Estimated effort:** ~3 sessions (one per phase); Phase 1 carries most of the logic.

## Open Risks & Assumptions

- **`INCONSISTENT` vs `ILLEGAL` fork depends on a fresh snapshot.** In GAME mode `latestOccupancy` is stale; the plan requests a snapshot on the reject path and relies on the diagnostic stream for restore-verify, degrading safely to `ILLEGAL` when no fresh snapshot contradicts the expected position. Deterministic on the emulator; real reed noise (S-09) is explicitly out of scope.
- **`SetMode` resets to GAME on reconnect** (§1.7) — diagnostics must be re-armed on `BoardConnected`, or the grid silently stops streaming.
- **Restoration lift/place must not be read as a move** — accumulation is short-circuited while `recovering`; the snapshot, not the deltas, drives verification.

## Success Criteria (Summary)

- A rejected sequence pauses the game with the correct category message and saves nothing.
- The live reed grid shows which squares differ from the expected position; restoring to an exact occupancy match re-enables acceptance.
- The full reject→recover→retry loop passes end-to-end on the emulator (all three targets) for both `ILLEGAL` and `INCONSISTENT`, and by hand on Android — with the web target never exposing diagnostics.
