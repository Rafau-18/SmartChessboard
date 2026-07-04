---
change_id: reject-recover-diagnostics
title: Reject recover diagnostics
status: implemented
created: 2026-06-19
updated: 2026-07-04
archived_at: null
---

## Notes

<!-- Free-form notes for this change: links, ad-hoc context, decisions that don't belong in research/frame/plan. -->

- Roadmap item: **S-07** (`context/foundation/roadmap.md`). PRD refs FR-010, FR-011, US-02. Prereq: S-06 (`physical-capture-emulated`, implemented).
- 2026-06-19: codebase research complete → `research.md` (scope: S-07 + forward notes for S-08/S-09; emphasis: plan-ready dossier).
- 2026-06-19: plan complete → `plan.md` + `plan-brief.md` (MEDIUM, 3 phases: headless MVI core → diagnostics UI/wiring → emulator E2E + manual). 6 design decisions settled (grid = observed-vs-expected diff; entry = reject-tap + mismatch-auto; hard restore gate; unified `acceptanceBlocked`; distinct `INCONSISTENT` via reducer absolute compare, interpreter stays delta-only; phone-first layout). Next: `/10x-implement reject-recover-diagnostics phase 1`.
- Stray duplicate folder `context/changes/eject-recover-diagnostics/` (typo) should be removed; this folder is canonical.
- 2026-07-04: **manual gate ACCEPTED** (user ran the on-device reject→recover→retry walkthrough for `ILLEGAL` + `INCONSISTENT`); the code-read/E2E-backed rows were already discharged in-file. `/10x-impl-review` intentionally **skipped** per user decision. Status `implementing` → `implemented`. Closing via `/10x-archive`.
