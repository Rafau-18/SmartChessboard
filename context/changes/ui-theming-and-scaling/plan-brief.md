# UI refresh — theming, dark/light, scalable board, animations — Plan Brief

> Full plan: `context/changes/ui-theming-and-scaling/plan.md`

## What & Why

The KMP app currently ships a bare `MaterialTheme {}` with hardcoded colors, a fixed-size board,
and zero animations. This change makes it feel finished: a Material 3 Expressive theme with a
user-toggleable day/night mode (the blue-accent **Slate Steel** palette), a scalable chessboard
(auto-fit + corner drag-resize, lichess-style), and motion (eval bar, highlights, transitions,
piece slide). Driven directly by the user, not a roadmap slice.

## Starting Point

Root is `MaterialTheme {}` with no dark/light handling; 10 colors hardcoded across board/eval/
diagnostics. The board is already size-agnostic (`aspectRatio(1f)`, weight grid) but capped at
480 dp; only Replay is two-pane (≥840 dp). `Settings` (per-platform) + Koin DI are wired and reused
for UI prefs. The eval bar snaps to center (0.0) while a new ply's eval loads.

## Desired End State

App follows the OS theme on first launch; a History top-bar control cycles System/Light/Dark and
persists. Both modes readable (wood board constant, chrome themed). Wide screens get a board that
fills the viewport with a persisted corner-drag size; phones stay full-width. The eval bar holds the
last shown eval while loading, shows a loading affordance + a fixed in-bar numeric label, and
animates. Moves animate (piece slide), with highlight/arrow/transition/dialog motion throughout.

## Key Decisions Made

| Decision | Choice | Why | Source |
| --- | --- | --- | --- |
| Theming base | Material 3 Expressive (alpha) | Repo already pins `material3 1.11.0-alpha07` — opt-in only, no version bump | Plan |
| Color palette | Slate Steel (steel-blue, lichess-like) | User picked from 4 previewed blue-accent options | Plan |
| Dark mode in board | Wood squares constant; theme only chrome | Least work, board always readable | Plan |
| Default mode / dynamic color | Follow system; no Material You | Consistent palette across Android/iOS/web | Plan |
| Theme toggle home | History top-bar action | No new route/screen; minimal surface | Plan |
| Board scaling | Auto-fit + corner drag handle (wide only), persisted | Lichess-like; board already size-agnostic | Plan |
| Animation scope | Everything incl. piece slide | User wants the full motion story | Plan |
| Eval bar | Hold last eval while loading + loading affordance + fixed in-bar numeric; 0.0 = mid-board | User-specified behaviour fix, not just animation | Plan |
| Testing | Unit (pure logic) + manual 3-target gate | Matches existing project discipline | Plan |

## Scope

**In scope:** Expressive theme + Slate Steel light/dark; `ChessColors` tokens (migrate 10 hardcodes);
persisted theme mode + History control; scalable board (auto-fit + drag handle, persisted) across
Replay/Play/PhysicalPlay; eval-bar hold-last + numeric + fill animation; highlight/arrow/transition/
dialog motion; piece-slide animation.

**Out of scope:** dynamic color/Material You; dark-mode board repaint; board-theme picker; new
nav routes/Settings screen; screenshot/Compose-UI tests; any game-logic/persistence/contract change;
resize handle on phones.

## Architecture / Approach

Phase 1 establishes the theme + tokens (everything else styles against them). Phase 2 makes the
board scalable (theme-independent). Phase 3 fixes eval-bar behaviour. Phase 4 adds low-risk motion.
Phase 5 adds the high-risk piece-slide last (overlay layer over the weight grid), isolated so the
slice still ships if it slips. New code follows MVVM + Koin (`lessons.md`); pure logic must pass on
the iOS Native target too.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Theme foundation | Expressive + Slate Steel dark/light, tokens, toggle, persistence | Expressive is alpha (opt-in churn) |
| 2. Scalable board | Auto-fit + persisted corner drag-resize (wide) | Drag gesture + clamp + height-bound sizing |
| 3. Eval bar | Hold-last + loading affordance + fixed numeric + fill anim | Held-state edge cases (game switch, all-dark mate) |
| 4. Motion (cheap) | Highlight/arrow fades, screen + dialog transitions | Nav3 transition API on iOS/web |
| 5. Piece slide | from→to slide over the grid (capture/castle/EP/promo) | Overlay over weight grid; special cases |

**Prerequisites:** none beyond the current `main` (worktree `worktree-ui-theming-and-scaling`).
**Estimated effort:** ~5–9 sessions across 5 phases — Phases 1–4 are the bulk of the polish; Phase 5
(piece slide) is the largest single piece.

## Open Risks & Assumptions

- Material 3 Expressive APIs are experimental; `MaterialExpressiveTheme`/motion-scheme behaviour may shift in later alphas. Mitigation: per-file `@OptIn`, no version bump.
- Nav3 screen-transition support parity on iOS/web — verify on the pinned CMP version during Phase 4.
- Piece-slide over a `weight` grid needs an absolute overlay; multi-piece moves (castling) and promotion glyph-swap are the fiddly cases — fall back to instant render when the delta isn't one resolvable move.
- Shared-element transitions are NOT relied upon (not guaranteed on non-Android CMP yet).

## Success Criteria (Summary)

- Day/night toggle works and persists on Android, iOS, and web; both modes readable, board constant.
- Board scales/resizes on wide screens (persisted) and stays clean on phones.
- Eval bar holds the prior eval while loading, animates, and shows a fixed numeric label; 0.0 mid-board.
- Moves and UI state changes animate (piece slide, highlights, transitions, dialogs) with no regressions.
