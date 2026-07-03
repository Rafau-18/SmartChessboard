# Mobile Landscape Layout — Plan Brief

> Full plan: `context/changes/mobile-landscape-layout/plan.md`
> Research: `context/changes/mobile-landscape-layout/research.md`

## What & Why

One coherent adaptive layout system for phone landscape, tablets, and wide web windows, across all screens. Today the app is width-only adaptive (one hand-rolled 840 dp breakpoint, no height axis), so a landscape phone gets either a rotated portrait column or tablet chrome at phone height — and rotation is fully unlocked, so two functional bugs (NewGame IME/scroll, Connection reachability) plus an unusable PhysicalPlay recovery flow are live today. User-initiated UX change (no PRD FR); redeems the adaptive follow-up deferred by `replay-seeded-games`.

## Starting Point

Rotation survival is already solved (Nav3 stack, ViewModels, BLE singleton all survive — pure layout work remains). The wide-screen half shipped in `ui-theming-and-scaling` (two-pane Replay, resizable board, 840 dp seam, `effectiveMoveListMode` pattern); its 3-target manual gate is fully confirmed. No adaptive library is on the classpath; Maven-verified artifacts exist for all our targets.

## Desired End State

On a landscape phone every screen swaps the TopAppBar for a left action rail (Back + actions, no title), and board screens render side-pane: a height-filling board (~280–300 dp vs ~220 today) next to a scrollable 340–480 dp control panel — recovery finally shows board and diagnostics grid side by side. Tablets/desktop/wide web keep today's wide behavior, now driven by official window size classes. Banners never displace the board (no-jump invariant). The functional bugs are fixed for every window shape.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Window classification | Official `WindowSizeClass` (window-core; add via compile-verified ladder: adaptive → window-core → enum artifact → hand-rolled) | Official width **and height** breakpoints (840 / 480) replace bespoke numerology and kill the duplicated 840 | Research → Plan |
| Board-screen layout | One slot-based `BoardScreenScaffold`; SidePane iff height-compact OR width-expanded | Generalizes the proven `WideReplay` pattern into a single code path for all three targets | Plan |
| Chrome at compact height | Left rail replaces TopAppBar on **all** screens (Back + actions, no title) | Height is the scarcest resource in landscape; width is abundant; one chrome rule app-wide | Plan (user) |
| Banners | Top of the side panel, in **fixed reserved slots** — board never moves when banners appear/disappear | No-jump invariant; board size stays independent of transient messages | Plan (user) |
| PhysicalPlay recovery | Diagnostics grid beside the live board in SidePane + viewport-height bound | Makes FR-010/011 comparison usable; deliberately revisits the single-column scoping | Plan |
| Resize handle / `BoardSize` | Active only on true wide (width-expanded AND not height-compact) | Restores the documented "phones always auto-fit" contract; fixes the accidental flagship-landscape behavior | Plan |
| Move-list default | Container-driven: INLINE in portrait column, TABLE in side panel; override still wins | Presentation matches container shape, uniformly for the fleet (kills the flagship-only rotation flip) | Plan |
| Phasing | Bug fixes first (shippable), then foundation → chrome → screens; Replay migrates last behind a regression pass | Live bugs land early; the already-working wide layout is the riskiest migration | Research → Plan |
| Verification | Automated gates per phase; one end-of-slice manual pass on the owner fleet (`manual-verification.md` matrix) | Matches the established deferred-gate workflow; decoupled from S-10 | Plan |
| Tests | Pure policy functions + unit tests on host + iOS simulator; no UI tests | `MoveListModeTest` precedent covers the risky logic; visual truth comes from the manual gate | Research → Plan |

## Scope

**In scope:** all screens (History, NewGame, Play, PhysicalPlay, Connection, Replay, SignIn insets, dialogs); functional fixes (NewGame scroll+IME, Connection reachability, EndGamePicker height, insets/cutout audit); window-class foundation + token consolidation; left-rail chrome; side-pane board screens; recovery side-by-side; wide-Replay migration + regression pass.

**Out of scope:** History→Replay list-detail / Nav3 scenes; `mediaQuery`/`Grid`/`FlexBox`; material3-adaptive canonical scaffolds; typography scaling; new persisted preferences; ViewModel/domain/data changes; new routes; orientation locking; foldable postures; web physical screens; firmware/BLE.

## Architecture / Approach

`WindowSizeClass` computed once at the App root → `LocalWindowSizeClass` → three pure policy functions (`screenChrome`, `boardArrangement`, `boardResizeEnabled`) + a container-driven move-list default. Two shared components consume the policy: `AdaptiveScaffold` (TopAppBar ↔ left rail) and `BoardScreenScaffold` (Column ↔ SidePane with banner slot / board / panel). `ResizableBoardBox`/`boardSide()` stays the single board-sizing authority, fed per-arrangement chrome reservations instead of `VERTICAL_CHROME=140`. Tokens consolidate into `components/Layout.kt`.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Functional fixes | NewGame/Connection/EndGamePicker usable at compact heights; insets audit | Cutout/IME behavior differs per target — audit before building on assumptions |
| 2. Window-class foundation | Official classes at the root; policy functions + tests; tokens consolidated | Adaptive artifact vs material3 alpha pin — mitigated by the compile ladder |
| 3. Left-rail chrome | All six screens: rail at compact height | Novel custom chrome; cutout on the rail edge; per-target manual checks |
| 4. BoardScreenScaffold + Play | Side-pane Play; banner no-jump slots; handle gating | Getting the reservation model right so the board truly fills the height |
| 5. PhysicalPlay | Recovery side-by-side; grid height-bound | Panel must fit grid + controls in a ~360 dp height budget |
| 6. Replay + regression | One policy source app-wide; wide behavior preserved | Regressing the shipped wide Replay — guarded by an explicit regression pass |

**Prerequisites:** worktree `worktree-mobile-landscape-layout` at `main@7c8c365` (includes delete-game p1 — low collision, HistoryScreen row-level only).
**Estimated effort:** ~3–5 sessions across 6 phases; manual acceptance in one end-of-slice pass on the owner fleet.

## Open Risks & Assumptions

- The `material3.adaptive` beta artifact may not compile against the material3 alpha pin — the decision ladder ends in a zero-dependency fallback, so the plan cannot dead-end.
- The left rail is new custom chrome on three targets; iOS back affordance moves from a top bar to the rail (system edge-swipe unaffected).
- Bounded banner slots assume recovery messages fit a capped height (scroll/ellipsis inside the slot for long texts).
- `ui.boardSize` set by flagship-landscape users is intentionally ignored at compact height after this change (contract restoration; called out in the manual pass).

## Success Criteria (Summary)

- A landscape phone shows a deliberate layout on every screen: rail chrome, height-filling board beside a panel, nothing unreachable, nothing under the cutout — verified via the screen × orientation × target matrix in `manual-verification.md`.
- Tablets / desktop / wide web look and behave as before Phase 6's regression list confirms it.
- All automated gates green per phase: three-target compiles + tests, policy-function units on host **and** iOS simulator, ktlint.
