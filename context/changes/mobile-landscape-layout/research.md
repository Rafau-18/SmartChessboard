---
date: 2026-07-03T00:07:43+0200
researcher: Rafał Urbaniak
git_commit: 9368d6810db9f1eaa5587bf96b70e92d52b57174
branch: worktree-mobile-landscape-layout
repository: smartchessboard (bitbucket: <user>/smartchessboard)
topic: "Mobile landscape layout — one coherent adaptive layout system for phone landscape, tablets, and wide web windows, across all screens"
tags: [research, codebase, mobile, landscape, adaptive-layout, window-size, compose-multiplatform, presentation, ui-theming-and-scaling, kmp]
status: complete
last_updated: 2026-07-03
last_updated_by: Rafał Urbaniak
last_updated_note: "Follow-up: web research on CMP adaptive best practices, Nav3 scenes, new layout APIs; version matrix hard-verified against Maven Central; holistic architecture recommendation added."
---

# Research: Mobile landscape layout

**Date**: 2026-07-03T00:07:43+0200
**Researcher**: Rafał Urbaniak
**Git Commit**: 9368d6810db9f1eaa5587bf96b70e92d52b57174
**Branch**: worktree-mobile-landscape-layout
**Repository**: smartchessboard (Bitbucket `<user>/smartchessboard`; remote not up to date — no permalinks, paths below are repo-relative)

Path shorthand: `<pkg>` = `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard`.

## Research Question

How should the app introduce **one coherent adaptive layout system** covering (a) phones rotated to landscape, (b) tablets, and (c) wide web browser windows — across **all** screens? What exists today (orientation config, adaptive machinery, state survival on rotation), what actually breaks in landscape, and which constraints and prior decisions bound the implementation plan?

**Scope decisions taken at kickoff (2026-07-02):** all screens in scope (not just board screens); targets = phones + tablets + web treated as one adaptive system; the change worktree was fast-forwarded to local `main` (`9368d68`) before research so findings match the code the change will be built on.

## Summary

1. **Rotation already works mechanically — this change is pure layout work.** No orientation lock exists on any platform (Android manifest sets no `screenOrientation`; iPhone allows Portrait + both Landscapes). On Android the Activity recreates on rotation, but everything survives: the Nav3 back stack (explicit polymorphic serializers + both `rememberSaveableStateHolder`/`rememberViewModelStore` decorators), ViewModels (retained store), `rememberSaveable` UI state, and — critically — the **BLE link**, because `KableBoardAdapter` is a Koin `single` scoped to the app, not to a screen. No plumbing, lifecycle, or transport work is needed.
2. **The current adaptivity is width-only, with a single hand-rolled 840 dp breakpoint — phone landscape falls into a structural dead zone.** A landscape phone window is ~640–930 × 360–430 dp. Below 840 dp width it gets the *portrait phone layout rotated* (board-above-controls column); at/above 840 dp (modern flagships: Pixel-8-class 914×411, iPhone 15 Pro 852×393) it gets *tablet chrome at phone height*. **No breakpoint anywhere considers height.** This split lands mid-fleet, so today two users with different phones see structurally different landscape behavior.
3. **The board itself never overflows — the chrome around it is what breaks.** `boardSide()` height-bounds the board in *both* modes (`viewportHeight − VERTICAL_CHROME(140dp)`), so in landscape the board self-limits to ~220 dp while ~60–70 % of the width sits empty and everything else (status, controls, move list, diagnostics) stacks below the fold.
4. **Two functional breaks are reachable today** (rotation is free, so these are live bugs, not hypotheticals): **NewGameScreen** has no `verticalScroll` and no `imePadding` — at 360 dp-class heights the Start button is pushed off-window when the error text shows, and the IME occludes fields at any height; **ConnectionScreen**'s trailing "Forget saved board" button becomes unreachable when the device list is long (unweighted `LazyColumn` inside a non-scrolling `Column`). Additionally **PhysicalPlayScreen**'s recovery flow (FR-010/011: compare board vs `ReedDiagnosticsGrid`) is unusable in landscape — the grid is an unbounded 480 dp square below a ~220 dp board.
5. **The building blocks for the fix already exist and generalize.** `rememberIsWideScreen()` (the breakpoint seam), `ResizableBoardBox`/`boardSide()` (the one board-sizing authority, already height-aware, with `reservedWidth` for adjacent panes), Replay's `WideReplay` two-pane pattern (board pane `weight(1f)` + side panel `widthIn(340)`, independent scrolls), `components/Layout.kt` width tokens, the `effectiveMoveListMode` "width-driven default + persisted override" pattern, and the `UiPreferences`/`BoardPreferencesViewModel` plumbing. What needs consolidation: **840 dp is declared twice** (public `WIDE_SCREEN_MIN_WIDTH` vs private `TWO_PANE_MIN_WIDTH`, measured against *different bases* — window width vs local constraints), **480 dp five times** (all private), and `VERTICAL_CHROME = 140.dp` is a portrait-shaped estimate that a landscape/side-pane layout must replace.
6. **No adaptive library is on the classpath; the shared code's only window primitive is `LocalWindowInfo.containerSize`.** Pins: Compose Multiplatform `1.11.1`, material3 `1.11.0-alpha07` (Expressive), navigation3 `1.1.1`. JetBrains does publish multiplatform `material3-window-size-class` / `material3-adaptive` artifacts, but version alignment with the alpha material3 pin is **unverified** — the plan should verify by add-and-compile (per lessons discipline), with the zero-dependency alternative being to extend the existing hand-rolled `rememberIsWideScreen` seam with a height dimension.
7. **This change redeems an explicitly deferred decision.** `replay-seeded-games/plan.md` deferred the "app-wide adaptive follow-up (WindowSizeClass, multi-pane)"; `ui-theming-and-scaling` then shipped the *wide-screen* half (two-pane Replay, resizable board, adaptive margins); `reject-recover-diagnostics` explicitly scoped physical screens as "phone-first, single column, no two-pane". The PRD contains **no** landscape/tablet requirement — this is a user-initiated UX change. No collisions: `delete-game-from-history` (in flight on main) is a row-level affordance in HistoryScreen; S-10 is proposed, testing-only.

