# Physical-record Integrity Tests — Plan Brief

> Full plan: `context/changes/testing-record-integrity/plan.md`
> Research: `context/changes/testing-record-integrity/research.md`

## What & Why

Rollout Phase 2 of the test plan: prove no physical-board game is ever *silently*
mis-recorded (risk #1) and no crash / kill / offline window loses an accepted move or the
end-of-game result (risk #2). The research found the exposure is not missing scenarios but
**five code-verified defects** plus an oracle gap. This change fixes the defects and adds the
missing proofs, test-first (red → green).

## Starting Point

The §6.2 durability spine is implemented and well-tested (~40 tests). But at commit `d7cee0c`:
a zero-row cloud UPDATE returns 2xx and destroys the only copy (W5); `reconcile` awaits the
flush inline so an offline finished game hangs the load screen (W6); the physical finish path
shows "finished" **before** it saves, unguarded, and nothing re-closes it on next load (W2);
digital tap-path writes are unguarded and crash (W3); and the PGN round-trip oracle shares the
chess engine on both sides, so an engine bug is invisible to it.

## Desired End State

Each of W5/W6/W2/W3 has a test that was red on the old code and is green after a minimal fix.
A corpus of physical games (incl. one long published "kitchen-sink" game) is asserted against
**hand-written** PGN and a per-move sensed-vs-derived occupancy invariant; `footprintOf` is
mechanically checked against `applyMove`; adversarial board streams provably surface a visible
rejection, never a divergent accepted move. All three target suites green in CI.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Zero-row on flush | Keep journal, stop retrying (no clear/mark-synced) | A false success must never destroy the only copy | Plan (P1) |
| Offline load hang | Split reconcile from sync — show game now, flush in background | Network leaves the critical open path, matching the move-accept pattern | Plan (P2) |
| Finish write failure | "Finished" only after durable write; banner + Retry; self-heal at load | Manual results aren't re-derivable from the position, so nothing shown may vanish | Plan (P3) |
| Auto-flush sweep | Concede the floor now, mechanism to roadmap | Keep a stabilisation phase test-and-fix, not a feature build | Plan (P4) |
| Android commit()==false | Accept as known risk, no code | A read-back verify reads cache and lies under the exact condition it targets | Plan (P5) |
| Oracle fixtures | Existing scenarios + one long published game | Strongest single anti-engine-drift proof at moderate cost | Plan (P6) |
| Hostile-input boundary | Game-logic level here; BLE transport → test-plan Phase 3 | Clean risk boundaries (#1/#2 vs #3), no duplicated work | Plan (P7) |
| Oracle strategy | Hand-written PGN + occupancy invariant + geometry property | Engine-independent signal without an external library | Research |
| Scope | Tests **and** fixes (red → green) | Each defect exposed on current code, then minimally fixed | Research |

## Scope

**In scope:** zero-row guard (repo + autosaver), reconcile/sync split, guarded digital writes,
physical finish feedback loop + Retry + load self-heal, independent-oracle fixtures + occupancy
invariant + geometry property + kitchen-sink game, adversarial game-logic streams, kill-window
+ floor tests, and the test-plan / roadmap / lessons / cookbook updates.

**Out of scope:** auto-flush sweep, deleted-row re-create, orphan cleanup, Android read-back
verify, BLE transport / garbage-byte tests, external engine oracle, real process-kill /
real-radio / multi-device.

## Architecture / Approach

Minimal, pattern-consistent fixes: the physical finish rework mirrors the existing
`CommitMove`→`MoveCommitted` feedback loop (state advances only on durable success); the
zero-row signal is a `GameRowMissingException` caught **above** the generic retry arm in
`GameAutoSaver.sync`; the reconcile/sync split reuses the fire-and-forget
`viewModelScope.launch { sync }` already run after every move. Oracles are engine-independent:
sensed-vs-derived occupancy after each accept, hand-written PGN, and a `footprintOf`↔`applyMove`
occupancy-diff property (the SYNC-comment coupling made mechanical).

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Cloud-handoff durability (W5+W6+W3) | Zero-row guard, reconcile/sync split, guarded digital writes | Reconcile split must keep the finished-flush backstop |
| 2. Physical finish integrity (W2) | §6.2 restored on finish, banner+Retry, load self-heal | Largest reducer/VM rework; existing E2E assumes instant freeze |
| 3. Record-integrity oracle (G1+G2) | Reuse `PgnFixtures` (Opera game) as kitchen-sink + occupancy invariant + geometry property + hand PGN for promotion/en passant | Famous games lack promotion/en passant — those two shapes stay on hand-written short scenarios |
| 4. Adversarial streams (G3+G4) | Phantom/bit-flip/gap scenarios → visible rejection or no-op | Virtual-time discipline around the armed diagnostic stream |
| 5. Kill-window + floor + docs (W7/G9+G10) | Partial-write + floor tests, test-plan/roadmap/lessons/cookbook | Keep it documentation, not a transactional-journal expansion |

**Prerequisites:** worktree with `local.properties` (Android SDK) + iOS TEAM_ID for the native
suite; run the fleet from a full checkout (a fresh worktree crashes on startup / lacks TEAM_ID).
**Estimated effort:** ~5 sessions, one per phase; Phase 2 is the heaviest, Phases 3–4 the most
test-writing.

## Open Risks & Assumptions

- W11 (Android `commit()==false`) accepted as a theoretical, un-observable risk (no code).
- Auto-flush floor conceded (P4): a game finished offline and never re-opened does not reach the
  cloud until re-opened; documented by a test + test-plan annotation, closure on the roadmap.
- Zero-row leaves a harmless local orphan (P1); cleanup deferred to the roadmap sweep item.
- `select`-on-UPDATE returns exactly the RLS-matched rows — verified against the library API, to
  be confirmed on the live-Supabase manual gate in Phase 1.

## Success Criteria (Summary)

- Every defect (W5/W6/W2/W3) has a test red on the old code, green after its minimal fix.
- Recorded PGN + per-move occupancy match hand-written / published expectations across the
  scenario corpus and the kitchen-sink game; adversarial streams never produce a divergent accept.
- All three target suites pass in CI; the risk-#2 floor and its planned closure are documented
  honestly.
