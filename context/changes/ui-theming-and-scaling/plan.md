# UI refresh — theming (M3 Expressive), dark/light, scalable board, animations — Implementation Plan

## Overview

Turn the default-`MaterialTheme {}` skeleton into a styled, polished UI across all three KMP
targets (Android / iOS / WasmJS): a Material 3 Expressive theme with a user-toggleable day/night
mode (the **Slate Steel** blue-accent palette), a scalable chessboard (auto-fit on wide screens
plus a drag-resize handle, lichess-style), and motion (eval-bar, highlights, screen transitions,
dialogs, and a piece-slide animation). The wooden board squares stay constant in both modes; only
the surrounding chrome is themed.

## Current State Analysis

- **Root theme is bare.** [`App.kt`](../../../SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt) wraps everything in `MaterialTheme {}` with no `colorScheme`, no dark/light handling, no `isSystemInDarkTheme()`. There is no root `Scaffold`/top bar; each screen owns its own `Scaffold` + `TopAppBar`.
- **10 colors are hardcoded** as private `val`s:
  - board (6): `LIGHT_SQUARE`, `DARK_SQUARE`, `ARROW_COLOR`, `SELECTED_TINT`, `TARGET_MARK`, `HIGHLIGHT_TINT` — `ChessBoardView.kt:45-50`
  - eval bar (2): `BAR_WHITE`, `BAR_BLACK` — `EvalComponents.kt:29-30`
  - diagnostics (2): `DIAG_LIGHT_CELL`, `DIAG_DARK_CELL` — `ReedDiagnosticsGrid.kt:24-25` (the rest of that grid already uses `MaterialTheme.colorScheme.*`, so it adapts once these two are themed)
- **Board is already size-agnostic.** `ChessBoardView` is `Box(modifier.aspectRatio(1f))` with a `weight(1f)` 8×8 grid (`ChessBoardView.kt:111-114`) — no hardcoded dp. The cap `BOARD_MAX_WIDTH = 480.dp` is duplicated in `ReplayScreen.kt:45` and `PlayScreen.kt:46` (and applied via `Modifier.widthIn(max = …).fillMaxWidth()`).
- **Only Replay is two-pane.** `ReplayScreen` switches to a two-column layout at `TWO_PANE_MIN_WIDTH = 840.dp` via `BoxWithConstraints` (`ReplayScreen.kt:137-143`); Play / PhysicalPlay stay single-column.
- **Eval bar jumps to center on navigation.** `EvalBar` (`EvalComponents.kt:39-66`) takes `PlyEvalState?`; for anything but `Evaluated` it uses fraction `0.5` (center). On ply navigation `ReplayViewModel.moveTo` → `evaluateIfUncached` sets `PlyEvalState.Loading` for the new ply (`ReplayViewModel.kt:165-195`), so the bar snaps to 0.0/center until the new eval arrives. The previous ply's resolved eval is still in the `evals` cache, so a "hold last shown" is achievable in the display layer.
- **No animations anywhere** — zero `animate*AsState`, `AnimatedVisibility`, `Crossfade`, `AnimatedContent`.
- **Persistence + DI are ready.** `Settings` is injected per platform (`PlatformModule.*`: `SharedPreferencesSettings(commit=true)` / `NSUserDefaults` / `StorageSettings`); Koin is the committed DI (`AppModules.kt`). The same `Settings` mechanism backs the game journal; UI preferences reuse it under a `ui.` key prefix.

### Key Discoveries:

