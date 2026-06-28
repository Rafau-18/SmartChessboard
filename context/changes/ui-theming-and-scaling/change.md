---
change_id: ui-theming-and-scaling
title: UI refresh — dark/light theming (M3 Expressive), scalable board, animations
status: implementing
created: 2026-06-28
updated: 2026-06-28
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
