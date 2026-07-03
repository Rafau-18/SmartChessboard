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

## Phase 5: PhysicalPlay adoption (recovery side-by-side)

Automated gate: **passed** (all four Gradle targets green — `ReedDiagnosticsGridTest` extended
with `diagnosticsGridSide` bounds, runs on host + iOS simulator + wasm; ktlint clean).

Implementation notes for the pass:
- PhysicalPlay renders through `BoardScreenScaffold` with a screen-specific banner slot
  (112 dp): **BoardMessage when present, else StatusBanner** — one fixed slot, one occupant, so
  neither appearing nor disappearing ever moves the board. The recovery message outranks the
  turn indicator (during a pause/rejection the instruction is what the player needs); message
  text above ~2 lines scrolls internally (40 dp cap) instead of growing the slot.
- The diagnostics grid is height-bound everywhere (`diagnosticsGridSide`: bounded slot →
  its height; scrolling column → viewport less chrome; 120 dp readability floor). In the side
  panel the diagnostics section renders **first** (above the sensor-dots toggle) with a
  pane-specific 180 dp chrome so the whole grid sits above the fold beside the board; portrait
  keeps today's order (toggle, then grid) with the board's 140 dp column chrome.
- The grid caption ("Reed diagnostics — …" + Hide) moved **below** the grid in both
  arrangements — it no longer pushes the grid down at compact height.
- `keepScreenOn` (AdaptiveScaffold modifier), the live sensor-dots overlay, and the at-rest
  mismatch tinting are untouched; only where the sections render changed.

Manual checks (deferred to the end-of-slice pass):

- [ ] 5.2 Landscape recovery: diagnostics grid beside the live board, both fully visible
      without scrolling
- [ ] 5.3 Portrait: grid height-bound (no overflow at low heights)
- [ ] 5.4 BoardMessage no-jump; long messages stay inside the bounded slot; keepScreenOn +
      sensor dots intact
- [ ] 5.5 Mid-game rotation (physical/BLE or emulator): link + state survive, layout
      re-arranges cleanly

## Phase 6: Replay unification + wide regression + polish

Automated gate: **passed** (all four Gradle targets green — previews included in
`assembleDebug`; ktlint clean; grep checks: `TWO_PANE_MIN_WIDTH`, `rememberIsWideScreen`,
and `SIDE_PANEL_MAX_WIDTH` gone from sources; no `840.dp` literal anywhere; the only 840/480
occurrences left are the policy layer's named constants/kdoc and its boundary tests, plus the
Phase-2-approved single `SECTION_MAX_WIDTH = 480.dp` token).

Implementation notes for the pass:
- Replay renders through `BoardScreenScaffold`: the player line ("White vs Black · result")
  sits in the fixed banner slot, the board + eval bar compose unchanged in the board slot
  (`BoardWithEvalBar`, reserved-width pattern), and the sections live in the panel. Panel
  order is per-arrangement: side-pane leads with the transport row (stepping never needs a
  scroll), then the eval panel; the portrait column keeps the shipped order (eval panel,
  truncation notice, transport). The move list closes the panel in both.
- The private 840 dp `TWO_PANE_MIN_WIDTH` and its local `BoxWithConstraints` measurement
  basis are gone — Replay reads the shared window classification. A landscape phone now gets
  the side-pane arrangement (board + eval bar beside the panel) instead of a rotated portrait
  column.
- Drag-to-grow is preserved via the new `BoardScreenScaffold.contentWidthExpansion` (0..1):
  past the default board size the container cap slides from `CONTENT_MAX_WIDTH` toward the
  full window, so an enlarged board still spills past the default margins. It stays 0 (hard
  cap) for Play/PhysicalPlay and wherever the resize handle is disabled.
