# Physical Resume After Restart (S-08 / FR-013) — Plan Brief

> Full plan: `context/changes/physical-resume-after-restart/plan.md`
> Research: `context/changes/physical-resume-after-restart/research.md`

## What & Why

Let a player restart the app mid physical-mode game on the same device and continue with no accepted move lost. The expected position already rebuilds from the durable PGN on screen entry — the missing piece is the **board-confirmation gate**: on resume, block move acceptance until the physical board is verified against the expected position. This is a wiring + gating slice, not a from-scratch build; nearly every primitive already ships and is test-proven by S-06/S-07.

## Starting Point

Opening an in-progress physical game already works end to end *except the gate*. Durability is guaranteed (synchronous journal before UI advance), the expected position rebuilds from PGN, the `Position.toOccupancy()` board-match compare + per-square diagnostics grid + `acceptanceBlocked` gate + restore-on-match transition all exist. But the `Loaded` arm renders the position and **immediately allows play** — a resume mismatch only opens the grid (`setupMismatch`), it never blocks acceptance. FR-013's "confirm the board before re-enabling" is enforced today only after a *rejected move*, never on *resume*.

## Desired End State

Cold-starting an in-progress physical game (tapped from History) lands on `PhysicalPlay` with the position rendered and acceptance **blocked** until the board is confirmed: board matches → gate clears automatically, normal play resumes (no extra tap); board mismatches → diagnostics open, acceptance stays blocked, and restoring the board to the expected position clears the gate — reusing the exact S-07 restore loop. No accepted move is ever lost.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Offline scope (G2) | Minimal — cloud-gated detect | Durability invariant holds regardless; the slice's real core is the gate, not new offline data plumbing | Plan |
| Entry UX | History tap is the offer (+ light "Resume" affordance) | Matches §6.3 "offers to resume"; reuses existing routing, no auto-nav fighting Nav3 saved-state | Plan |
| Confirm-on-match friction (G3) | Auto-resume on match; prompt only on mismatch | Matches §6.3 "Match → resume"; the board snapshot *is* the confirmation, lowest friction | Plan |
| Gate representation (G1) | Distinct `awaitingResumeConfirm` flag, ORed into `acceptanceBlocked` | One gate + shared clear-transition (FR-012-ready), while UI distinguishes resume from reject (G3) | Plan |
| FR-012 reuse | Build confirm check as a shared transition, don't inline | §1.7 and §6.3 specify FR-012/FR-013 reconcile identically; S-09 wires it into `BoardConnected` | Research |
| Same-device only | Boundary, no cross-device work | PRD OQ-4 puts cross-device handoff out of MVP | Research |

## Scope

**In scope:** resume gate (`awaitingResumeConfirm` + `acceptanceBlocked`); resume entry in the `Loaded` arm; shared board-confirm transition (match → auto-resume + `SetMode(GAME)`; mismatch → diagnostics + stay blocked → restore loop); reducer unit tests; fault-injected resume E2E (match / mismatch→restore / promotion-pending-at-kill) on Android + iOS; light History "Resume" affordance; foundation write-backs.

**Out of scope:** offline cold-start discovery / local index; cross-device handoff; BLE `BoardConnected` wiring + auto-reconnect transport (S-09/FR-012); auto-navigation / startup prompt; any web work; persistence-schema changes.

## Architecture / Approach

One new boolean (`awaitingResumeConfirm`) on the `Playing` state, ORed into `acceptanceBlocked`. The `Loaded` arm sets it for an in-progress physical game and ensures a board snapshot is requested; a **shared transition** keyed on `incomingOccupancy == position.toOccupancy()` clears the active gate on match (resume → `SetMode(GAME)`) or opens diagnostics and stays blocked on mismatch, re-checking each fresh occupancy until restore. The reconcile is the same machinery FR-012 will fire on reconnect — built shared, not inlined. Everything else (durability, expected-position rebuild, diagnostics grid, board re-snapshot on connect) is reused unchanged.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Resume gating core | `awaitingResumeConfirm` + gate + shared board-confirm transition in the pure MVI core, reducer unit tests | Snapshot-vs-`Loaded` ordering race (snapshot may precede the flag) |
| 2. E2E, edges & write-backs | Fault-injected resume E2E (Android+iOS), History "Resume" affordance, foundation write-backs | Emulator resets to start-position → E2E must inject occupancy, not rely on default |

**Prerequisites:** S-07 merged (the `acceptanceBlocked` gate, `Position.toOccupancy`, `ReedDiagnosticsGrid`, restore transition) — all present on `main`.
**Estimated effort:** ~1–2 sessions across 2 phases; mostly wiring + tests, no new architecture or persistence.

## Open Risks & Assumptions

- **Snapshot/`Loaded` ordering**: the VM subscribes before loading, so a snapshot can arrive before the flag is set — the `Loaded` arm must evaluate against already-observed occupancy or request one explicitly (covered in the plan's Critical Implementation Details).
- **Emulator masks mismatch**: a fresh emulator reports start-position occupancy, so a real mid-game resume always mismatches on the emulator default — the E2E injects occupancy deliberately.
- **Assumption**: reusing the `recovering` restore loop's "clear on exact match" mechanism for the resume gate behaves identically; verified by the new reducer tests + E2E.

## Success Criteria (Summary)

- Restarting mid physical game and resuming with a matching board auto-continues play with zero lost moves.
- A mismatched board on resume blocks acceptance and opens diagnostics; restoring the board resumes play — same UX as recovering from a rejected move.
- Resume E2E green on Android host and iOS simulator; manual on-device pass confirms the flow.
