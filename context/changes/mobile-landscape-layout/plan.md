# Mobile Landscape Layout Implementation Plan

## Overview

Introduce **one coherent adaptive layout system** covering (a) phones rotated to landscape, (b) tablets, and (c) wide web browser windows — across **all** screens of the Smart Chessboard app. This is a user-initiated UX change (no PRD FR mandates it); it redeems the app-wide adaptive follow-up explicitly deferred by `replay-seeded-games` and deliberately revisits the "phone-first, single column" scoping of `reject-recover-diagnostics`.

Rotation/resize robustness is already solved (nav stack, ViewModels, `rememberSaveable`, and the BLE link all survive rotation — research §1). This change is **exclusively about what layout to emit for a given window shape**, plus fixing the functional bugs that today's landscape exposes.

Path shorthand: `<pkg>` = `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard`.

## Current State Analysis

Full detail in `context/changes/mobile-landscape-layout/research.md` (2026-07-03, includes Maven-verified dependency matrix). Load-bearing facts:

- **Adaptivity is width-only with a single hand-rolled 840 dp breakpoint.** A landscape phone window (~640–930 × 360–430 dp) either gets the portrait column rotated (< 840 dp wide) or tablet chrome at phone height (≥ 840 dp — Pixel-8-class, iPhone 15 Pro+). No code observes window **height**. The 840 line lands mid-fleet, so two phones ship structurally different landscape behavior today.
- **The board never overflows — the chrome around it breaks.** `boardSide()` height-bounds the board in both modes (`viewportHeight − VERTICAL_CHROME(140dp)`), so in landscape the board self-limits to ~220 dp while ~60–70 % of width sits empty and controls stack below the fold.
- **Two live functional bugs + one unusable flow:** NewGameScreen has no `verticalScroll`/`imePadding` (Start button pushed off-window; IME occludes fields); ConnectionScreen's trailing "Forget saved board" is unreachable when the device list is long; PhysicalPlay recovery (FR-010/011) is unusable in landscape (unbounded 480 dp diagnostics grid below a ~220 dp board). EndGamePicker (~320 dp, no scroll) is borderline at 360 dp heights.
- **Building blocks exist:** `rememberIsWideScreen()` (breakpoint seam), `ResizableBoardBox`/`boardSide()` (single board-sizing authority, height-aware, `reservedWidth` support), Replay's `WideReplay` two-pane (board `weight(1f)` + panel `widthIn(340)`, independent scrolls), `components/Layout.kt` tokens, the `effectiveMoveListMode` "default + persisted override" pattern (unit-tested), `UiPreferences` plumbing.
- **Constants are duplicated:** 840 dp declared twice against *different measurement bases* (window vs local constraints); 480 dp five times (private); `VERTICAL_CHROME = 140.dp` is a portrait-shaped estimate that is wrong for a side-pane layout.
- **Insets:** the only manual inset call is SignIn's `safeContentPadding()`; **no `imePadding()` anywhere**; landscape cutout (long edge) handling unaudited.
- **Dependencies:** CMP `1.11.1`, material3 `1.11.0-alpha07` (Expressive), nav3 `1.1.1`. No adaptive artifacts on the classpath. Maven-verified available (research F1): `material3-window-size-class` at exactly `1.11.0-alpha07`; `org.jetbrains.compose.material3.adaptive:adaptive*` at `1.3.0-beta02` (all our targets incl. wasmJs) pulling `window-core` (modern `WindowSizeClass` with width 600/840 **and height 480/900** breakpoints).

### Key Discoveries:

