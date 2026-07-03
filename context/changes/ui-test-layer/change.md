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
