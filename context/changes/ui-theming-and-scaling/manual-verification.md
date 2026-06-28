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
