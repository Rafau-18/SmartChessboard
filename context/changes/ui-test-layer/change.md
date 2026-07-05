---
change_id: ui-test-layer
title: UI test layer (screenshot goldens + compose.uiTest smoke + test CI)
status: implementing
created: 2026-07-04
updated: 2026-07-04
archived_at: null
---

## Notes

Born from an extended debate (2026-07-04) about what multiplatform testing is
possible/worth it for a KMP + Compose Multiplatform app. Scope decided
interactively: Roborazzi JVM golden tests (full matrix × light/dark) for
ChessBoardView, the adaptive scaffolds, and panel components; compose.uiTest v2
smoke flows (digital happy path, History→Replay+delete) through the real App()
root with Koin overrides; GitHub Actions test CI with CI-recorded goldens.
GitHub migration (Phase 0) is a hard prerequisite the user declared
"absolutely necessary" — the plan assumes GitHub + `gh` CLI so the agent can
verify CI runs autonomously. Distribution CI stays in the separate
`github-ci-and-distribution` change.

All 5 phases automated-verified and merged to `main` (`bc907a1`, 2026-07-04).
`status` stays `implementing`, not `implemented`, because four manual
confirmations remain open (3.6, 4.4, 5.6, 5.7 — see `manual-verification.md` /
`manual-verification-pl.md`). User explicitly chose to merge ahead of that
manual pass due to time constraints; flip to `implemented` (and run the
epilogue commit) once those are confirmed.