- `<pkg>/presentation/board/ResizableBoardBox.kt:27,43-52` — public `WIDE_SCREEN_MIN_WIDTH = 840.dp`; `rememberIsWideScreen()` reads `LocalWindowInfo.current.containerSize.width` (window-based, deliberately shared across screens)
- `<pkg>/presentation/replay/ReplayScreen.kt:58,174-177` — duplicate private `TWO_PANE_MIN_WIDTH = 840.dp` measured against **local** `BoxWithConstraints.maxWidth` (they agree today only by accident)
- `<pkg>/presentation/board/ResizableBoardBox.kt:30,113-130` — `VERTICAL_CHROME = 140.dp`; `boardSide()` viewport-height bound applies in both modes
- `<pkg>/presentation/replay/ReplayScreen.kt:270-364,374-403` — `WideReplay` two-pane and `BoardWithEvalBar` `reservedWidth` + `Row(height(IntrinsicSize.Min))` patterns to generalize
- `<pkg>/presentation/newgame/NewGameScreen.kt:65-106` — non-scrolling form, no `imePadding` (live bug)
- `<pkg>/presentation/connection/ConnectionScreen.kt:151-182` — unweighted `LazyColumn` inside a non-scrolling `Column`; trailing Forget button unreachable (live bug)
- `<pkg>/presentation/board/ReedDiagnosticsGrid.kt:43` — `aspectRatio(1f)` grid with **no** viewport-height bound
- `<pkg>/domain/preferences/MoveListMode.kt` + `MoveListModeTest` — the pure "default + override" function precedent for policy functions and their tests
- `<pkg>/di/PlatformModule.android.kt:28-58` — BLE adapter is a Koin `single`; rotation mid-game keeps the link (no transport work needed)
- `SmartChessboard/gradle/libs.versions.toml` — version pins; adaptive artifacts to be added in Phase 2
- `context/changes/ui-theming-and-scaling/manual-verification.md` — its 3-target gate is **fully confirmed** (all `[x]`), so this change carries no inherited manual debt; it authors its own gate

## Desired End State

Every window shape gets a **deliberate** layout, driven by one official window-classification source:

- **Landscape phone (height-compact, ~360–430 dp high):** all screens replace the TopAppBar with a **left vertical rail** (Back + the top-bar actions, no title). Board screens render **side-pane**: board fills the height (~280–300 dp at a 360 dp window vs ~220 dp today) next to a 340–480 dp control panel with its own scroll. No primary control sits below the fold. PhysicalPlay recovery shows the live board and the diagnostics grid **side by side**.
- **Tablets / desktop / wide web (width-expanded, not height-compact):** current wide behavior preserved (two-pane Replay, resize handle + persisted fraction, content max-widths) — now driven by the same policy functions instead of bespoke numbers.
- **Portrait phones:** visually unchanged, except banners no longer displace the board (stable slots).
- The functional bugs (NewGame IME/scroll, Connection reachability, EndGamePicker height, unbounded diagnostics grid) are fixed **regardless of window shape**.

Verification: per-phase automated gates (compile + tests on all three targets, ktlint) plus one end-of-slice manual acceptance pass on the owner's fleet against the per-screen × orientation × target checklist accumulated in `manual-verification.md`.

## What We're NOT Doing

- **No History→Replay list-detail** on tablets/web, no Nav3 `Scene`/`SceneStrategy` work (wasm browser-history semantics with multi-entry scenes are unverified — research F4; optional post-MVP spike).
- **No `mediaQuery` / `Grid` / `FlexBox`** (experimental, flag-gated at our pins — research F3). The pure policy function maps 1:1 onto `derivedMediaQuery` later.
- **No material3-adaptive canonical scaffolds** (`ListDetailPaneScaffold`, `SupportingPaneScaffold`, `NavigationSuiteScaffold`) — our board+controls surface is not list-detail; only the `WindowSizeClass` primitive is adopted.
- **No typography/dimension scaling** with window size (not a mainstream Compose pattern; layout changes only).
- **No new persisted preferences** (no dock-side setting; `ui.boardSize` and `ui.moveListMode` keep their keys and storage).
- **No ViewModel/domain/data changes** — adaptive layout is view-layer only; MVI on Physical/Connection and all ViewModel contracts stay as-is (lessons.md).
- **No new NavKeys/routes** and no second navigation mechanism.
- **No orientation locking** on any platform; no foldable posture handling (foldables are treated purely by window size).
- **No web Connection/PhysicalPlay work** — those screens don't exist on web (FR-020).
- **No firmware/BLE work.**

## Implementation Approach

Evolution, not rewrite (research F6 + user decisions 2026-07-03):