- The resize handle + stored fraction now gate on `boardResizeEnabled()` in Replay too: a
  ≥ 840 dp-wide landscape phone loses the handle it accidentally had (auto-fit instead, per
  the restored `BoardSize` contract); wide web/desktop behavior is unchanged. The move-list
  default follows the container (side panel → TABLE), so it no longer flips on rotation for
  wide phones — and a landscape-phone Replay now defaults to the table layout.
- `SIDE_PANEL_MAX_WIDTH` (hard 340 dp) retired: the shared panel sizing applies, so on a
  large monitor Replay's panel can now be up to 480 dp wide. Expected deltas for the
  regression eye: slightly wider panel on big screens, player line at the top of the panel
  (side-pane) instead of above the board, transport row in the panel instead of under the
  board, and in portrait the board sits ~30 dp lower (fixed banner slot — same as Play).
- `rememberIsWideScreen()` deleted (its last call site migrated); `BoardSize`'s doc names
  `boardResizeEnabled()` as the gate; `ReplayTruncatedPreview` pins a portrait-phone
  `LocalWindowSizeClass` so it keeps previewing the column arrangement.

Manual checks (deferred to the end-of-slice pass):

- [ ] 6.3 Wide regression pass (web maximized / desktop / tablet): Replay two-pane visually
      equivalent to pre-change — centered margins, board-size drag grows toward edges, tight
      pane gap, eval bar matches board height, move-list toggle + persistence intact
- [ ] 6.4 Replay landscape phone: SidePane with eval bar beside board; transport + move list
      reachable in the panel; stepping plies keeps the board stable (no jump)
- [ ] 6.5 History / NewGame / Connection landscape: caps and centering correct (History 720
      cap unchanged)
- [ ] 6.6 End-of-slice acceptance pass on the owner fleet: full matrix below confirmed

## End-of-slice acceptance pass (6.6) — fleet matrix

One pass over the owner fleet confirms every deferred row above (1.3–1.6, 2.6, 3.2–3.4,
4.2–4.5, 5.2–5.5, 6.3–6.5). Suggested order: web first (window resize covers the most
shapes fastest), then the Android phone, then the iPhone, then the physical board. Tick the
per-phase rows above as the runs confirm them.

**Launching the targets** (all Gradle calls from `SmartChessboard/`):

- **Web**: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :webApp:wasmJsBrowserDevelopmentRun`
  → opens `http://localhost:8080/` in the default browser. Resize the window by hand; for the
  short-wide shape use a window ≲ 470 px tall (or devtools responsive mode with e.g. 900×420).
- **Android**: connect the phone (USB debugging on), `ANDROID_HOME="$HOME/Library/Android/sdk"
  ./gradlew :androidApp:installDebug`, open *SmartChessboard* on the phone. Enable auto-rotate.
- **iOS**: open `SmartChessboard/iosApp` in Xcode → run the `iosApp` scheme on the iPhone
  (or a notched simulator). Make sure the orientation lock in Control Center is off.
- **Physical board**: power the ESP32 board (SmartChessboard-DA3A); it is single-central, so
  test with one phone at a time.

### Run A — Web (Chrome, resizable window; digital subset only — no Connection/PhysicalPlay)

1. **Wide Replay regression (6.3)**: maximize the window (≳ 1400×800). History → open a
   finished game (Replay). Verify against the pre-change look: content centered with side
   margins; board left, panel right with a tight (12 dp) gap; enable **Analysis** — the eval
   bar appears beside the board and exactly matches the board's height. Drag the board's
   corner handle right: the board grows and, past its default size, the whole two-pane
   container widens past the default margins toward the window edges; drag it back. Toggle
   **Table/Inline**: reload the page (or leave and reopen the game) — the choice persists.
   Step plies with `|< < > >|` and by clicking moves in the list.
2. **Resize handle gating (4.4 part)**: still wide — handle present. Now shrink the window
   to a phone-ish column (~420×900 css px): handle gone, board full-width auto-fit,
   portrait column layout, move list defaults to INLINE (unless you persisted an override in
   step 1 — the override must win here too, 4.5).