- Pieces are vector drawables (`painterResource(pieceDrawable(...))`, `ChessBoardView.kt:138-144`) — they scale crisply, so scaling needs no asset work.
- The repo is already pinned to `material3 = "1.11.0-alpha07"` (`gradle/libs.versions.toml:15`), the JetBrains artifact that carries the Expressive APIs — adopting `MaterialExpressiveTheme` needs **no version bump**, only an `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- `isSystemInDarkTheme()` works on all three targets (Android `uiMode`; iOS/Web via `LocalSystemTheme`) — see research; an explicit toggle still holds its own state.
- `lessons.md`: **MVVM is the default** (a `ThemeViewModel`/state-holder fits), **Koin is the committed DI** (register prefs + theme holder there), on **wasm catch `Throwable`** for any Settings/network call that could fail (StorageSettings is synchronous and local — low risk, but follow the rule if a read can throw).

## Desired End State

- The app launches following the OS dark/light setting; a control in the History top bar cycles **System → Light → Dark** and the choice persists across restarts on every target.
- Both modes are readable: the board stays wooden; eval bar, diagnostics, surfaces, and text use mode-aware Slate Steel tokens.
- On wide screens (tablet / desktop / wide web) the board auto-fills the available space (bounded by height) and can be resized with a corner drag handle; the size persists. Phones keep the full-width auto-fit.
- The eval bar holds the last shown evaluation while the next position loads (no snap to center), shows a loading affordance signalling "this is the previous position's eval", animates its fill, and shows a small numeric score at a fixed spot inside the bar. 0.0 = fill exactly at the board's vertical centre.
- Moves animate: pieces slide from→to; selection / target / lift highlights and the best-move arrow fade in; screen transitions and dialogs animate.

### Verification of end state

- Automated: `:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest` green for the pure-logic units (theme-mode persistence, board-size clamp, eval-bar fraction + hold-last); `:androidApp:assembleDebug` and `:webApp` compile; ktlint clean.
- Manual (3-target gate, `manual-verification.md`): toggle theme on Android/iOS/web; resize the board on a wide window and confirm persistence; navigate plies and confirm the eval bar holds + animates; observe piece-slide, highlight fades, transitions.

## What We're NOT Doing

- No dynamic color / Material You (`dynamicColorScheme`) — one consistent palette across all targets (user decision).
- No dark-mode repaint of the board squares (wood stays constant) and no board-theme picker (deferred).
- No screenshot/golden or Compose UI instrumented tests — pure-logic units + a manual 3-target gate (user decision).
- No new navigation routes / Settings screen — the theme control lives in the History top bar.
- No changes to game logic, persistence schema, eval providers, BLE, or any backend/contract surface.
- No resize handle on phones (auto-fit only there).

## Implementation Approach

Phase 1 lays the theme foundation and removes every hardcoded color, so every later phase styles
against tokens. Phase 2 makes the board scalable (independent of theme). Phase 3 fixes the
eval-bar behaviour and adds its numeric label + fill animation. Phase 4 adds the low-risk motion
(highlights, arrow, transitions, dialogs). Phase 5 adds the high-risk piece-slide last, isolated so
the slice still ships if it slips. Each phase is independently compilable and testable; the manual
3-target gate runs once at slice end.

## Critical Implementation Details

- **Expressive opt-in scope.** `MaterialExpressiveTheme` and the motion-scheme APIs are `@ExperimentalMaterial3ExpressiveApi`. Prefer a per-file `@OptIn` on the new `Theme.kt` (and any file reading `MaterialTheme.motionScheme`) over a global compiler arg, to keep the experimental surface visible. No catalog change — the alpha artifact is already pinned.
- **Eval-bar UX spec (Phase 3).** The numeric label sits *inside* the bar at a **fixed** anchor (bottom-centre, small inset) — it must not track the moving fill boundary and must not jump. 0.0 maps to fill fraction exactly `0.5` (already the case via `whiteBarFraction`). While the viewed ply's eval is `Loading`/absent, the bar shows the **last shown** fraction + score (held in a `remember` that updates only on `Evaluated`) with a loading affordance (e.g. a subtle pulsing overlay or dimmed label), not the center default. The fill animates via `animateFloatAsState`. The held value resets to neutral only when the game/screen changes. Legibility: at the fixed bottom anchor the fill is the light "white-advantage" colour for essentially the whole range; pick a label colour that stays legible (and handle the rare all-dark forced-mate-for-black case).
- **Piece-slide over a weight grid (Phase 5).** The board is a `weight(1f)` grid, so a moved piece can't be tweened in place. The non-obvious approach: render the static grid (with the moved piece's destination square *empty* during the animation) and overlay an absolutely-positioned animating layer that draws the moving piece from the source cell rect to the destination cell rect using an `Animatable`/`animateOffsetAsState` with a spring spec (Expressive **spatial** motion scheme). Derive the move by diffing the previous `Position` (held in `remember`) against the new one; handle the special cases — capture (captured piece fades at destination), castling (king + rook both slide), en passant (captured pawn off a third square), promotion (piece swaps glyph at the end). Respect `orientation` (flip) when computing cell rects (reuse the `center(square)` math from `drawBestMoveArrow`). Falls back to an instant render when the delta is not a single resolvable move (jump-to-ply, load).

## Phase 1: Theme foundation (M3 Expressive + dark/light + Slate Steel + color tokens)

### Overview

Introduce a shared Expressive theme with the Slate Steel light/dark schemes, a `ChessColors`
token set (constant board + mode-aware chrome) via a `CompositionLocal`, a persisted theme-mode
preference, and a History top-bar control. Migrate all 10 hardcoded colors to tokens.

### Changes Required:

#### 1. UI preferences (persistence)

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/preferences/UiPreferences.kt` (new, interface) + `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/preferences/SettingsUiPreferences.kt` (new, impl)