1. **Fix the live functional bugs first** (independently shippable, framework-agnostic).
2. **Adopt the official `WindowSizeClass`** (window-core, added via a compile-verified spike), computed **once** at the App root and exposed through a CompositionLocal. All layout decisions become **pure functions of width class × height class**, unit-tested like `effectiveMoveListMode`. `rememberIsWideScreen()` becomes a derivation during migration. Tokens consolidate into `components/Layout.kt`.
3. **Adaptive chrome:** at height-compact, every screen with a TopAppBar swaps it for a shared **left action rail** (Back + actions, no title).
4. **Board screens** render through one slot-based **`BoardScreenScaffold`** (banner / board / panel) that generalizes the proven `WideReplay` pattern: side-pane at height-compact **or** width-expanded, portrait column otherwise.
5. **Per-screen adoption** in risk order: Play (canonical), PhysicalPlay (adds the diagnostics-beside-board recovery layout), Replay last (migration of the already-working wide layout, guarded by a regression pass).

## Critical Implementation Details

- **No-jump invariant (user decision 2026-07-03):** a banner appearing or disappearing (StatusBanner, BoardMessage, sync/error banners) must **never move or resize the chessboard**, in any arrangement. Banners render in **fixed reserved slots** (empty placeholder when absent) or as overlays; in side-pane mode the banner slot sits at the **top of the side panel**, never above the whole row. This generalizes: avoid any appear/disappear element displacing stable content (the ui-theming eval-tile constant-size fix is the in-repo precedent). Long `BoardMessage` texts must fit a bounded slot (cap + internal scroll/ellipsis) rather than growing it.
- **Dependency decision ladder (Phase 2 starts with this, ~15 min):** try `org.jetbrains.compose.material3.adaptive:adaptive:1.3.0-beta02` (brings `window-core` + `currentWindowAdaptiveInfo()`); if it won't resolve/compile against material3 `1.11.0-alpha07` on any target, try `org.jetbrains.androidx.window:window-core:1.5.1` directly (type only; compute the class from `LocalWindowInfo.containerSize` ourselves); then `org.jetbrains.compose.material3:material3-window-size-class:1.11.0-alpha07` (enum API, version-exact); last resort the hand-rolled two-axis function. **No code builds on the API until the spike compiles on all three targets** (lessons.md: compiler verification over web research; the yarn-lock lesson does not apply — Maven, not npm).
- **One measurement base:** classification is computed from the window (`containerSize` semantics) at the root and passed down; Replay's local-`BoxWithConstraints` basis is retired (Phase 6). Component-level `BoxWithConstraints` stays only where a *component* genuinely adapts to its own slot.
- **Rail insets:** the left rail sits on a long edge — exactly where the landscape camera cutout lives. The rail must consume `WindowInsets.displayCutout` + `systemBars` on its leading edge; Phase 1's audit records what `ScaffoldDefaults.contentWindowInsets` actually covers at material3 `1.11.0-alpha07` (one source check) so Phase 3 builds on facts.
- **Native test discipline:** the new policy functions count as green only after `:shared:iosSimulatorArm64Test` passes, not just the JVM host (lessons.md).
- **Terminology:** "orientation" in older changes means **board flip** (`ChessBoardView(orientation)`); this plan says "window shape / height-compact" and never overloads that word.

## Phase 1: Functional fixes (independently shippable)

### Overview

Fix the reachable-today bugs that landscape exposes, without any new layout machinery. Ships value even if later phases slip.

### Changes Required:

#### 1. NewGame form scroll + IME safety

**File**: `<pkg>/presentation/newgame/NewGameScreen.kt`

**Intent**: Make the form usable at compact heights and with the keyboard open — today the error text pushes Start off-window and the IME occludes fields.

**Contract**: The form column gains `verticalScroll` and IME-aware padding (`imePadding`); with the error visible at a 360 dp-high window every field and the Start button remain reachable. Screen composable signature unchanged.

#### 2. Connection screen reachability

**File**: `<pkg>/presentation/connection/ConnectionScreen.kt`

**Intent**: "Forget saved board" (and any trailing action) must be reachable regardless of device-list length or window height.

**Contract**: The device `LazyColumn` becomes bounded (weighted) inside the column so trailing actions stay on screen; `keepScreenOn` and the connected-guard behavior are untouched.

#### 3. EndGamePicker height safety

**File**: `<pkg>/presentation/play/EndGamePicker.kt`

**Intent**: The ~320 dp dialog must be fully usable at 360 dp-high windows.

**Contract**: Dialog content is height-capped with internal scroll; all options and the confirm action reachable at compact heights.

#### 4. Insets audit + fixes