3. **Short-wide window → rail (3.4)**: stretch the window wide but short (~900×420). Every
   screen (History, NewGame, Play, Replay) swaps the top bar for the **left rail** (Back on
   top, actions below, no title). Replay shows SidePane: board + eval bar left, panel right
   with transport on top (6.4 shape on web). Slowly drag the window taller: past ~480 px the
   top bar returns (continuous resize, no reload).
4. **Play banner no-jump (4.3)**: in a short-wide or portrait window start a NewGame → Play.
   Make a few moves incl. giving check — the status banner text/emphasis changes in its slot
   and the board never shifts. End the game (End game → pick result → confirm): the
   "X wins" banner swap must not move the board either.
5. **NewGame landscape shape (6.5 part)**: short-wide window — form centered, 480 dp cap,
   scrollable, Start reachable with the duplicate-name error visible.
6. **History cap (6.5 part)**: wide window — list centered at its 720 dp cap, unchanged.

### Run B — Android phone (cutout device)

1. **Portrait spot-check (2.6)**: walk SignIn → History → NewGame → Play → Replay in
   portrait. Everything looks familiar; the one intended delta: on Play/PhysicalPlay/Replay
   the board sits ~30 dp lower (fixed banner slot).
2. **Rail on all six screens (3.2)**: rotate to landscape on History, NewGame, Play,
   PhysicalPlay, Connection, Replay — each shows the left rail (Back + that screen's
   actions, no title); rotate back — top bar returns.
3. **Cutout both rotations (1.6, 3.3)**: on each landscape screen rotate both ways
   (cutout-left and cutout-right): no content or tap target under the cutout, rail items
   comfortably tappable (≥ 48 dp); portrait unregressed.
4. **NewGame IME + error (1.3)**: landscape, focus a name field — the IME must not cover
   the focused field (form scrolls; `imePadding`). Trigger the duplicate-name error — Start
   still reachable.
5. **Play landscape (4.2, 4.4, 4.5)**: board fills the height beside the panel; no primary
   control below the fold; **no resize handle**; move list defaults to TABLE in the panel
   (with your persisted override winning if set); banner no-jump when check/result appears
   (4.3).
6. **EndGamePicker at compact height (1.5)**: in landscape Play tap "End game" — all result
   options + Confirm reachable (internal scroll if needed).
7. **Replay landscape (6.4)**: open a finished game, rotate: SidePane with eval bar beside
   the board (enable Analysis), transport at the top of the panel, move list below; stepping
   plies never moves the board.
8. **Connection reachability (1.4)**: open Connection (physical game) with a long device
   list at phone height (landscape helps): "Forget saved board" reachable (list is bounded,
   trailing action on screen).

### Run C — iPhone

1. Repeat B.2–B.7 on the iPhone (the notch is the cutout; both landscape rotations). Web-
   style resize doesn't apply; rotation covers the shapes.
2. **NewGame IME (1.3 iOS half)**: landscape + keyboard — focused field stays visible.
3. **Home-indicator edge**: in landscape board screens nothing sits under the home
   indicator (scaffold insets).

### Run D — Physical board (BLE, Android or iPhone)

1. **Recovery side-by-side (5.2)**: start/resume a physical game, rotate to landscape,
   lift a wrong piece / mis-place to trigger recovery (or toggle diagnostics): the reed
   grid renders **beside** the live board, both fully visible, no scrolling.
2. **Portrait grid bound (5.3)**: same flow in portrait — the grid caps to the viewport
   (no overflow), caption + Hide below it.
3. **BoardMessage no-jump (5.4)**: as messages appear/disappear (disconnect the board,
   reconnect, reject a move) the board never moves; a long message scrolls inside its slot;
   screen stays awake; sensor dots overlay intact and toggleable.
4. **Mid-game rotation (5.5)**: rotate the phone mid physical game: BLE link + game state
   survive, the layout re-arranges with no reload flash; make a move after rotating.
