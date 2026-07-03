# Manual verification — mobile-landscape-layout

Device-fleet manual checks accumulate here per phase. Per the plan's Testing Strategy,
phases run consecutively with manual rows deferred to **one end-of-slice acceptance pass**
on the owner fleet (Android phone + iPhone + web resize; tablet/iPad if at hand).

Status legend: `[ ]` pending, `[x]` confirmed on the fleet.

## Phase 1: Functional fixes

Automated gate: **passed** (all four Gradle targets green + ktlint clean).

Inset audit finding (plan Phase 1 item #4, recorded once):
- `MainActivity` calls `enableEdgeToEdge()`, so Compose receives the `systemBars`, `ime`
  and `displayCutout` insets. The manifest neither locks `screenOrientation` nor overrides
  `windowSoftInputMode`, so landscape is allowed and the IME inset animates into Compose.
- `ScaffoldDefaults.contentWindowInsets` at material3 `1.11.0-alpha07` resolves to
  `WindowInsets.systemBars` (status + navigation bars; the iOS home-indicator arrives via
  navigation bars). It does **not** include `ime` or `displayCutout`.
- Only genuinely-missing inset that causes a bug today: **IME on the NewGame form** — fixed
  with `imePadding()`. Connection/EndGamePicker have no text fields, so no IME gap there.
- `displayCutout`: NewGame and Connection content is a centered, 480 dp width-capped column,
  so it clears the long-edge landscape cutout by construction. The leading-edge action rail
  (Phase 3) will own `displayCutout` consumption when it lands. No blanket `safeDrawing`
  added anywhere; SignIn keeps its `safeContentPadding()`.

Manual checks (deferred to the end-of-slice pass):

- [ ] 1.3 NewGame landscape: with the duplicate-name error visible, Start is reachable;
      focusing a field with the IME open never hides the focused field (Android + iOS)
- [ ] 1.4 Connection with a long device list at phone height: "Forget saved board" reachable
- [ ] 1.5 EndGamePicker at ~360 dp height: all result options + confirm reachable
- [ ] 1.6 Cutout device, both landscape rotations: nothing under the cutout; portrait unregressed

## Phase 2: Window classification foundation + token consolidation

Automated gate: **passed** (all four Gradle targets green + ktlint clean; `AdaptiveLayoutTest`
11/11 and `MoveListModeTest` 3/3 on host **and** iOS simulator; token greps clean).

Dependency-spike outcome (plan Phase 2 item #1, decision ladder):
- **Rung 1 FAILED**: `org.jetbrains.compose.material3.adaptive:adaptive:1.3.0-beta02` — its
  Android variant requires **AGP >= 9.1.0** (project pins 9.0.1) and flags compileSdk 37
  (project pins 36). Bumping AGP is out of this change's scope.
- **Rung 2 WON**: `org.jetbrains.androidx.window:window-core:1.5.1` — type-only dependency;
  resolves and compiles on all three targets. API surface verified from the artifact's sources:
  public `WindowSizeClass(minWidthDp, minHeightDp)` constructor,
  `isWidthAtLeastBreakpoint`/`isHeightAtLeastBreakpoint`, official constants
  (`WIDTH_DP_EXPANDED_LOWER_BOUND=840`, `HEIGHT_DP_MEDIUM_LOWER_BOUND=480`), and
  `BREAKPOINTS_V1.computeWindowSizeClass(...)` selectors.
- The class is computed at the App root from `LocalWindowInfo.containerSize` (bucketed via
  `BREAKPOINTS_V1`) and exposed through `LocalWindowSizeClass` — exactly the rung-2 shape the
  plan anticipated.

Manual checks (deferred to the end-of-slice pass):

- [ ] 2.6 Spot-check (Android): visuals unchanged — phase is behavior-neutral

## Phase 3: Adaptive chrome — left rail at compact height

Automated gate: **passed** (all four Gradle targets green + ktlint clean on `src/`; the only
ktlint hits sit in `shared/build/generated/` — BuildKonfig + compose resource generator output,
outside the gate).

Implementation notes for the pass:
- `AdaptiveScaffold` (new, `presentation/components/`) is the one chrome authority: M3
  `Scaffold` + `TopAppBar` normally; at compact height a left vertical rail with Back on top,
  then the screen's actions, title dropped. All six screens migrated; signatures unchanged.
- The rail consumes `displayCutout` + `systemBars` on its leading edge; the content pane keeps
  the trailing-edge + vertical insets (covers the other rotation's cutout). Touch targets rely
  on M3's 48 dp minimum-interactive-size enforcement.
- `keepScreenOn` stayed per-screen (PhysicalPlay + Connection pass it via the modifier slot).

Manual checks (deferred to the end-of-slice pass):

- [ ] 3.2 All six screens at compact height show the rail (Back + actions, no title);
      portrait keeps the TopAppBar
- [ ] 3.3 Rail cutout-safe in both landscape rotations (Android cutout device + iPhone)
- [ ] 3.4 Rail targets tappable (>= 48 dp); web short window shows rail, tall window restores
      the top bar (continuous resize)

## Phase 4: BoardScreenScaffold + Play adoption

Automated gate: **passed** (all four Gradle targets green — new `BoardScreenScaffoldTest` 6/6
runs on host + iOS simulator + wasm; ktlint clean on `src/`, generated files excepted as before).

Implementation notes for the pass:
- `BoardScreenScaffold` (new, `presentation/components/`) is the arrangement authority:
  side-pane (board beside a scrolling panel) at compact height or expanded width, the portrait
  column otherwise. The banner renders in a **fixed-height reserved slot** (`BANNER_SLOT_HEIGHT`,
  56 dp) — laid out even when empty, pinned to the top of the panel in side-pane — so a banner
  appearing/disappearing never moves the board. Note for portrait: Play's board sits ~30 dp
  lower than before (the slot reserves the emphasized banner's height permanently); that is the
  intended no-jump trade-off.
- Panel width is the pure `sidePanelWidth` function: the board is primary (fills its height
  budget), the panel takes the leftover clamped to 340..480 dp, never crushing the board below
  200 dp.
- Board height budget in side-pane is now **exact**: the pane's bounded constraint (insets and
  chrome consumed upstream) instead of the window-minus-estimate path, which remains only for
  scrollable columns. Play gates the resize handle on `boardResizeEnabled()` and derives the
  move-list default from the container (`boardArrangement == SidePane`).

Manual checks (deferred to the end-of-slice pass):

- [ ] 4.2 Play landscape phone: rail + height-filling board + panel; no below-fold primaries
- [ ] 4.3 Banner appear/disappear never moves the board (portrait + landscape)
- [ ] 4.4 Resize handle: absent at compact height; present + persisted on wide web/desktop;
      portrait auto-fit unchanged
- [ ] 4.5 Move-list defaults per container (INLINE portrait / TABLE panel); override wins both
      ways
