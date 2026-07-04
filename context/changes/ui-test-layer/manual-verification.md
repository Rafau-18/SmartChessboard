# Manual verification — ui-test-layer

Code/doc-read manual rows are deferred here per the house convention (defer code-read manual items
to the end of the stream, commit each phase as its automated criteria pass, confirm in one sitting).
On-CI review items (Phase 5) stay in the plan's gate — they need the live CI run, not a code read.

> Status legend: `[ ]` pending confirmation · `[x]` confirmed by the user.

## Phase 3 — uiTest harness + digital happy path

Automated (done, committed): `:shared:iosSimulatorArm64Test` + `:shared:wasmJsTest` green including
`DigitalPlaySmokeTest`; Android host runs with the documented `uitest/` exclusion
(`shared/build.gradle.kts`); the whole run used no Supabase credentials (no `local.properties` in
the worktree — BuildKonfig empty); full `:shared:testAndroidHostTest` green.

Pending confirmation (code read):

- [ ] 3.6 `DigitalPlaySmokeTest` reads as product-flow documentation: the steps and naming mirror
  the real flow (History → New game → names → Start → 1. e4 → move list → End game → White wins →
  confirm → Analyse/Back to history → History row "White won").
  File: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/uitest/DigitalPlaySmokeTest.kt`

## Phase 4 — History → Replay + delete smoke

Automated (done, committed): both smokes green on `:shared:iosSimulatorArm64Test` +
`:shared:wasmJsTest`; Android host green under the Phase 3 `uitest/` exclusion; still no
credentials anywhere in the worktree.

Pending confirmation (code read):

- [ ] 4.4 `HistoryReplayDeleteSmokeTest` reads correctly as the product flow, and the delete
  confirmation copy asserted in the test ("Delete game?", "This permanently deletes <matchup>…")
  matches the real dialog in `HistoryScreen.kt` (`DeleteGameDialog`).
  File: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/uitest/HistoryReplayDeleteSmokeTest.kt`

## Phase 5 — CI workflows on GitHub Actions

Automated (done, merged to main `c8a7441`): `tests.yml` (PR/main gate, JVM golden verify + wasm)
green via `gh`; `record-goldens.yml` dispatched on `ui-test-layer-ci`, produced bot commit
`dd6ced7` refreshing 10 board goldens; `tests.yml` green against the refreshed goldens;
deliberate-break demo on scratch branch `ci-break-demo` (deleted after use) — verify failed,
`roborazzi-report` artifact downloaded and inspected, revert turned it green again;
`ios-tests.yml` green both via manual dispatch and its own nightly cron; all three workflow
files pass `actionlint` 1.7.12. Merged `ui-test-layer-ci` → `main` at `c8a7441`.

Pending confirmation (needs a human look, not just a code read):

- [ ] 5.6 Review the Roborazzi diff-report artifact end-to-end and confirm the triptych
  (Reference | Diff | New) format is readable as the ongoing visual-regression review surface.
  Fetch a fresh one from any `tests.yml` run: `gh run download <run-id> -n roborazzi-report -D <dir>`,
  open `reports/roborazzi/androidHostTest/index.html`.
- [ ] 5.6b Review the golden-refresh diff in bot commit
  [`dd6ced7`](https://github.com/Rafau-18/SmartChessboard/commit/dd6ced7) — confirm every changed
  `board_*.webp` shows only antialiasing-level pixel drift, no structural/color change.
- [ ] 5.7 Check Actions minutes consumption after the first full week (from 2026-07-04, so on or
  after 2026-07-11) at Settings → Billing and licensing → Usage, and confirm it's comfortably
  inside the free 2,000 min/month tier given the nightly iOS job bills at ×10.

See `manual-verification-pl.md` in this folder for a plain-language, step-by-step Polish
walkthrough of all four pending items above (3.6, 4.4, 5.6, 5.6b, 5.7).