**Intent**: A small durable store for UI choices (theme mode now, board size in Phase 2), backed by the already-injected `Settings` under a `ui.` key prefix so it never collides with the journal.

**Contract**: `UiPreferences` exposes theme mode as a `StateFlow`/getter+setter — `themeMode: ThemeMode` (`SYSTEM` / `LIGHT` / `DARK`) persisted under key `ui.themeMode`. Impl takes `Settings` constructor-injected. Keep reads total (default `SYSTEM` when unset/invalid).

#### 2. Theme mode holder

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/theme/ThemeViewModel.kt` (new)

**Intent**: MVVM holder (per `lessons.md`) exposing the current `ThemeMode` and a `setMode`/`cycle` intent; drives `AppTheme` and the History control.

**Contract**: `class ThemeViewModel(prefs: UiPreferences) : ViewModel` with `val mode: StateFlow<ThemeMode>` and `fun setMode(ThemeMode)` (persists via `UiPreferences`). A `ThemeMode` enum lives in this package (or `domain/preferences`).

#### 3. App theme + color tokens

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/theme/Theme.kt` (new) + `ChessColors.kt` (new, same package)

**Intent**: Define the Slate Steel `lightColorScheme`/`darkColorScheme`, a `ChessColors` data class with `LocalChessColors`, and an `AppTheme(mode, content)` composable that resolves dark/light from `ThemeMode` + `isSystemInDarkTheme()` and applies `MaterialExpressiveTheme` + provides `LocalChessColors`.

**Contract**:
- `AppTheme(mode: ThemeMode, content: @Composable () -> Unit)` — `val dark = when(mode){ SYSTEM -> isSystemInDarkTheme(); LIGHT -> false; DARK -> true }`; wraps `MaterialExpressiveTheme(colorScheme = if (dark) DarkColors else LightColors)` and `CompositionLocalProvider(LocalChessColors provides if (dark) ChessColorsDark else ChessColorsLight)`. `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- **Slate Steel anchors** (generate the full M3 role set seeded on the accent; pin these neutrals):
  - Light: `primary = #3B6EA5`, `onPrimary = #FFFFFF`, `background = #F1F4F7`, `surface = #FFFFFF`, `surfaceVariant = #EAF0F6`, `onSurface = #1C2530`, `onSurfaceVariant = #5A6573`.
  - Dark: `primary = #6FA8DC`, `onPrimary = #0A131C`, `background = #121821`, `surface = #1B2430`, `surfaceVariant = #202B39`, `onSurface = #E3E9F0`, `onSurfaceVariant = #93A1B3`.
- `ChessColors` fields (each with a light and a dark value; **board hues identical in both**): `lightSquare = #F0D9B5`, `darkSquare = #B58863` (constant); chrome with dark variants: `selectedTint`, `targetMark`, `bestMoveArrow`, `liftHighlight`, `evalBarTrack`, `evalBarFill`, `evalBarLabel`, `diagLightCell`, `diagDarkCell`. Defaults for light = the current hex values; dark variants chosen for contrast on the dark surface (e.g. eval track/fill retain white-advantage = light fill semantics).

#### 4. Wire the theme at the root

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt`

**Intent**: Replace bare `MaterialTheme {}` with `AppTheme(mode)`, resolving `ThemeViewModel` at the root and threading its mode + setter to the History entry.

**Contract**: `val themeVm = koinViewModel<ThemeViewModel>()`; `val mode by themeVm.mode.collectAsStateWithLifecycle()`; `AppTheme(mode) { … existing content … }`. Pass `themeMode = mode` and `onSetThemeMode = themeVm::setMode` (or `onCycleTheme`) into `HistoryScreen` at its `entry<HistoryKey>`.

#### 5. DI registration

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/di/AppModules.kt`

**Intent**: Register `UiPreferences` impl (single) and `ThemeViewModel`.

**Contract**: in `dataModule`: `single<UiPreferences> { SettingsUiPreferences(get()) }`; in `presentationModule`: `viewModelOf(::ThemeViewModel)`.

