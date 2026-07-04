---
change_id: ui-theming-and-scaling
title: UI refresh — dark/light theming (M3 Expressive), scalable board, animations
status: impl_reviewed
created: 2026-06-28
updated: 2026-07-04
archived_at: null
---

## Notes

User-driven UI improvement initiative (not a roadmap slice). Goal: turn the
default-`MaterialTheme` skeleton into a styled, readable, polished UI across all
three KMP targets (Android / iOS / WasmJS).

Direction decided with the user (2026-06-28):

1. **Theming base — Material 3 Expressive (alpha).** The repo is already pinned to
   `material3 = 1.11.0-alpha07` (the JetBrains artifact carrying Expressive APIs),
   so adopting `MaterialExpressiveTheme` is incremental, not a version jump.
   Centralize the 10 hardcoded `Color(0x…)` values (board / eval / diagnostics)
   into a theme-aware `ChessColors` (CompositionLocal) with **light AND dark
   variants** — "both modes readable" is the core requirement. Dark/light driven
   by `isSystemInDarkTheme()` + an explicit user toggle persisted via
   `multiplatform-settings` (already used for the journal). `dynamicColorScheme`
   Android-only.

2. **Scalable board — auto-fit + manual resize handle.** Board is already
   size-agnostic (`Modifier.aspectRatio(1f)`); no board rewrite. Auto-fit by
   `min(paneWidth, availableHeight)` and lift the `BOARD_MAX_WIDTH = 480.dp` cap on
   wide screens (lichess-like). Plus a draggable resize handle / slider with size
   persisted in settings.

3. **Animations (where they pay off):** eval-bar fill (animateFloatAsState),
   selection/target/lift highlights, best-move arrow, Nav3 screen transitions,
   dialog enter/exit — and (larger, later) piece-slide from→to via an absolutely
   positioned overlay over the weight-based grid.

Current-state map and 2026 best-practices research are captured in this session;
plan.md will phase the work. Worktree: `worktree-ui-theming-and-scaling`.

Key files: `presentation/board/ChessBoardView.kt`, `presentation/replay/EvalComponents.kt`,
`presentation/replay/ReplayScreen.kt`, `presentation/board/ReedDiagnosticsGrid.kt`, `App.kt`.

- 2026-07-04: **impl-review rescued** from the `pedantic-taussig-cb0635` agent worktree (it was uncommitted there; the worktree was removed during the branch/worktree cleanup). Full-plan review → `reviews/impl-review.md`, verdict **NEEDS ATTENTION** (0 critical, 1 warning, 4 observations; all PENDING). **F1** (warning): `readBoardSize()` is not a total read — `settings.getFloat` throws on wasm for a non-numeric stored `ui.boardSize`, crashing at Koin startup; reachability-gated (the app's own writer always stores a float). Fix = `getFloatOrNull` + a `wasmJsTest`. F2–F5 are observations (a multi-ply animation edge case + three test-coverage gaps). Status `implemented` → `impl_reviewed`. **Not archived** — ui-theming is outside the F-01..S-09 close-out; triage F1 before its own `/10x-archive`.
