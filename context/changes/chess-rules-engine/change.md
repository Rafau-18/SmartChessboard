---
change_id: chess-rules-engine
title: Chess rules engine — full legality validation + mate/stalemate detection
status: implementing
created: 2026-06-11
updated: 2026-06-11
archived_at: null
---

## Notes

Roadmap item **F-01** (foundation), `main_goal: speed`. A shared, test-verified chess
legality capability in `SmartChessboard/shared`: full move validation (check, pinned
pieces, king safety, castling, en passant, promotion) plus checkmate/stalemate
detection — ready for any board input channel (digital S-04, physical S-06) to consume.

- PRD refs: FR-005, FR-007, Guardrails ("never save an illegal move").
- Prerequisites: none. Runs parallel to the review loop (S-01 → S-02 → S-03) so it
  never delays the north star.
- Must be verified by its own test corpus before any UI consumes it.