#### 6. History top-bar theme control

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/history/HistoryScreen.kt`

**Intent**: Add a theme-cycle action to the existing `TopAppBar` actions (alongside New game / Sign out).

**Contract**: new params `themeMode: ThemeMode`, `onSetThemeMode: (ThemeMode) -> Unit` (or `onCycleTheme: () -> Unit`); render an `IconButton` showing the current mode (`ti`-style icon: auto/sun/moon via Material icons or text) that cycles System → Light → Dark.

#### 7. Migrate hardcoded colors to tokens

**Files**: `presentation/board/ChessBoardView.kt`, `presentation/replay/EvalComponents.kt`, `presentation/board/ReedDiagnosticsGrid.kt`

**Intent**: Replace the private color `val`s with `LocalChessColors.current.*`; remove the literals.

**Contract**: `ChessBoardView` reads `selectedTint`/`targetMark`/`bestMoveArrow`/`liftHighlight`/`lightSquare`/`darkSquare` from the local (note the two `DrawScope` arrow/target helpers take a `Color` param instead of the file-level `val`). `EvalComponents.EvalBar` reads `evalBarTrack`/`evalBarFill`. `ReedDiagnosticsGrid` reads `diagLightCell`/`diagDarkCell` (its `errorContainer`/`error`/`onSurfaceVariant` usage is unchanged — already themed).

### Success Criteria:

#### Automated Verification:

- Theme-mode persistence unit test passes (set DARK → re-read returns DARK) on host: `:shared:testAndroidHostTest`
- Pure units pass on a Native target: `:shared:iosSimulatorArm64Test`
- Web compiles/tests: `:shared:wasmJsTest`
- Android app builds: `ANDROID_HOME=… :androidApp:assembleDebug`
- ktlint clean: `ktlint` from `SmartChessboard/`

#### Manual Verification:

- Cycling the History control switches System/Light/Dark live on Android, iOS, and web
- The chosen mode survives an app restart on each target
- Both modes are readable: board, eval bar, diagnostics, surfaces, text all legible; wood board unchanged

---

## Phase 2: Scalable board (auto-fit + drag-resize handle, persisted)

### Overview

Lift the fixed 480 dp cap; on wide screens size the board to the available space bounded by height
(lichess-style), with a corner drag handle that sets a persisted size. Phones keep full-width
auto-fit. Apply consistently in Replay / Play / PhysicalPlay.

### Changes Required:

#### 1. Board size preference

**File**: `data/preferences/SettingsUiPreferences.kt` + `domain/preferences/UiPreferences.kt`

**Intent**: Persist the user's wide-screen board size.

**Contract**: add `boardSize` (a fraction or dp, e.g. `Float` in a clamped range) persisted under `ui.boardSize`; default = a sensible mid value. Total reads.

#### 2. Resizable board wrapper

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/board/ResizableBoardBox.kt` (new)

**Intent**: One reusable container that decides board size: phones (narrow) → full width auto-fit; wide → `min(availableWidth × userSize, availableHeight)` with a draggable corner handle that updates the persisted size; renders the handle only on wide screens.

**Contract**: `ResizableBoardBox(isWide: Boolean, size: Float, onSizeChange: (Float) -> Unit, content: @Composable (Modifier) -> Unit)` — computes a square `Modifier` via `BoxWithConstraints` (`min(maxWidth, maxHeight)` bounded, clamped to min/max), draws a corner handle (`pointerInput`/`detectDragGestures`) on wide, and passes the sized `Modifier` to `content`. Pure clamp math extracted to a testable function `clampBoardSize(raw, min, max)`.

#### 3. Adopt the wrapper in the board screens

**Files**: `presentation/replay/ReplayScreen.kt`, `presentation/play/PlayScreen.kt`, `presentation/physical/PhysicalPlayScreen.kt`

**Intent**: Replace the duplicated `BOARD_MAX_WIDTH` + `widthIn(max=…).fillMaxWidth()` board sizing with `ResizableBoardBox`; pull `boardSize` + the wide flag (Replay already has the `BoxWithConstraints` breakpoint) and pass through.

**Contract**: remove the per-file `BOARD_MAX_WIDTH`; board (and, in Replay, the eval bar height tie via `IntrinsicSize.Min`) sizes from the wrapper. Wide detection reuses the existing `TWO_PANE_MIN_WIDTH`/`BoxWithConstraints`; Play/PhysicalPlay treat ≥ breakpoint as wide. Non-board section widths (move list, controls) keep their current behaviour.

