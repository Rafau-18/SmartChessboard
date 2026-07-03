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