## Detailed Findings

### 1. Rotation reality: configuration & what survives

**Android** — `SmartChessboard/androidApp/src/main/AndroidManifest.xml:35-55`: activity has **no** `android:screenOrientation`, **no** `android:configChanges`, `launchMode="singleTask"` → rotation recreates the Activity and recomposes from `App()`. `MainActivity.kt:11-18`: `enableEdgeToEdge()` + `setContent { App() }`; Koin initialized once in `SmartChessboardApplication.kt:8-13`.

**What survives the Android recreation:**
- **Back stack**: `App.kt:76` `rememberNavBackStack(navSavedStateConfiguration, HistoryKey)`; `App.kt:89-92` installs *both* `rememberSaveableStateHolderNavEntryDecorator()` and `rememberViewModelStoreNavEntryDecorator()`. `<pkg>/presentation/navigation/Routes.kt:59-72` registers every `NavKey` with explicit polymorphic serializers (mandatory — iOS/wasm have no reflection; **any new NavKey must be added there**).
- **ViewModels**: retained via the Activity's `viewModelStore` chain; Koin `viewModel` definitions resolve the same instances after rotation.
- **UI state**: `rememberSaveable` usages restore (e.g. `PhysicalPlayScreen.kt:33` `showSensorDots`, NewGameScreen's name fields); anything held in plain `remember` resets.
- **BLE link**: `di/PlatformModule.android.kt:28-58` — `KableBoardAdapter` is a Koin **`single`** (`onClose` fires only at Koin shutdown) bound to `BoardConnection` + `BoardTransport`; `ConnectionViewModel`/`PhysicalPlayViewModel` are `viewModel`-scoped and *reference* the singleton. Mid-game rotation keeps the link connected and the retained `PhysicalPlayViewModel` state hot — no reload flash, no re-scan (`ConnectionScreen.kt:50-69` fires `onConnected` off `ConnectionPhase.Connected`; the S-09 already-connected guard holds). `BlePermissionController.android.kt:28-55` rebinds `LocalContext`/activity-result launcher to the new Activity naturally.

**iOS** — `iosApp/iosApp.xcodeproj/project.pbxproj:309-310`: iPhone = Portrait + LandscapeLeft/Right (no upside-down); iPad = all four. `MainViewController.kt:7` is a plain `ComposeUIViewController { App() }`; `iosApp/iosApp/ContentView.swift:16` applies **`.ignoresSafeArea()`** — Compose receives the full-bleed window and owns inset handling. No recreation semantics on rotation; the Compose tree just gets new constraints.

**Web** — `SmartChessboard/webApp/src/webMain/kotlin/.../Main.kt`: `ComposeViewport { App() }`; `index.html:5` responsive viewport meta. Resize is **continuous** — there is no discrete "orientation", so any adaptive model must be a pure function of current window size (which also makes it trivially testable).

**Conclusion:** rotation/resize robustness is already solved. The change is exclusively about what layout to emit for the given window shape.

### 2. The current adaptive system (what exists to build on)

**The breakpoint** — `<pkg>/presentation/board/ResizableBoardBox.kt:27` `WIDE_SCREEN_MIN_WIDTH = 840.dp` (public), consumed via `rememberIsWideScreen()` (`:47-52`), which reads **`LocalWindowInfo.current.containerSize.width`** — deliberately window-based so Play/PhysicalPlay "share Replay's two-pane threshold" without their own `BoxWithConstraints` (doc comment `:43-46`). **Duplicate:** `ReplayScreen.kt:58` declares its own private `TWO_PANE_MIN_WIDTH = 840.dp` and compares it against **local `BoxWithConstraints.maxWidth`** (`:174-177`). Same number, two declarations, two measurement bases — they agree today only because Scaffold adds no horizontal padding.

**Board sizing authority** — `ResizableBoardBox` (`:64-104`) + `boardSide()` (`:113-130`):

```
usableWidth = availableWidth − reservedWidth          // reservedWidth: adjacent eval bar
widthBound  = isWide ? usableWidth × clampBoardSize(size) : usableWidth
capped      = min(widthBound, BOARD_MAX_SIDE=1000.dp)
bounded     = min(capped, max(viewportHeight − VERTICAL_CHROME=140.dp, MIN_BOARD_SIDE=200.dp))
side        = max(bounded, min(usableWidth, 200.dp))
```

Key facts: the **viewport-height bound applies in both modes** (the board never overflows vertically — at 360 dp height it self-limits to ~220 dp); `VERTICAL_CHROME=140.dp` (`:30`) is a portrait-shaped estimate of "top bar + status + controls" that is wrong for a side-pane landscape layout (correct reservation ≈ 0) and undersized for the real portrait stack; the corner drag `ResizeHandle` (`:138-175`) and the persisted fraction apply only when `isWide`.

**The one true two-pane** — `ReplayScreen.kt`: `LoadedReplay` (`:174-205`) switches `WideReplay`/`NarrowReplay` on local width ≥ 840. `NarrowReplay` (`:209-264`) is the canonical portrait column (PlayerLine → board+eval bar → EvalPanel → TransportControls → MoveList, sections capped at 480 dp). `WideReplay` (`:270-364`): `Row` capped at `containerMax` — `CONTENT_MAX_WIDTH(1200) + (fullWidth − 1200) × enlargement`, `enlargement = (boardSize − 0.6)/(1.0 − 0.6)` — left pane `weight(1f)` (board, auto-fit + handle), right pane `widthIn(max = SIDE_PANEL_MAX_WIDTH = 340.dp)` (EvalPanel + MoveList), **independent `verticalScroll` per pane**. `BoardWithEvalBar` (`:374-403`) shows the `reservedWidth` + `Row(height(IntrinsicSize.Min))` pattern for attaching fixed-width elements to the board without breaking its sizing.

**Tokens & preferences** — `<pkg>/presentation/components/Layout.kt`: `CONTENT_MAX_WIDTH=1200`, `SIDE_PANEL_MAX_WIDTH=340`, `LIST_MAX_WIDTH=720` (the natural home to consolidate the five private `480.dp` constants: `PlayScreen.kt:53`, `PhysicalPlayScreen.kt:61`, `ReplayScreen.kt:55`, `NewGameScreen.kt:38`, `ConnectionScreen.kt:37`). `domain/preferences/BoardSize.kt` (0.4–1.0, default 0.6; doc: "phones always render full-width auto-fit and ignore the stored value" — **already false for ≥840 dp-wide landscape flagships**). `domain/preferences/MoveListMode.kt` `effectiveMoveListMode(override, isWide)` — the pure "width-driven default + persisted override" template (unit-tested). `UiPreferences` + `BoardPreferencesViewModel` (Koin-wired into Replay/Play/PhysicalPlay) is the established plumbing for any new persisted layout preference.

**Board primitives are size-agnostic** — `ChessBoardView.kt:176` root is `Box(modifier.aspectRatio(1f))`; all overlays (lift tints, target marks, S-09 occupancy dots at `fillMaxSize(0.16f)` per cell `:240-249`, best-move arrow, piece-slide) scale with the given square. `ReedDiagnosticsGrid.kt:43` likewise `aspectRatio(1f)` — but it is **not** wrapped in `ResizableBoardBox`, so it has no viewport-height bound (see §3, PhysicalPlay).

**Theming** — `Theme.kt` (`MaterialExpressiveTheme` + `LocalChessColors`), `ChessColors.kt` (12 tokens), `ThemeViewModel`. **No typography/dimension scaling with window size exists** — the only scaling concepts in the app are the board-size fraction and the width caps.

### 3. Per-screen inventory & landscape risk

Fleet reality for "phone landscape": ~640–930 wide × 360–430 dp high. Flagships cross the 840 line (Pixel 8 914×411, iPhone 15 Pro 852×393, 15 Pro Max 932×430); the 360 dp-wide class (780×360) and iPhone SE (667×375) stay below → **two different landscape behaviors ship today**.

| Screen | Root structure | Board? | Adapts today | Phone-landscape risk |
|---|---|---|---|---|
| `App.kt` / Restoring (`:221-229`) | AppTheme → auth gate → Box + single-pane NavDisplay | — | nothing global | none |
| SignIn (`auth/SignInScreen.kt`) | Surface → Box(`safeContentPadding`) → centered Column (no Scaffold) | no | no | **Low** — ~280 dp centered content fits |
| History (`history/HistoryScreen.kt:58-119`) | Scaffold + TopAppBar (3 actions) → LazyColumn capped `LIST_MAX_WIDTH=720`, top-centered | no | 720 cap | **Low** — scrolls, centers |
| NewGame (`newgame/NewGameScreen.kt:65-106`) | Scaffold → Column **no scroll**, fields capped 480 | no | 480 cap | **High (functional)** — at 360 dp heights error text pushes Start off-window, unreachable; IME occludes (no `imePadding`, no scroll) at all heights |
| Play (`play/PlayScreen.kt:114-206`) | Scaffold → Column(widthIn 1200, `verticalScroll`): StatusBanner → ResizableBoardBox → Sync → EndGameSection → MoveList; sections 480 | tap-interactive | `rememberIsWideScreen` (handle+fraction), caps, move-list default | **Medium** — board ~220 dp, 60-70 % width dead, controls below fold |
| PhysicalPlay (`physical/PhysicalPlayScreen.kt:85-243`) | as Play + `keepScreenOn` + BoardMessage banner + SensorDotsToggle + ReedDiagnosticsSection | display-only + live dots | same as Play | **Medium-High** — recovery needs board ↔ grid comparison; grid is an unbounded 480 dp square below a 220 dp board; error banner pushes board down exactly when recovering |
| Connection (`connection/ConnectionScreen.kt:72-96,151-182`) | Scaffold(`keepScreenOn`) → Column(480) **no scroll**; device `LazyColumn` inside | no | 480 cap | **Medium (functional)** — trailing "Forget saved board" TextButton (`:179-181`) unreachable when list long |
| Replay (`replay/ReplayScreen.kt:174-364`) | Scaffold → BoxWithConstraints → Narrow (scroll column) / Wide (two-pane) | yes + EvalBar | **real two-pane @840 local** | **Medium** below 840 (board 220 dp + transport below fold @360); near-OK ≥840 (two-pane at phone height is the closest thing to a correct landscape layout in the app) |
| PromotionPicker (dialog) | Row of 4×56 dp pieces | — | — | Low (~150 dp) |
| EndGamePicker (dialog, `play/EndGamePicker.kt`) | Column widthIn(360), **no scroll**, ~320 dp tall | — | — | Med-Low — borderline on 360 dp-high windows |

Web note: `supportsPhysicalBoard` gates the chip/routes, and per FR-020 web never shows Connection/PhysicalPlay — their landscape work is mobile-only by definition. The remaining screens' landscape fixes automatically cover "short and wide" browser windows (same code path, continuous resize).

UX quirk worth a decision: `effectiveMoveListMode` defaults flip INLINE↔TABLE **on rotation** for ≥840 dp-wide phones (window-width-driven) unless the user has set an override.

### 4. Insets posture

- Android: `enableEdgeToEdge()` (`MainActivity.kt:12`); all six nav screens use M3 `Scaffold` + `Box(fillMaxSize().padding(padding))` and rely on Scaffold's default inset behavior.
- The **only** manual inset call in the app is `SignInScreen.kt:69` `safeContentPadding()` (it has no Scaffold). `RestoringScreen` (Surface + spinner) has none.
- **No `imePadding()` anywhere** (grep-confirmed) despite text fields in NewGame; no `displayCutout` special-casing.
- iOS hands Compose the full-bleed window (`.ignoresSafeArea()`, `ContentView.swift:16`).
- Landscape exposes the camera cutout on a **long edge** and the iOS home indicator on the bottom edge of a short window — current handling is unaudited for that shape; the plan needs an explicit inset audit (which insets Scaffold's defaults actually cover at the pinned M3 version vs what needs `WindowInsets.displayCutout`/`safeDrawing`).

### 5. Dependencies & adaptive-API availability at the pins

`SmartChessboard/gradle/libs.versions.toml`: `composeMultiplatform = "1.11.1"`, `material3 = "1.11.0-alpha07"` (Expressive train), `navigation3 = "1.1.1"`, `androidx-lifecycle = "2.11.0-beta01"`, Koin, Kable 0.43.1. **Not declared:** `material3-window-size-class`, `material3-adaptive` (any flavor), `androidx.window`.

- The shared code's only window primitive in use is **`LocalWindowInfo.current.containerSize`** (`ResizableBoardBox.kt:49,75`) — px-based, works on all three targets, already proven.
- JetBrains publishes multiplatform artifacts for `material3-window-size-class` (follows the material3 version train) and `material3-adaptive` (`org.jetbrains.compose.material3.adaptive:adaptive*`). **Compatibility with the alpha material3 pin (`1.11.0-alpha07`) is unverified** — if the plan wants library window-size classes, verify by adding the dependency and compiling all three targets (per `lessons.md` discipline: compiler verification over web research; note the wasm yarn-lock lesson does not apply — these are Maven, not npm, artifacts).
- Zero-dependency alternative (consistent with the existing pattern): extend the `rememberIsWideScreen()` seam into a small hand-rolled window-class function of **width × height** (e.g. compact-height < ~480 dp ⇒ landscape-phone layout), keeping 840 as the width class boundary. All adaptivity in the app is already hand-rolled `BoxWithConstraints`/`LocalWindowInfo`; a 20-line pure function + unit tests matches `effectiveMoveListMode`'s precedent.

### 6. Product requirements & historical decisions

- **PRD** (`context/foundation/prd.md`): FR-019 (mobile core flows, must-have) and FR-020 (web digital subset, nice-to-have; physical/BLE intentionally excluded from web — decision at `:231-232`) say nothing about orientation, landscape, or tablets. NFR: latest two iOS/Android majors. **No FR forces this change — it is a user-initiated UX improvement**, so the change's own success criteria will have to define "good landscape" (no FR to point at).
- **The deferral this change redeems**: `context/changes/replay-seeded-games/plan.md:182-186` — wide-screen guardrail shipped, "app-wide adaptive follow-up (WindowSizeClass, multi-pane) explicitly deferred".
- **The wide half already shipped**: `context/changes/ui-theming-and-scaling/plan.md` Phases 2 (`:157-205` ResizableBoardBox, 840 threshold, persisted `ui.boardSize`), 6 (`:318-372` content max-width, two-pane Replay, move-list toggle, History cap) and 7 (`:405-441` adaptive margins / containerMax interpolation). Status `impl_reviewed`, awaiting `/10x-archive`. Its `manual-verification.md` carries a **deferred 3-target manual gate** — this change will accrue more manual-verification debt of exactly the same shape (visual checks per target/orientation); the plan should decide whether to fold both into one manual pass.
- **Physical screens were deliberately single-column**: `context/changes/reject-recover-diagnostics/plan.md:118` — "No two-pane / 840.dp adaptive layout — phone-first, single column (physical is mobile-only)". A landscape layout for PhysicalPlay deliberately revisits that decision (it was scoped-out, not rejected).
- **"Orientation" in older changes means board flip**, not device orientation (`digital-pass-and-play/plan.md:206`; `ChessBoardView(orientation)` parameter). Terminology to keep distinct in the plan.
- **S-09 live matrix overlay** renders as per-square corner dots *inside* `ChessBoardView` (`PhysicalPlayScreen.kt:200-211`) — it scales with the board automatically and imposes no landscape constraint; at-rest mismatch tinting lives in `ReedDiagnosticsGrid`.

### 7. Collisions & sequencing

- **`delete-game-from-history`** (in flight, p1 merged on main `9368d68`): HistoryScreen kebab menu + dialog — row-level affordance, no layout restructure. Low collision; if both land, a trivial merge in HistoryScreen imports at worst.
- **S-10 `ble-connectivity-robustness`** (proposed, research seeded): multi-device BLE testing, no layout code. No collision; note S-10's device matrix (several Android + iOS devices) is also the natural fleet for landscape manual verification — potential shared manual pass.
- **`ui-theming-and-scaling`**: `impl_reviewed`, awaiting archive — no code in flight.
- This change's worktree is at `main@9368d68` (fast-forwarded 2026-07-03), which already includes delete-game p1.

### 8. Contract surfaces & testing

- **Load-bearing names** (`docs/reference/contract-surfaces.md`): routes `SignInKey/HistoryKey/NewGameKey/PlayKey/ReplayKey/ConnectionKey/PhysicalPlayKey` (any new key must also be registered in `navSavedStateConfiguration`, `Routes.kt:59-72`); composables `ChessBoardView`, `MoveList`, `EvalBar`/`EvalPanel`, `ReedDiagnosticsGrid`, screen composables; ViewModels incl. `BoardPreferencesViewModel`, `ThemeViewModel`; `UiPreferences`. An adaptive refactor is **internal to screen composables** — no renames needed.
- **Previews are signature-coupled**: `shared/src/androidMain/.../replay/ReplayScreenPreviews.kt` calls `LoadedReplay` directly; `ChessBoardPreviews.kt` similar. Changing internal composable signatures touches them (Android-only, cheap).
- **Test surface today**: `ChessBoardGeometryTest`, `BoardMoveAnimationTest` (pure logic), `MoveListModeTest`, `BoardSizeTest` (pure preference functions — the precedent for unit-testing a window-class function). **Zero tests for orientation/resize/layout**; `runComposeUiTest` is the sanctioned cross-platform UI test tool (`tech-stack.md:86-88`) but no UI tests exist yet. Manual verification per target remains the realistic gate for visual layout (see the ui-theming deferred-gate note in §6).

## Code References

- `SmartChessboard/androidApp/src/main/AndroidManifest.xml:35-55` — no orientation lock / no configChanges (activity recreates)
- `SmartChessboard/androidApp/src/main/kotlin/.../MainActivity.kt:11-18` — `enableEdgeToEdge` + `App()`
- `iosApp/iosApp.xcodeproj/project.pbxproj:309-310` — iPhone/iPad supported orientations
- `iosApp/iosApp/ContentView.swift:16` — `.ignoresSafeArea()` (Compose owns insets)
- `SmartChessboard/webApp/src/webMain/kotlin/.../Main.kt` — `ComposeViewport` (continuous resize)
- `<pkg>/App.kt:52-104,221-229` — theme root, auth gate, NavDisplay + decorators, single-pane host
- `<pkg>/presentation/navigation/Routes.kt:59-72` — explicit polymorphic NavKey registration
- `<pkg>/presentation/board/ResizableBoardBox.kt:27,30,43-52,64-130,138-175` — 840 threshold, `VERTICAL_CHROME`, `rememberIsWideScreen`, `boardSide()`, resize handle
- `<pkg>/presentation/replay/ReplayScreen.kt:55-58,174-205,209-264,270-364,374-403` — 480/840 duplicates, two-pane switch, Narrow/Wide layouts, `BoardWithEvalBar` reservedWidth pattern
- `<pkg>/presentation/play/PlayScreen.kt:53,69,114-206` — canonical portrait board column
- `<pkg>/presentation/physical/PhysicalPlayScreen.kt:33,61,85-107,180-243,271-337,346-375,480-493` — keepScreenOn, BoardMessage, live-dots board, diagnostics grid section, sensor toggle
- `<pkg>/presentation/connection/ConnectionScreen.kt:37,50-69,86-96,151-182,207-253` — connected guard, non-scrolling column, device list, unreachable Forget button
- `<pkg>/presentation/newgame/NewGameScreen.kt:38,65-106` — non-scrolling form, no imePadding
- `<pkg>/presentation/history/HistoryScreen.kt:58-119` — LazyColumn + 720 cap
- `<pkg>/presentation/auth/SignInScreen.kt:65-77,127-132` — only `safeContentPadding` in the app
- `<pkg>/presentation/board/ChessBoardView.kt:176,240-281` — `aspectRatio(1f)`, scale-with-cell overlays
- `<pkg>/presentation/board/ReedDiagnosticsGrid.kt:43` — square grid with no viewport-height bound
- `<pkg>/presentation/components/Layout.kt` — `CONTENT_MAX_WIDTH`/`SIDE_PANEL_MAX_WIDTH`/`LIST_MAX_WIDTH`
- `<pkg>/presentation/components/MoveList.kt` — inline `FlowRow` / table modes (orientation-neutral)
- `<pkg>/domain/preferences/BoardSize.kt`, `MoveListMode.kt`, `UiPreferences.kt` — persisted layout preferences + pure default functions
- `<pkg>/di/PlatformModule.android.kt:28-58` — `KableBoardAdapter` as Koin `single` (BLE survives rotation)
- `SmartChessboard/gradle/libs.versions.toml` — CMP 1.11.1 / material3 1.11.0-alpha07 / nav3 1.1.1; no adaptive artifacts

## Architecture Insights

1. **Width-only adaptivity is the root cause.** Every current decision (two-pane, resize handle, move-list default) keys off one 840 dp *width* line. Landscape phones are defined by **compact height** (~360–430 dp), which no code observes. The natural model for "one coherent system" is a two-axis window classification (width class × height class) as a pure function — `isWide` becomes one derived value of it, preserving all existing call sites.
2. **The board is solved; the chrome is not.** `boardSide()` already guarantees the board fits any window. A landscape layout is therefore a *rearrangement* problem: put the existing 480 dp-capped control column **beside** the board (Replay's `WideReplay` is the in-repo proof of that pattern, including independent per-pane scrolling and `reservedWidth`), and replace the portrait-shaped `VERTICAL_CHROME=140.dp` with a per-layout reservation.
3. **Constant consolidation is a prerequisite, not a nicety.** 840 dp exists twice with different measurement bases (window vs local constraints — they can silently diverge if any horizontal padding is ever added above `LoadedReplay`), and 480 dp exists five times privately. `components/Layout.kt` is the established home; a breakpoint/token consolidation phase would make the adaptive change reviewable.
4. **Scroll + insets are the cheap, high-value fixes.** NewGame/Connection just need `verticalScroll` (+ `imePadding` for NewGame); EndGamePicker needs a scroll or height cap; a cutout/inset audit covers the long-edge camera in landscape. These fix real usability bugs even before any two-pane work, and are independently shippable.
5. **Preferences plumbing is ready for layout prefs.** If the plan adds any user-facing layout choice (e.g. which side the panel docks to), `UiPreferences` + `BoardPreferencesViewModel` + the `effectiveMoveListMode` override pattern is the established, tested shape — no new architecture.
6. **MVVM/MVI boundaries are untouched.** Adaptive layout is a view-layer concern; no ViewModel contracts change. (Physical/Connection MVI stays as-is per lessons.md.)

## Historical Context (from prior changes)

- `context/changes/replay-seeded-games/plan.md:173-186` — adaptive guardrail + **explicit deferral of app-wide WindowSizeClass/multi-pane** (the debt this change repays)
- `context/changes/ui-theming-and-scaling/plan.md:157-205,318-372,405-441` — the shipped wide-screen system (ResizableBoardBox, 840, two-pane, margins); `manual-verification.md` — deferred 3-target visual gate (shared-debt candidate)
- `context/changes/reject-recover-diagnostics/plan.md:118` — physical screens deliberately single-column phone-first (decision now revisited)
- `context/changes/real-board-over-ble/plan.md:44-53,74-88,319-384` — live dots inside the board (no separate overlay), MVI connection screen, foreground-first posture
- `context/changes/digital-pass-and-play/plan.md:206` — "orientation" there = board flip (terminology)
- `context/changes/delete-game-from-history/plan.md` — in-flight HistoryScreen row affordance (no layout collision)
- `context/foundation/lessons.md` — relevant priors: refresh-on-return must be push-driven (rotation-safe already); Nav3 committed + explicit serializer registration; MVVM default/MVI justified; wasm `Throwable` catch discipline (untouched by layout work)

## Related Research

- `context/changes/real-board-over-ble/research.md` — BoardConnection seam, connection UX (background for PhysicalPlay/Connection screens)
- `context/changes/ble-connectivity-robustness/research.md` — S-10 device matrix (candidate shared fleet for landscape manual verification)

## Open Questions

1. **Window-class mechanism**: extend the hand-rolled `rememberIsWideScreen` seam with a height axis (zero deps), or adopt `material3-window-size-class`/`material3-adaptive` multiplatform artifacts? If the latter — verify artifact/version compatibility with material3 `1.11.0-alpha07` by add-and-compile on all three targets before committing the plan to it.
2. **Landscape layout shape for the three board screens**: generalize `WideReplay`'s two-pane (board `weight(1f)` left + 340–480 dp control pane right) at *compact height regardless of the 840 width line*? Where do StatusBanner/BoardMessage go in that shape (top of right pane vs above the row)?
3. **PhysicalPlay recovery in landscape**: should `ReedDiagnosticsGrid` sit *beside* the live board (side-by-side comparison finally possible in landscape) and gain a viewport-height bound (wrap in `ResizableBoardBox` or equivalent)?
4. **Top bar in compact height**: keep the 64 dp `TopAppBar` (15–18 % of a landscape phone's height), or compact/auto-hide it on board screens?
5. **`VERTICAL_CHROME` model**: replace the fixed 140 dp with per-layout reservations (≈0 in side-pane mode) or actual measurement?
6. **`BoardSize`/resize-handle semantics on ≥840 dp-wide landscape phones**: keep the fraction active (current accidental behavior), or gate the handle on height class too and re-document the `BoardSize.kt` contract?
7. **`effectiveMoveListMode` flip on rotation** (INLINE↔TABLE default change for wide phones): acceptable, or should the default key off a stabler signal (e.g. width class of the *side panel* rather than the window)?
8. **Dialog policy**: EndGamePicker at ~320 dp with no scroll — add internal scroll, or cap+scroll all dialogs as a rule?
9. **Success criteria & manual gate**: with no PRD FR to anchor to, what is the acceptance checklist (per screen × orientation × target), and does it fold with the deferred ui-theming 3-target gate and/or the S-10 device matrix into one manual pass?
10. **Tests**: is a pure window-class function + unit tests (à la `MoveListModeTest`) sufficient for MVP, or should the plan introduce the first `runComposeUiTest` layout smoke (e.g. "landscape Play shows board and controls without scrolling past the fold")?

## Follow-up Research 2026-07-03 (web: adaptive best practices, Nav3 scenes, holistic approach)

**Method note.** Three web-research agents ran in two rounds; both rounds were cut off mid-synthesis (host restart, then session limit), so their raw reports were lost. Their source trails and interim notes were salvaged from transcripts, and the load-bearing facts below were then **re-verified directly by this session**: the whole version matrix against `repo1.maven.org` (metadata + `.module` files), and API shapes against the locally installed Google skill docs (`.claude/skills/adaptive/`, `.claude/skills/navigation-3/`, `.claude/skills/edge-to-edge/`). Items that could not be re-verified first-hand are explicitly marked **UNVERIFIED**. All version facts stamped (checked 2026-07-03).

### F1. Verified dependency/version matrix (Maven Central, checked 2026-07-03)

| Artifact | Our pin | Latest on Maven Central | Multiplatform targets | Compatibility notes |
|---|---|---|---|---|
| `org.jetbrains.compose.*` (core: `ui`, `foundation-layout`) | 1.11.1 | **1.12.0-beta01** | full KMP | current stable train is ours |
| `org.jetbrains.compose.material3:material3` | 1.11.0-alpha07 | **1.12.0-alpha03** | full KMP | Expressive train; alphas only |
| `org.jetbrains.compose.material3:material3-window-size-class` | — | **1.12.0-alpha03** | android, desktop, iosArm64, iosSimulatorArm64, js, macosArm64, **wasmJs** (verified from `.module`) | **exists at exactly `1.11.0-alpha07`** = our material3 pin; no cross-deps on other JetBrains groups (self-contained enum-based API) |
| `org.jetbrains.compose.material3:material3-adaptive-navigation-suite` | — | **1.12.0-alpha03** | (same train) | exists at `1.11.0-alpha07`; nav bar↔rail auto-switching |
| `org.jetbrains.compose.material3.adaptive:adaptive` | — | **1.3.0-beta02** | android, desktop, iosArm64, iosSimulatorArm64, js, macosArm64, **wasmJs** (verified) | depends on `org.jetbrains.androidx.window:window-core:1.5.0` → provides `currentWindowAdaptiveInfo()` + modern `WindowSizeClass` |
| `org.jetbrains.compose.material3.adaptive:adaptive-layout` / `adaptive-navigation` | — | **1.3.0-beta02** | (same family) | `ListDetailPaneScaffold`, `SupportingPaneScaffold` |
| `org.jetbrains.compose.material3.adaptive:adaptive-navigation3` | — | **1.3.0-beta02** | android, desktop, iosArm64, iosSimulatorArm64, js, macosArm64, **wasmJs** (verified) | depends on `navigation3-ui:1.1.0` + `adaptive-navigation:1.3.0-beta02` → **compatible with our nav3 1.1.1** (Gradle resolves upward); ships `ListDetailSceneStrategy` |
| `org.jetbrains.androidx.navigation3:navigation3-ui` | 1.1.1 | **1.2.0-alpha02** | full KMP | 1.1.1 = latest stable |
| `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` | 2.11.0-beta01 | **2.11.0-rc01** | full KMP | one-version-per-group rule applies |
| `org.jetbrains.androidx.window:window-core` | — | **1.5.1** | KMP (per adaptive's dependency) | home of modern `WindowSizeClass` (width **and height** breakpoints) |
| `com.github.terrakok:navigation3-browser` | 1.1.0 | **1.1.0** | wasmJs | already at latest |
| Jetpack `androidx.compose.foundation:foundation-layout` (Android-only reference) | n/a | docs pin `1.12.0-alpha03` for Grid/FlexBox | Android | see F3 for CMP availability |

**Correction/confirmation of §5:** the initial research left multiplatform availability of the adaptive stack unverified. It is now **confirmed available**: the full `material3.adaptive` family and `material3-window-size-class` ship all our targets **including wasmJs**, and `material3-window-size-class` exists at *exactly* our material3 pin. The zero-dependency hand-rolled option remains viable but is no longer forced.

### F2. Two window-classification APIs exist — pick the modern one

- **`androidx.window.core.layout.WindowSizeClass`** (KMP `window-core` 1.5.x; pulled in by `adaptive` 1.3.0-beta02): breakpoint-query API — `isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND=600)`, `isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND=840)`, and **height breakpoints** (`HEIGHT_DP_MEDIUM_LOWER_BOUND=480`, `HEIGHT_DP_EXPANDED_LOWER_BOUND=900`). This is the API the Nav3 scene recipes and `currentWindowAdaptiveInfo()` use. Hard-verified from commonMain source by the interrupted agent; API surface confirmed by the nav3-recipes code in the local skill (`scenes-twopane.md` imports `androidx.window.core.layout.WindowSizeClass...WIDTH_DP_MEDIUM_LOWER_BOUND`).
- **`material3-window-size-class`** (enum-based `WindowWidthSizeClass`/`WindowHeightSizeClass`, `calculateWindowSizeClass()`): older shape, self-contained, available at exactly our material3 pin.
- **Height classes are official**: M3 window size classes define a height axis; a landscape phone is width-`medium/expanded` × **height-`compact` (<480 dp)** — precisely the dead-zone dimension §2/§3 identified as missing. (M3 docs trail: m3.material.io "applying layout / window size classes"; page fetch was in the interrupted agent's trail — treat exact prose as UNVERIFIED, the 480/900 bounds themselves are from the API constants.)

**Implication:** our `rememberIsWideScreen()` (`window width ≥ 840`) is exactly `isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)`; the landscape gap is one missing conjunction: `!isHeightAtLeastBreakpoint(HEIGHT_DP_MEDIUM_LOWER_BOUND)` (height-compact). Adopting the official type replaces bespoke numerology with named platform breakpoints at near-zero migration cost.

### F3. New Compose layout APIs (MediaQuery / Grid / FlexBox) — real, but not MVP-load-bearing

From the locally installed Google `adaptive` skill references (+ interrupted-agent source notes):

- **`mediaQuery { … }` / `derivedMediaQuery { … }`** (`androidx.compose.ui`): composable predicate over `UiMediaScope` — `windowWidth`/`windowHeight` (Dp), `windowPosture` (foldables), `pointerPrecision`, `keyboardKind`, `hasCamera`, `viewingDistance`. **Experimental**, opt-in via `ComposeUiFlags.isMediaQueryIntegrationEnabled = true`. Interrupted agent verified the flag + APIs exist in the JetBrains compose core **v1.11.1 tag in commonMain** (source level); exposure in the *published* JB 1.11.1 artifacts **UNVERIFIED** — compile-check if pursued.
- **`Grid`** and **`FlexBox`** (`androidx.compose.foundation.layout`): CSS-grid/flexbox-style containers (`column(100.dp)`/`row(…)` config; `direction`, `wrap`, `gap`, `Modifier.flex { grow(1f) }`). Google docs pin **Jetpack compose 1.12.0-alpha03**; agent notes place them in the JB v1.11.1 source tag (commonMain), but presence in the published JB `foundation-layout:1.11.1` artifact is **UNVERIFIED** (JB `1.12.0-beta01` is the safer assumption).
- **Relevance verdict:** none of our shapes needs Grid/FlexBox (two-pane `Row` + existing `FlowRow` move list suffice); `mediaQuery` is philosophically exactly our width×height predicate but experimental + flag-gated → **note as future migration target, do not build MVP on it**. A pure window-class function today maps 1:1 onto `derivedMediaQuery` later.

### F4. Nav3 Scenes — available at our pin; verdicts per shape

From the local `navigation-3` skill recipes (Google-sourced, incl. full `TwoPaneSceneStrategy` code) + Maven verification:

- **`Scene`/`SceneStrategy` is nav3 core API** (`androidx.navigation3.scene.*`): a strategy inspects the back stack tail and may *claim* the top N entries into one multi-entry scene (`entries`, `previousEntries`, metadata-driven opt-in per entry, explicit back semantics). Available in principle at our `navigation3-ui 1.1.1` with **zero new dependencies**; the recipe uses `NavMetadataKey`/`metadata {}` helpers and `currentWindowAdaptiveInfoV2()` — exact helper availability at the JB 1.1.1 pin **UNVERIFIED → compile-check** (fallbacks: plain metadata map / own `LocalWindowInfo`).
- **`ListDetailSceneStrategy`** ships **multiplatform** in `adaptive-navigation3:1.3.0-beta02` (wasmJs+iOS verified; depends on nav3 1.1.0 → resolves with our 1.1.1).
- **Verdicts for our shapes:**
  - **(a) History→Replay list-detail (tablet/wide web):** technically feasible today. BUT: it restructures the app's one NavDisplay, and the wasm browser-history binding maps the *top entry* to the URL fragment (terrakok `HierarchicalBrowserNavigation`) — semantics with a two-entry scene are undefined territory (agent died before resolving YouTrack CMP-7646's relevance — **UNVERIFIED**). Recommendation: **out of scope for this change**; optional post-MVP spike.
  - **(b) Board screens at compact height (the core of this change):** a scene combines *multiple back-stack entries*; board+controls is **one** destination's internal arrangement. Scenes are the wrong tool — **in-screen adaptive layout is the correct, ecosystem-standard mechanism** (the recipes themselves switch on window class inside content for this case).
  - **(c) PromotionPicker/EndGamePicker:** stay plain Compose `Dialog`s; dialog scenes add nav-stack ceremony with no adaptivity payoff for two small pickers.

### F5. Community / guidance patterns (salvaged trail + local skills; partially UNVERIFIED)

- **Window class at the root, local constraints for components**: reference apps (NowInAndroid trail) compute adaptive info once at the app root and pass it down (parameter or CompositionLocal); `BoxWithConstraints` stays for *component-level* decisions (its subcomposition cost and measure-time-only constraints are the standard argument — RevenueCat SubcomposeLayout internals in trail). Our duplicated 840 measured two ways (§2) is precisely the anti-pattern this rule prevents.
- **Insets (from the local `edge-to-edge` skill + our audit)**: on M3, `Scaffold` handles system-bar insets for its slots but **IME and long-edge display cutout need explicit handling** on affected screens (`imePadding` on forms; `WindowInsets.displayCutout`/`safeDrawing` audit for landscape). Exact default coverage of `ScaffoldDefaults.contentWindowInsets` at material3 1.11.0-alpha07: **verify at plan time** (one-line source check).
- **Typography/dimension scaling by window class**: not a mainstream Compose pattern; guidance favors *layout* changes (panes, max-widths) over global type scaling — consistent with our theme system having no dimension tokens. (OPINION, low stakes.)

### F6. Holistic recommendation (answer to "is there a better overall approach?")

The evidence points to **evolution, not rewrite** — but with one deliberate foundation swap:

1. **Adopt the official window-classification primitive** — `window-core`'s `WindowSizeClass` (either via tiny direct dependency or transitively via `material3.adaptive:adaptive` for `currentWindowAdaptiveInfo()`). Compute **once at the App.kt root**, expose via one CompositionLocal (e.g. `LocalWindowSizeClass`), and derive the app's layout policy from **width class × height class**. `rememberIsWideScreen()` becomes a one-line derivation during migration, then call sites move to the richer policy. This kills the duplicated 840, adds the missing height axis with official 480/900 bounds, and aligns us with where Nav3 scenes and `mediaQuery` are heading.
2. **Generalize the proven in-screen two-pane** (WideReplay) into one shared slot-based scaffold for board screens (e.g. `BoardScreenScaffold(board = {...}, panel = {...}, banner = {...})`) whose arrangement (stack vs side-pane, chrome reservation, panel width 340–480) is a pure function of the window classes. Landscape phone = height-compact ⇒ side-pane; tablet/wide web = width-expanded ⇒ same mechanism. One code path for all three targets — this is the "max flexibility" ask with the least new machinery, and it reuses `ResizableBoardBox`/`reservedWidth` as-is.
3. **Consolidate tokens** into `components/Layout.kt` (single threshold source, one 480 constant, per-layout chrome reservation replacing `VERTICAL_CHROME=140`).
4. **Fix the functional bugs regardless of framework** (NewGame/Connection scroll + `imePadding`, EndGamePicker height, cutout audit) — independently shippable.
5. **Explicitly defer**: `ListDetailPaneScaffold`/`SupportingPaneScaffold` (beta; our board screens aren't list-detail; visual opinions differ from our custom panes), Nav3 scenes for History→Replay (wasm browser-history spike first), `mediaQuery` (experimental flag).

Rejected alternatives: *full material3-adaptive canonical scaffolds* (fits list screens, not a chessboard+controls surface; beta churn risk on an alpha material3 train), *Nav3-scene-centric restructure* (solves a problem we mostly don't have; wasm URL semantics unproven), *pure status-quo extension with bespoke numbers* (works, but re-invents the now-available official primitive and stays misaligned with recipes/tooling).

### Updated Open Questions

- OQ1 is now a narrower decision: `window-core` directly vs via `material3.adaptive:adaptive` (for `currentWindowAdaptiveInfo()`) vs enum-based `material3-window-size-class@1.11.0-alpha07` — settle with a 15-minute add-and-compile on all three targets (wasm included).
- NEW: does `ScaffoldDefaults.contentWindowInsets` at material3 1.11.0-alpha07 include `displayCutout`? (one source check; drives the landscape cutout fix scope)
- NEW: published-artifact availability of `mediaQuery`/`Grid`/`FlexBox` in JB 1.11.1 vs 1.12.0-beta01 — only relevant if the plan wants them (recommendation: it shouldn't, for MVP).
- OQ2/OQ4/OQ5 unchanged but now framed by F6's slot-scaffold + height-compact policy; OQ9/OQ10 unchanged.
