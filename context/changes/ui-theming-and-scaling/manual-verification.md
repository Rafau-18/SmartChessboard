# Manual verification — ui-theming-and-scaling

The plan defers all manual checks to a single **3-target gate at slice end** (Android / iOS / web).
Automated verification for each phase is run and committed as the phase lands; the on-device manual
rows are accumulated here and confirmed in one pass once the slice is implemented.

> Status legend: `[ ]` pending end-of-slice confirmation · `[x]` confirmed by the user.

## Phase 1 — Theme foundation

Automated (done, committed): host + iOS-sim + wasm tests, Android assembleDebug, ktlint — all green.

Pending on-device confirmation:

- [ ] 1.6 Cycling the History top-bar control (Auto → Light → Dark) switches the theme live on
  Android, iOS, and web.
- [ ] 1.7 The chosen mode survives an app restart on each target (persisted via `ui.themeMode`).
- [ ] 1.8 Both modes are readable — board, eval bar, diagnostics, surfaces, and text all legible; the
  wooden board squares are unchanged between modes.

## Phase 2 — Scalable board

Automated (done, committed): `clampBoardSize` boundary test + board-size persistence round-trip on
host + iOS-sim; `:androidApp:assembleDebug`, `:webApp` wasm compile, `:shared:wasmJsTest`; ktlint — all green.

Pending on-device confirmation:

- [ ] 2.4 On a wide window (web/desktop/tablet) the board auto-fills the available space up to the
  viewport height; dragging the corner handle resizes it; the size survives an app restart
  (persisted via `ui.boardSize`).
- [ ] 2.5 On a phone the board is full-width auto-fit with no handle and no regression.
- [ ] 2.6 Replay (two-pane and single-column), Play, and PhysicalPlay all render the board correctly at
  narrow and wide widths; the eval bar still matches the board height in Replay.

## Phase 3 — Eval-bar behaviour + numeric label + fill animation

Automated (done, committed): hold-last fraction/score unit tests + `whiteBarFraction` regression on
host + iOS-sim; `:shared:wasmJsTest` (web compiles), `:androidApp:assembleDebug`; ktlint — all green.

Pending on-device confirmation (Replay, analysis enabled):

- [ ] 3.3 Stepping forward/back through plies, the eval bar holds the prior evaluation and animates to
  the new fraction instead of snapping to the centre while the next eval is fetching.
- [ ] 3.4 A loading affordance (the held label pulsing) shows while the new ply's eval is being fetched.
- [ ] 3.5 The numeric score label is readable, sits at a fixed bottom-centre spot inside the bar, and
  does not jump as the fill moves; `0.00` fills exactly to the board's vertical centre. Check the
  rare strong-black-advantage / forced-mate-for-Black case stays legible (light label on dark track).