### Success Criteria:

#### Automated Verification:

- `clampBoardSize` unit test (below-min, above-max, in-range) passes: `:shared:testAndroidHostTest`
- Board-size persistence round-trip test passes (host + `:shared:iosSimulatorArm64Test`)
- All targets compile: `:androidApp:assembleDebug`, `:webApp` build, ktlint clean

#### Manual Verification:

- On a wide window (web/desktop/tablet) the board fills the space up to the viewport height; dragging the corner resizes it; the size persists across restart
- On a phone the board is full-width auto-fit with no handle and no regression
- Replay two-pane, Play, and PhysicalPlay all render the board correctly at narrow and wide widths

---

## Phase 3: Eval-bar behaviour + numeric label + fill animation

### Overview

Stop the bar snapping to center on ply change: hold the last shown evaluation until the new one
resolves, show a loading affordance, add a fixed in-bar numeric label, and animate the fill.

### Changes Required:

#### 1. Hold-last + loading affordance + numeric label

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/replay/EvalComponents.kt`

**Intent**: Make `EvalBar` keep the previously shown fraction + score while the current ply is `Loading`/absent, animate to the new fraction, render a small score label at a fixed inside-bar anchor, and show a loading affordance signalling the eval is for the previous position.

**Contract**: `EvalBar` gains internal `remember { mutableStateOf(0.5f) }` (last shown fraction) and a remembered last score string, updated only when `eval is Evaluated`; the displayed fraction goes through `animateFloatAsState`. A boolean `loading = eval == null || eval is PlyEvalState.Loading` drives a subtle affordance (pulsing overlay or dimmed label). Numeric label = `formatEvalScore(...)` of the last shown eval, drawn inside the bar at a fixed bottom-centre anchor, font ≥ 11 sp, legible colour. 0.0 still maps to fraction `0.5` (`whiteBarFraction` unchanged). Extract any new pure logic (e.g. "fraction to show given current+last") to a testable function.

### Success Criteria:

#### Automated Verification:

- Unit test: given a `Loading` current eval and a prior `Evaluated`, the shown fraction/score equals the prior (not 0.5); given `Evaluated`, it tracks the new value. `:shared:testAndroidHostTest` + `:shared:iosSimulatorArm64Test`
- `whiteBarFraction(0,null) == 0.5` regression intact; web compiles

#### Manual Verification:

- Stepping forward/back through plies: the bar holds the prior eval and animates to the new one instead of snapping to center
- The loading affordance shows while the new eval is fetching
- The numeric label is readable, sits at a fixed spot inside the bar, and does not jump; 0.0 is exactly mid-board

---

## Phase 4: Motion — highlights, arrow, screen transitions, dialogs

### Overview

Add the low-risk animations across the app.

### Changes Required:

#### 1. Board highlight + arrow animations

**File**: `presentation/board/ChessBoardView.kt`

**Intent**: Fade selection / legal-target / lift-highlight tints in and out, and draw/fade the best-move arrow in, instead of hard toggles.

**Contract**: wrap tint overlays in `AnimatedVisibility`/`animateColorAsState`; animate the arrow alpha (and optionally length) when `bestMoveArrow` appears. No change to the tap/selection contract or `BoardInteraction`.

#### 2. Screen transitions

**File**: `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt`

**Intent**: Animate `NavDisplay` destination changes (push/pop) with a transition spec.

**Contract**: supply `NavDisplay`'s transition parameters (Nav3 `AnimatedContent`-based transition) for forward/back; keep `onBack`/back-stack behaviour and the wasm browser-history binding unchanged.

#### 3. Dialog + panel transitions

**Files**: `presentation/play/PlayScreen.kt` (`PromotionPicker`, `EndGamePicker` use sites), `presentation/replay/ReplayScreen.kt` (eval panel)

**Intent**: Animate dialog enter/exit and crossfade the eval panel between states.

**Contract**: wrap the picker presence and the `EvalPanel` state switch in `AnimatedVisibility`/`Crossfade`; no logic change.

### Success Criteria:

#### Automated Verification:

- All targets compile and existing tests stay green: `:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest`, `:androidApp:assembleDebug`; ktlint clean

#### Manual Verification:

- Selecting a piece / showing targets / lifting in physical mode fades smoothly; the best-move arrow fades in
- Navigating between screens animates on all three targets; back/forward unaffected (incl. web browser Back)
- Promotion and end-game dialogs animate in/out; eval panel crossfades between states

---

## Phase 5: Piece-slide animation (overlay over the weight grid)

### Overview

Animate a piece sliding from its source square to its destination on each single-move position
change. Isolated last because it requires an overlay layer and special-case handling.

### Changes Required:

#### 1. Animated piece overlay

**File**: `presentation/board/ChessBoardView.kt` (+ a small internal helper file if it grows)

**Intent**: When the rendered `position` changes by exactly one resolvable move, slide the moved piece from→to over the static grid; otherwise render instantly.

**Contract**: hold the previous `Position` in `remember`; diff to derive the move (from, to, captured, castle rook, en-passant square, promotion). Render the grid with the destination piece suppressed during the run, and an absolutely-positioned overlay drawing the moving piece(s) via `Animatable`/`animateOffsetAsState` with a spring spec from the Expressive **spatial** motion scheme. Reuse the orientation-aware `center(square)` geometry. Handle capture (fade the captured piece), castling (king + rook slide together), en passant, and promotion (glyph swap at the end). Fall back to instant render for multi-ply jumps, load, or an unresolvable delta. Display-only — no change to `BoardInteraction` / tap handling.

### Success Criteria:

#### Automated Verification:

- Pure move-diff helper (previous vs new `Position` → moved piece(s)/special case) unit-tested for normal move, capture, castling, en passant, promotion, and "not a single move → none": `:shared:testAndroidHostTest` + `:shared:iosSimulatorArm64Test`
- All targets compile; ktlint clean

#### Manual Verification:

- In Play, a tapped move slides the piece; captures, castling, en passant, and promotion animate correctly under both orientations (flip)
- In Replay, stepping forward animates the slide; jumping plies / loading renders instantly with no glitch
- PhysicalPlay (emulator) move resolution slides correctly; no regression to highlights or arrow

---

## Phase 6: Wide-screen layout & move-list refinements

### Overview

Post-bring-up polish from live web/mobile feedback: keep the wide-screen layout from stretching
edge-to-edge, fix the resize handle and the mobile eval bar, stabilise the eval-panel tile, and add
a lichess-style two-column move list as a persisted, screen-defaulted toggle. Display-only; no
change to game logic, persistence schema, or any contract surface.

### Changes Required:

#### 1. Resize handle on the board's own corner

**File**: `presentation/board/ResizableBoardBox.kt`

**Intent**: The corner grip pinned to the *content's* bottom-end, which in Replay is the eval bar's
corner — covering its numeric label. Pin it to the board square's corner instead.

**Contract**: overlay the handle on a board-sized `Box(Modifier.size(side), BottomEnd)`, not the
whole content Row. The drag maps to the board's width budget (pane minus the reserved adjacent
element). No change to the persisted-size contract.

#### 2. Reserve eval-bar width on narrow + absolute board cap + lower default

**Files**: `presentation/board/ResizableBoardBox.kt`, `presentation/replay/ReplayScreen.kt`, `domain/preferences/BoardSize.kt`

**Intent**: On a phone the full-width board pushed the eval bar off-screen (the analysis bar was
missing on Android/iOS). Reserve the bar's width so board + bar fit. Cap the board at an absolute
`BOARD_MAX_SIDE` so it stays natural on a big monitor; lower `BOARD_SIZE_DEFAULT` (0.7 → 0.6).

**Contract**: `ResizableBoardBox` gains a `reservedWidth: Dp` subtracted from the usable width before
the fraction/cap; `BoardWithEvalBar` passes `EvalBarWidth + gap` when analysis is on. `boardSide`
also caps at `BOARD_MAX_SIDE = 640.dp`. `EvalBarWidth` becomes `internal`.

#### 3. Fixed eval-panel tile height

**File**: `presentation/replay/EvalComponents.kt`

**Intent**: The `EvalPanel` resized between the short loading state and the taller evaluated one, so
the crossfade "jumped". Give it a fixed minimum height.

**Contract**: `EvalPanel`'s `Surface` gets `heightIn(min = EvalPanelMinHeight)` sized to the tallest
(evaluated) state. No content/logic change.

#### 4. Centered max-width content + bounded Replay side panel + History list cap

**Files**: `presentation/components/Layout.kt` (new), `presentation/replay/ReplayScreen.kt`, `presentation/play/PlayScreen.kt`, `presentation/physical/PhysicalPlayScreen.kt`, `presentation/history/HistoryScreen.kt`

**Intent**: On wide windows the content stretched edge-to-edge and the two Replay columns were 50/50
(the move list ate half the screen); the History list was full-width and left-aligned.

**Contract**: a `CONTENT_MAX_WIDTH = 1200.dp` centred container wraps each board screen's content
(top bars stay full-bleed). Replay two-pane: board column `weight(1f)`, side panel
`widthIn(max = SIDE_PANEL_MAX_WIDTH = 340.dp)`. History list `widthIn(max = LIST_MAX_WIDTH = 720.dp)`
centred.

#### 5. Lichess-style two-column move list (persisted, screen-defaulted toggle)

**Files**: `domain/preferences/MoveListMode.kt` (new), `domain/preferences/UiPreferences.kt`, `data/preferences/SettingsUiPreferences.kt`, `presentation/board/BoardPreferencesViewModel.kt`, `presentation/components/MoveList.kt`, `presentation/replay/ReplayScreen.kt`, `presentation/play/PlayScreen.kt`, `presentation/physical/PhysicalPlayScreen.kt`

**Intent**: Offer a lichess-style grid (white | black, one full move per row) alongside the compact
inline flow, toggleable and persisted, defaulting by screen width.

**Contract**: `MoveListMode { INLINE, TABLE }` + pure `effectiveMoveListMode(override, isWide)`
(override ?? by-width). Persisted under `ui.moveListMode` (null = unset → default by screen).
`MoveList` gains `tableMode: Boolean`. A persisted toggle lives in the Replay top bar; Play /
PhysicalPlay render the same effective mode.

### Success Criteria:

#### Automated Verification:

- `effectiveMoveListMode` unit-tested (null→by-width; explicit override wins): host + `:shared:iosSimulatorArm64Test`
- Move-list-mode persistence round-trip + unset/unrecognized → null: `:shared:testAndroidHostTest`
- All targets compile, existing tests green (host, iOS sim, wasm, android assemble); ktlint clean

#### Manual Verification:

- Resize handle sits on the board's corner and no longer covers the eval label
- Wide window: content is centred with side margins, board column wider than a bounded move-list panel
- Mobile (Android/iOS) analysis shows the eval bar to the right of the board
- The eval-panel tile no longer changes size between loading and evaluated
- Move-list toggle switches inline ↔ table, persists, and defaults to table on wide / inline on phone
- History list is capped and centred on wide screens

---

## Testing Strategy

### Unit Tests:

- Theme-mode persistence (set/get round-trip), default when unset.
- `clampBoardSize` boundaries; board-size persistence round-trip.
- Eval-bar "fraction/score to show" given current + last (hold-last), and `whiteBarFraction` regression.
- Piece move-diff helper across normal/capture/castle/en-passant/promotion/none.
- All pure logic must pass on `:shared:iosSimulatorArm64Test` too (Native engine differs from JVM — `lessons.md`).

### Manual Testing Steps (3-target gate → `manual-verification.md`):

1. Toggle System/Light/Dark from History; confirm live switch + persistence on Android/iOS/web.
2. Wide window: board auto-fills, corner drag resizes, size persists; phone: full-width, no handle.
3. Replay: step plies — eval bar holds prior eval, animates, numeric label fixed; 0.0 mid-board.
4. Observe piece-slide (incl. capture/castle/en-passant/promotion), highlight fades, arrow fade-in, screen transitions, dialog animations.

## Performance Considerations

Animations are local and cheap except the piece-slide; keep it a single overlay layer driven by one
spring, suppressing the destination piece only during the run, so recomposition stays bounded. The
scalable board adds no per-frame cost (one square `Modifier` from `BoxWithConstraints`).

## Migration Notes

New `ui.`-prefixed keys in the existing `Settings` store; no migration of existing journal data, no
schema/contract change. Removing `BOARD_MAX_WIDTH` is internal.

## References

- Change identity: `context/changes/ui-theming-and-scaling/change.md`
- Plan brief: `context/changes/ui-theming-and-scaling/plan-brief.md`
- Current UI map + 2026 CMP styling research: captured in the planning session
- Key sources: `App.kt`, `presentation/board/ChessBoardView.kt`, `presentation/replay/{ReplayScreen,ReplayViewModel,EvalComponents}.kt`, `presentation/play/PlayScreen.kt`, `presentation/board/ReedDiagnosticsGrid.kt`, `presentation/history/HistoryScreen.kt`, `di/{AppModules,PlatformModule.*}.kt`, `gradle/libs.versions.toml`
- Rules: `context/foundation/lessons.md` (MVVM default; Koin DI; catch `Throwable` on wasm; Native-green requirement)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Theme foundation (M3 Expressive + dark/light + Slate Steel + color tokens)

#### Automated

- [x] 1.1 Theme-mode persistence unit test passes (`:shared:testAndroidHostTest`) — 8f31e97
- [x] 1.2 Pure units pass on Native (`:shared:iosSimulatorArm64Test`) — 8f31e97
- [x] 1.3 Web compiles/tests (`:shared:wasmJsTest`) — 8f31e97
- [x] 1.4 Android app builds (`:androidApp:assembleDebug`) — 8f31e97
- [x] 1.5 ktlint clean — 8f31e97

#### Manual

- [ ] 1.6 History control switches System/Light/Dark live on Android, iOS, web
- [ ] 1.7 Chosen mode survives restart on each target
- [ ] 1.8 Both modes readable; wood board unchanged

### Phase 2: Scalable board (auto-fit + drag-resize handle, persisted)

#### Automated

- [x] 2.1 `clampBoardSize` boundary unit test passes (`:shared:testAndroidHostTest`) — 5ce7104
- [x] 2.2 Board-size persistence round-trip passes (host + `:shared:iosSimulatorArm64Test`) — 5ce7104
- [x] 2.3 All targets compile (`:androidApp:assembleDebug`, `:webApp`), ktlint clean — 5ce7104

#### Manual

- [ ] 2.4 Wide window: board fills to viewport height, corner drag resizes, size persists
- [ ] 2.5 Phone: full-width auto-fit, no handle, no regression
- [ ] 2.6 Replay two-pane / Play / PhysicalPlay render correctly narrow and wide

### Phase 3: Eval-bar behaviour + numeric label + fill animation

#### Automated

- [x] 3.1 Hold-last fraction/score unit test passes (host + `:shared:iosSimulatorArm64Test`) — 1e84936
- [x] 3.2 `whiteBarFraction(0,null)==0.5` regression intact; web compiles — 1e84936

#### Manual

- [ ] 3.3 Bar holds prior eval and animates to new one (no snap to center)
- [ ] 3.4 Loading affordance shows while fetching
- [ ] 3.5 Numeric label readable, fixed inside bar, no jump; 0.0 mid-board

### Phase 4: Motion — highlights, arrow, screen transitions, dialogs

#### Automated

- [x] 4.1 All targets compile, existing tests green (host, iOS sim, wasm, android assemble), ktlint clean — 3d290ba

#### Manual

- [ ] 4.2 Selection/targets/lift fade; best-move arrow fades in
- [ ] 4.3 Screen transitions animate on all targets; back/forward (incl. web Back) unaffected
- [ ] 4.4 Promotion/end-game dialogs animate; eval panel crossfades

### Phase 5: Piece-slide animation (overlay over the weight grid)

#### Automated

- [ ] 5.1 Move-diff helper unit-tested (normal/capture/castle/en-passant/promotion/none), host + iOS sim
- [ ] 5.2 All targets compile; ktlint clean

#### Manual

- [ ] 5.3 Play: tapped move slides; capture/castle/en-passant/promotion correct under both orientations
- [ ] 5.4 Replay: step animates; jump/load renders instantly with no glitch
- [ ] 5.5 PhysicalPlay (emulator) slides correctly; highlights/arrow unaffected

### Phase 6: Wide-screen layout & move-list refinements

#### Automated

- [x] 6.1 `effectiveMoveListMode` + move-list-mode persistence unit tests pass (host + `:shared:iosSimulatorArm64Test`)
- [x] 6.2 All targets compile, existing tests green (host, iOS sim, wasm, android assemble); ktlint clean

#### Manual

- [ ] 6.3 Resize handle sits on the board's corner and no longer covers the eval label
- [ ] 6.4 Wide window: content centred with side margins; board column wider than the bounded move-list panel
- [ ] 6.5 Mobile (Android/iOS) analysis shows the eval bar to the right of the board
- [ ] 6.6 The eval-panel tile keeps a constant size between loading and evaluated
- [ ] 6.7 Move-list toggle switches inline ↔ table, persists, defaults to table on wide / inline on phone
- [ ] 6.8 History list is capped and centred on wide screens
