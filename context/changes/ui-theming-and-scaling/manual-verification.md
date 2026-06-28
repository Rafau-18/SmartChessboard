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