**File**: audit across the six Scaffold screens + `SignInScreen.kt`; fixes where the audit demands (expected: form screens' IME, landscape cutout)

**Intent**: Establish factually what `ScaffoldDefaults.contentWindowInsets` covers at material3 `1.11.0-alpha07` (one source check), then close real gaps: IME on forms, long-edge display cutout in landscape, iOS home-indicator edge on short windows.

**Contract**: Findings recorded in the phase commit message / progress note; only genuinely missing insets are added (no blanket `safeDrawing` everywhere). SignIn keeps `safeContentPadding()`.

### Success Criteria:

#### Automated Verification:

- All targets compile and existing tests stay green: `:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest`, `:androidApp:assembleDebug`
- ktlint clean

#### Manual Verification:

- NewGame at a landscape-phone window: with the duplicate-name error visible, Start is reachable; focusing a field with IME open never hides the focused field (Android + iOS)
- Connection with a long device list at phone height: "Forget saved board" reachable
- EndGamePicker at ~360 dp height: all result options + confirm reachable
- On a cutout device in both landscape rotations: no content or touch target under the cutout; no regression in portrait

---

## Phase 2: Window classification foundation + token consolidation

### Overview

Adopt the official `WindowSizeClass`, expose it once at the root, express all layout decisions as unit-tested pure functions, and collapse the duplicated constants. Behavior-neutral phase: nothing visibly changes yet.

### Changes Required:

#### 1. Dependency spike (decision ladder)

**File**: `SmartChessboard/gradle/libs.versions.toml`, `SmartChessboard/shared/build.gradle.kts`

**Intent**: Add the window-classification artifact per the ladder in Critical Implementation Details; record which rung won and why.

**Contract**: Chosen artifact resolves and **all three targets compile and test** before any code uses the API. Outcome noted in the Progress section entry and the commit message.

#### 2. Root-level window classification

**File**: new `<pkg>/presentation/layout/AdaptiveLayout.kt`; wiring in `<pkg>/App.kt`

**Intent**: Compute the window size class once at the App root and provide it via a CompositionLocal so every screen reads the same value from the same measurement base.

**Contract**: `LocalWindowSizeClass` provided in `App()` above the NavDisplay. This is the signature contract later phases build on:

```kotlin
enum class ScreenChrome { TopBar, LeftRail }
enum class BoardArrangement { Column, SidePane }

fun screenChrome(windowSizeClass): ScreenChrome        // LeftRail iff height-compact (< 480 dp)
fun boardArrangement(windowSizeClass): BoardArrangement // SidePane iff height-compact OR width-expanded (>= 840 dp)
fun boardResizeEnabled(windowSizeClass): Boolean        // width-expanded AND NOT height-compact
```

Functions are pure and constructible in `commonTest` (exact parameter type — the class itself or width/height Dp — settled by what the spike's API makes testable).

#### 3. Move-list default becomes container-driven

**File**: `<pkg>/domain/preferences/MoveListMode.kt` + `MoveListModeTest`

**Intent**: The default presentation follows the container the list renders in — TABLE in a side panel, INLINE in a portrait column — uniformly for the whole fleet (kills the ≥840-dp-phones-only rotation flip). The persisted override stays authoritative.

**Contract**: `effectiveMoveListMode(override, …)` keys off "renders in side panel" instead of `isWide`; tests updated to the new axis (override wins both ways; defaults per container).

#### 4. Token consolidation

**File**: `<pkg>/presentation/components/Layout.kt`; call sites in `PlayScreen.kt`, `PhysicalPlayScreen.kt`, `ReplayScreen.kt`, `NewGameScreen.kt`, `ConnectionScreen.kt`, `ResizableBoardBox.kt`

**Intent**: One home for layout tokens: the five private `480.dp` section caps become one named constant; the breakpoint numbers live only inside the policy functions; per-arrangement board chrome reservations (named constants for Column vs SidePane) replace the single `VERTICAL_CHROME = 140.dp`.

**Contract**: After this phase `git grep "840.dp"` and `git grep "480.dp"` in `presentation/` each hit exactly one declaration site; `boardSide()` takes its vertical reservation per arrangement (Replay's own `TWO_PANE_MIN_WIDTH` is retired later, in Phase 6, with its layout migration).

#### 5. `rememberIsWideScreen` becomes a derivation

**File**: `<pkg>/presentation/board/ResizableBoardBox.kt`

**Intent**: Keep every existing call site working while the source of truth moves to the window size class.

**Contract**: Same public name and semantics (width-expanded), implemented as a one-line read of `LocalWindowSizeClass`; deprecation comment pointing at the policy functions.

### Success Criteria:

#### Automated Verification:

- Dependency spike: chosen artifact compiles on all three targets (`:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest`, `:androidApp:assembleDebug`)
- New policy-function unit tests pass on host **and** iOS simulator (boundaries: 479/480, 839/840; sanity: 600/900)
- Updated `MoveListModeTest` green on host + iOS simulator
- Token grep check: single declaration site for the 480 section cap; no `VERTICAL_CHROME` symbol left
- ktlint clean

#### Manual Verification:

- Spot-check one target (Android): app renders visually unchanged (this phase is behavior-neutral)

---

## Phase 3: Adaptive chrome — left rail at compact height (all screens)

### Overview

At height-compact, every screen with a TopAppBar swaps it for a shared left vertical rail carrying Back + the same actions (no title). One chrome policy for the whole app (user decision: all screens, not just board screens).

### Changes Required:

#### 1. Shared adaptive chrome scaffold

**File**: new `<pkg>/presentation/components/AdaptiveScaffold.kt`

**Intent**: One wrapper implementing `screenChrome()`: M3 `Scaffold` + `TopAppBar` in TopBar mode; in LeftRail mode a vertical rail on the leading edge hosting the navigation icon (Back) and the screen's actions, content filling the rest. Title is dropped in rail mode.

**Contract**: Slot API mirroring what screens pass today (`title`, optional `navigationIcon`, `actions`, `content`); rail consumes `displayCutout` + `systemBars` on its leading edge; every rail item keeps a ≥ 48 dp touch target; `keepScreenOn` remains a per-screen concern. Web gets the same behavior (short browser window ⇒ rail) — the policy is window-shape-driven, not platform-driven.

#### 2. Migrate all six screens

**File**: `HistoryScreen.kt`, `NewGameScreen.kt`, `PlayScreen.kt`, `PhysicalPlayScreen.kt`, `ReplayScreen.kt`, `ConnectionScreen.kt`

**Intent**: Swap `Scaffold` + `TopAppBar` for `AdaptiveScaffold`; behavior at non-compact heights is pixel-identical (same M3 components underneath).

**Contract**: Screen composable signatures unchanged (contract-surfaces safe); History keeps its 3 actions in the rail; Replay keeps the move-list/analysis toggles; Android-only previews updated where signatures they call changed.

### Success Criteria:

#### Automated Verification:

- All targets compile and tests stay green (same four Gradle tasks)
- ktlint clean

#### Manual Verification:

- Each of the six screens at a landscape-phone window shows the left rail (Back + actions, no top bar, no title); portrait shows the unchanged TopAppBar
- Rail is cutout-safe in **both** landscape rotations (cutout left vs right) on a cutout Android device and an iPhone
- Rail actions are comfortably tappable; a short wide web window shows the rail too, and restoring window height restores the top bar (continuous resize)

---

## Phase 4: BoardScreenScaffold + Play adoption

### Overview

The slot-based board-screen scaffold generalizing `WideReplay`, then Play adopts it as the canonical consumer.

### Changes Required:

#### 1. Board screen scaffold

**File**: new `<pkg>/presentation/components/BoardScreenScaffold.kt`

**Intent**: One arrangement authority for board screens per `boardArrangement()`: SidePane = `Row` of board pane (`weight(1f)`, board via `ResizableBoardBox` with `reservedWidth` support) + side panel (`widthIn(min 340.dp, max 480.dp)`, own `verticalScroll`); Column = today's portrait order. Banner slot per the no-jump invariant: **fixed-height reserved slot at the top of the side panel** (SidePane) / above the board (Column) — board position never depends on banner presence.

**Contract**: Slots: `banner` (nullable content, slot always reserved), `board`, `panelContent` (column scope). Board sizing keeps `boardSide()` as the single authority, fed the per-arrangement chrome reservation from Phase 2 (SidePane reservation ≈ vertical paddings only). The `BoardWithEvalBar` `reservedWidth` pattern must compose inside the board slot unchanged.

#### 2. Play adopts the scaffold

**File**: `<pkg>/presentation/play/PlayScreen.kt`

**Intent**: Play renders through `BoardScreenScaffold`: StatusBanner in the banner slot; Sync section, EndGameSection, MoveList in the panel; move-list default now container-driven (panel ⇒ TABLE default); resize handle gated by `boardResizeEnabled()` (landscape phones: auto-fit, no handle).

**Contract**: `PlayScreen` signature unchanged; panel sections keep the consolidated 480 dp cap; `effectiveMoveListMode` called with the container axis.

### Success Criteria:

#### Automated Verification:

- All targets compile; host + iOS-simulator + wasm tests green; `:androidApp:assembleDebug`
- ktlint clean

#### Manual Verification:

- Play at a landscape-phone window: rail + board (fills the height budget, ~280–300 dp at a 360 dp window) + side panel; no scrolling needed to see the board and reach move list/controls
- Triggering a status/sync banner does **not** move the board (portrait and landscape) — the no-jump invariant holds
- Resize handle: absent on landscape phone; present with persisted fraction on wide web/desktop; portrait phone auto-fit unchanged
- Move list: INLINE default in portrait, TABLE default in the side panel; a persisted override wins in both

---

## Phase 5: PhysicalPlay adoption (recovery side-by-side)

### Overview

PhysicalPlay adopts the scaffold; the diagnostics grid becomes height-bound and, in SidePane, renders beside the live board — making FR-010/011 recovery (compare board vs grid) usable for the first time in landscape. This deliberately revisits the `reject-recover-diagnostics` single-column scoping (it was scoped out, not rejected).

### Changes Required:

#### 1. PhysicalPlay through the scaffold

**File**: `<pkg>/presentation/physical/PhysicalPlayScreen.kt`

**Intent**: BoardMessage renders in the reserved banner slot (top of panel in SidePane; bounded slot — long recovery texts scroll/ellipsize inside it, never grow it); controls, sensor-dots toggle, and the diagnostics section move to the panel; `keepScreenOn` and the live-dots overlay (drawn inside `ChessBoardView`, scales automatically) are untouched.

**Contract**: Screen signature unchanged; the S-09 live matrix overlay and at-rest mismatch tinting behavior are not modified — only *where* the sections render.

#### 2. Diagnostics grid height bound

**File**: `<pkg>/presentation/board/ReedDiagnosticsGrid.kt` (or its call site section)

**Intent**: The grid must never exceed the viewport height budget — fixing the unbounded 480 dp square in portrait too.

**Contract**: Grid keeps `aspectRatio(1f)` and gains a max-side bound derived from the same viewport budget the board uses (reuse `boardSide()`-style bounding or wrap in the existing sizing authority); section cap stays via the consolidated token.

### Success Criteria:

#### Automated Verification:

- All targets compile; host + iOS-simulator + wasm tests green; `:androidApp:assembleDebug`
- ktlint clean

#### Manual Verification:

- PhysicalPlay at a landscape-phone window: rail + board + panel; entering recovery shows the diagnostics grid **beside** the live board — both fully visible without scrolling
- Portrait: grid is height-bound (no longer overflows at low heights); recovery flow unchanged otherwise
- BoardMessage appearing/disappearing never moves the board; long messages stay inside the bounded slot
- Rotating mid physical game (emulator or real board): BLE link and game state survive; screen re-arranges without a reload flash

---

## Phase 6: Replay unification + wide regression + polish

### Overview

Replay — the screen that already works wide — migrates off its private breakpoint onto the shared policy and scaffold, guarded by an explicit wide-screen regression pass. Cross-cutting docs/preview polish lands here.

### Changes Required:

#### 1. Replay migrates to the shared system

**File**: `<pkg>/presentation/replay/ReplayScreen.kt`

**Intent**: `LoadedReplay` drops `TWO_PANE_MIN_WIDTH` + its `BoxWithConstraints` basis; `NarrowReplay`/`WideReplay` collapse into `BoardScreenScaffold` usage. The shipped wide behavior is preserved: eval bar via `BoardWithEvalBar` (`reservedWidth`), EvalPanel + MoveList in the panel, transport controls reachable in both arrangements, `containerMax`/enlargement interpolation retained for width-expanded non-compact windows.

**Contract**: `ReplayScreen` signature unchanged; the eval-bar hold-last/animation behavior untouched; landscape phone gets the SidePane arrangement (board + eval bar left, panel right).

#### 2. `BoardSize` contract rewrite

**File**: `<pkg>/domain/preferences/BoardSize.kt`

**Intent**: The doc comment ("phones always render full-width auto-fit and ignore the stored value") becomes true again — the fraction/handle applies only when `boardResizeEnabled()` (width-expanded and not height-compact).

**Contract**: Doc comment rewritten to name the policy function as the gate; no storage/key change.

#### 3. Previews + final grep sweep

**File**: `SmartChessboard/shared/src/androidMain/.../replay/ReplayScreenPreviews.kt`, `ChessBoardPreviews.kt`; repo-wide grep

**Intent**: Previews compile against the migrated internals; no stray breakpoint constants remain anywhere.

**Contract**: `:androidApp:assembleDebug` compiles previews; `git grep TWO_PANE_MIN_WIDTH` empty; breakpoint numbers exist only inside the policy functions.

### Success Criteria:

#### Automated Verification:

- All targets compile; host + iOS-simulator + wasm tests green; `:androidApp:assembleDebug` (previews included)
- Grep checks: `TWO_PANE_MIN_WIDTH` gone; breakpoint literals only in the policy layer
- ktlint clean

#### Manual Verification:

- **Wide regression pass** (web maximized / desktop / tablet): Replay two-pane visually equivalent to pre-change — centered margins, board-size drag grows toward edges, tight pane gap, eval bar matches board height, move-list toggle + persistence intact
- Replay at a landscape-phone window: SidePane with eval bar beside board; transport + move list reachable in the panel; stepping plies keeps the board stable (no jump)
- History / NewGame / Connection in landscape: list/form caps and centering correct (720 cap on History unchanged)
- **End-of-slice acceptance pass** on the owner fleet (Android phone + iPhone + web resize; tablet/iPad if at hand): full per-screen × orientation × target checklist in `manual-verification.md` confirmed

---

## Testing Strategy

### Unit Tests:

- Policy functions (`screenChrome`, `boardArrangement`, `boardResizeEnabled`): boundary values around 480 dp height and 840 dp width (479/480, 839/840), plus 600/900 sanity — on **host and iOS simulator** (lessons.md Native discipline)
- `effectiveMoveListMode` updated to the container axis: defaults per container, override wins both ways
- Existing `BoardSizeTest` / geometry / animation suites stay green (no contract change)

### Integration Tests:

- None new — no UI tests in this change (decision 2026-07-03): the risky logic (policy) is pure-function-covered; visual truth comes from the manual gate. `runComposeUiTest` remains available for a future change.

### Manual Testing Steps:

Accumulated per phase into `manual-verification.md` (per established workflow: phases run consecutively, manual rows deferred, one end-of-slice pass). The acceptance matrix:

1. **Per screen** (SignIn, History, NewGame, Play, PhysicalPlay, Connection, Replay, dialogs) × **portrait / landscape-left / landscape-right** × **Android phone / iPhone / web (narrow, wide, short-wide)** — deliberate layout, nothing unreachable, nothing under cutout/home indicator
2. Key scenarios: NewGame IME + error at compact height; Connection long list; PhysicalPlay recovery side-by-side; mid-game rotation (digital + physical/BLE) with zero state loss; banner no-jump checks; move-list defaults + override; resize handle only on true wide; wide-Replay regression suite (Phase 6 list)

## Performance Considerations

Window classification is a cheap pure function of `containerSize`, recomputed on resize/rotation — no subcomposition added at the root. Replay *loses* one `BoxWithConstraints` layer. Panel scroll states are per-pane (existing WideReplay pattern). No new hot-path allocation; board overlays already scale with the given square size.

## Migration Notes

No data or schema migration. Persisted keys unchanged (`ui.boardSize`, `ui.moveListMode`, `ui.themeMode`). Semantics deltas: a stored board-size fraction is now ignored at height-compact (restores the documented contract — flagship-landscape users who accidentally set it get auto-fit); move-list defaults change only where no override was persisted. Both are called out for the manual pass.

## References

- Research (incl. Maven-verified version matrix + F1–F6): `context/changes/mobile-landscape-layout/research.md`
- Deferral being redeemed: `context/changes/replay-seeded-games/plan.md:182-186`
- Wide-screen half already shipped: `context/changes/ui-theming-and-scaling/plan.md` (Phases 2, 6, 7)
- Single-column decision being revisited: `context/changes/reject-recover-diagnostics/plan.md:118`
- Two-pane pattern to generalize: `<pkg>/presentation/replay/ReplayScreen.kt:270-403`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Functional fixes (independently shippable)

#### Automated

- [x] 1.1 All targets compile and existing tests stay green (`testAndroidHostTest`, `iosSimulatorArm64Test`, `wasmJsTest`, `assembleDebug`) — 0c9e1cc
- [x] 1.2 ktlint clean — 0c9e1cc

#### Manual

- [ ] 1.3 NewGame landscape: error visible + Start reachable; IME never hides the focused field (Android + iOS)
- [ ] 1.4 Connection long device list at phone height: "Forget saved board" reachable
- [ ] 1.5 EndGamePicker at ~360 dp height: all options + confirm reachable
- [ ] 1.6 Cutout device, both landscape rotations: nothing under the cutout; portrait unregressed

### Phase 2: Window classification foundation + token consolidation

#### Automated

- [x] 2.1 Dependency spike: chosen ladder rung compiles on all three targets; outcome recorded
- [x] 2.2 Policy-function unit tests green on host + iOS simulator (479/480, 839/840, 600/900)
- [x] 2.3 Updated `MoveListModeTest` green on host + iOS simulator
- [x] 2.4 Token grep: one 480 declaration site; `VERTICAL_CHROME` symbol gone
- [x] 2.5 All targets compile; wasm tests green; ktlint clean

#### Manual

- [ ] 2.6 Spot-check (Android): visuals unchanged — phase is behavior-neutral

### Phase 3: Adaptive chrome — left rail at compact height

#### Automated

- [ ] 3.1 All targets compile and tests stay green; ktlint clean

#### Manual

- [ ] 3.2 All six screens at compact height show the rail (Back + actions, no title); portrait keeps the TopAppBar
- [ ] 3.3 Rail cutout-safe in both landscape rotations (Android cutout device + iPhone)
- [ ] 3.4 Rail targets tappable (≥48 dp); web short window shows rail, tall window restores top bar

### Phase 4: BoardScreenScaffold + Play adoption

#### Automated

- [ ] 4.1 All targets compile; host + iOS-sim + wasm tests green; assembleDebug; ktlint clean

#### Manual

- [ ] 4.2 Play landscape phone: rail + height-filling board + panel; no below-fold primaries
- [ ] 4.3 Banner appear/disappear never moves the board (portrait + landscape)
- [ ] 4.4 Resize handle: absent at compact height; present + persisted on wide web/desktop; portrait auto-fit unchanged
- [ ] 4.5 Move-list defaults per container (INLINE portrait / TABLE panel); override wins both ways

### Phase 5: PhysicalPlay adoption (recovery side-by-side)

#### Automated

- [ ] 5.1 All targets compile; host + iOS-sim + wasm tests green; assembleDebug; ktlint clean

#### Manual

- [ ] 5.2 Landscape recovery: diagnostics grid beside the live board, both fully visible without scrolling
- [ ] 5.3 Portrait: grid height-bound (no overflow at low heights)
- [ ] 5.4 BoardMessage no-jump; long messages stay inside the bounded slot; keepScreenOn + sensor dots intact
- [ ] 5.5 Mid-game rotation (physical/BLE or emulator): link + state survive, layout re-arranges cleanly

### Phase 6: Replay unification + wide regression + polish

#### Automated

- [ ] 6.1 All targets compile; host + iOS-sim + wasm tests green; assembleDebug (previews); ktlint clean
- [ ] 6.2 Grep: `TWO_PANE_MIN_WIDTH` gone; breakpoint literals only in the policy layer

#### Manual

- [ ] 6.3 Wide regression pass: Replay two-pane visually equivalent to pre-change (margins, drag growth, pane gap, eval bar height, move-list toggle)
- [ ] 6.4 Replay landscape phone: SidePane with eval bar; transport + move list reachable; stepping plies keeps the board stable
- [ ] 6.5 History / NewGame / Connection landscape: caps and centering correct (History 720 cap unchanged)
- [ ] 6.6 End-of-slice acceptance pass on the owner fleet: full screen × orientation × target matrix confirmed in `manual-verification.md`
