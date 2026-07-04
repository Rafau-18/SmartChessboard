---
change_id: testing-record-integrity
title: "Physical-record integrity tests: no silent divergence, no lost accepted moves"
status: planned
created: 2026-07-04
updated: 2026-07-04
archived_at: null
---

## Notes

Rollout Phase 2 of context/foundation/test-plan.md: "Physical-record integrity".

Risks covered: #1 (a physical-board game is silently recorded with moves that differ from what was actually played — irreversible), #2 (moves the player saw accepted are lost after crash/kill/offline, incl. the end-of-game result never reaching the cloud).

Test types planned: adversarial scenario unit tests, fault-injected integration, PGN round-trip invariants.

Risk response intent:
- #1: prove that for every accepted sequence (captures, castling, en passant, promotion, reconnect-mid-game) the persisted PGN replays to exactly the physical position — divergence must surface as a visible rejection, never silent acceptance. Challenge "emulator-green implies board-green" and "accepted move equals persisted move". Avoid the implementation-oracle anti-pattern (expected PGN computed by the same interpreter under test).
- #2: prove that kill/crash/offline at any point after "move accepted" leaves the move present after restart, and a finished game eventually lands in the cloud without further user activity. Challenge "final status 200 means saved" and "the next move re-triggers sync". Avoid testing only graceful shutdown.
