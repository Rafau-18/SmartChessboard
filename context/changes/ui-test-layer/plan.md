# UI Test Layer Implementation Plan

## Overview

Add the missing UI-testing layer to the SmartChessboard KMP app: Roborazzi
golden (screenshot) tests on the JVM freezing the freshly-merged adaptive UI,
compose.uiTest v2 smoke flows running from `commonTest` on all three targets,
and a GitHub Actions test gate — with GitHub + `gh` CLI onboarded first so the
agent can execute and verify the CI phases autonomously.

## Current State Analysis

- Logic coverage is strong: 57 test files — perft-verified chess engine,
  ViewModels, reducers, and 6 emulator-driven physical-flow E2E suites — but
  **zero pixels and zero composition tests**. `tech-stack.md` declares
  `runComposeUiTest` as the chosen UI-test tool; it was never implemented.
- `mobile-landscape-layout` (merged `5dac931`, 2026-07-04) added
  `LocalWindowSizeClass` + pure layout-policy functions + `BoardScreenScaffold`
  (Column vs SidePane, banner no-jump slots) and `AdaptiveScaffold`
  (TopBar vs LeftRail). Layout *logic* is unit-tested
  (`AdaptiveLayoutTest`, `BoardScreenScaffoldTest`); its plan-brief explicitly
  says "no UI tests; visual truth comes from the manual gate". Both recent UI
  slices (`ui-theming-and-scaling`, `mobile-landscape-layout`) rely on manual
  fleet passes for visual truth.
- No CI of any kind exists. Remote is Bitbucket
  (`git@bitbucket.org:<user>/smartchessboard.git`); the user has declared the
  GitHub move "absolutely necessary". A separate planned change
  (`github-ci-and-distribution`) owns distribution; this plan owns the **test**
  workflows only.
- Existing assets this plan builds on: hand-written fakes
  (`FakeGamesRepository`, `FakeAuthRepository`, `FakeEvalRepository`,
  `FakeGameJournal`), `PgnFixtures`/`TestPositions`, Koin DI with lazy
  `single {}` definitions, `withHostTest { isIncludeAndroidResources = true }`
  already configured, and a working `wasmJsTest` headless-browser task.
- Versions: Kotlin 2.4.0, Compose Multiplatform 1.11.1, AGP 9.0.1 (new KMP
  android-library plugin with `androidHostTest`), Material3 `1.11.0-alpha07`,
  JUnit 4.

## Desired End State

A PR against the (now-GitHub-hosted) repo automatically runs, on a Linux
runner: the full `:shared:testAndroidHostTest` suite **including golden
verification** of the board, adaptive scaffolds, and panel components (full
variant matrix × light/dark), plus `:shared:wasmJsTest` including two
compose.uiTest smoke flows (digital happy path; History→Replay+delete) that
also pass on the iOS simulator via a nightly/manual macOS workflow. Goldens are
recorded **by a CI workflow** (canonical environment) and live in plain git as
WebP. An intentional UI change follows a documented ritual: dispatch the record
workflow → review the diff images → commit. The agent can drive the whole loop
with `gh` (`workflow run` / `run watch` / `run download`).

Verification of the end state: a deliberately broken render (e.g. a changed
board color) fails the PR workflow with a readable diff report artifact, and
reverting it turns the PR green — demonstrated once during Phase 5.

### Key Discoveries:

- `LocalWindowSizeClass` (`presentation/layout/AdaptiveLayout.kt:16`) defaults
  to `WindowSizeClass(0, 0)` → height-compact → **SidePane + LeftRail**. Any
  golden or uiTest must provide an explicit class; "forgot the local" produces
  the landscape layout, not portrait.
- The three arrangements are pure functions of the provided class
  (`AdaptiveLayout.kt:58-77`), so landscape/wide goldens need no devices — just
  inject the class and size the canvas.
- Fakes-first DI is already the house rule (lessons.md): Koin `single {}` is
  lazy, so overriding repository definitions means `SupabaseClient` is never
  constructed — the uiTest suite must pass **without** `local.properties`
  credentials (the fresh-worktree crash lesson).
- CMP 1.11 deprecated the old `runComposeUiTest` in favor of **v2**
  (`androidx.compose.ui.test.v2.runComposeUiTest`), which defaults to
  `StandardTestDispatcher` — tests must `waitForIdle()`/`waitUntil()` instead
  of assuming eager coroutines. On wasm the body runs with delays skipped.
- macOS Actions minutes count ×10 against the free 2,000/month; Linux ×1 —
  this is why the iOS simulator job is nightly/manual, not a PR gate.
- Mac-recorded goldens routinely fail on Linux CI (font rasterization), which
  is why Phase 5 makes CI the canonical recording environment and re-records
  once.

## What We're NOT Doing

- **No `ReedDiagnosticsGrid` goldens** (explicitly excluded by the user).
- No iOS / desktop / wasm **screenshot** targets (Roborazzi-iOS experimental,
  no desktop target in this project, web canvas testing deferred).
- No Playwright/browser visual tests for the wasm target, no Maestro, no cloud
  visual services (Percy/Chromatic/Argos/Lost Pixel), no Git LFS.
- No screen-level goldens through live ViewModels (components + scaffolds with
  fake slot content only; no stateless-screen refactor in this change).
- No auth-flow or PhysicalPlay uiTest smoke (only the two chosen flows).
- No distribution CI (APK/web deploy — that's `github-ci-and-distribution`),
  no branch-protection setup, no ktlint/detekt CI wiring (can join the PR
  workflow later), no coverage tooling.
- No fixing of pre-existing test debt; no Bitbucket repo deletion (old remote
  stays until the user retires it manually).

## Implementation Approach

Two independent test layers over one shared harness philosophy, then a CI roof:

1. **Golden layer (JVM only)** — Roborazzi + Robolectric in the existing
   `androidHostTest` source set of `:shared`. Common composables compile into
   the Android target, and Skia renders identically enough across platforms
   that JVM goldens are the community-standard proxy for CMP UIs. A small
   harness function pins theme + window-size-class + surface size per shot.
2. **Smoke layer (all targets)** — compose.uiTest v2 from `commonTest`,
   rendering the real `App()` root with Koin repository overrides (existing
   fakes), driving flows by semantics. Runs via the existing per-target test
   tasks; no new devices or emulators.
3. **CI roof** — three GitHub workflows (PR gate on ubuntu, golden-record on
   dispatch, iOS nightly on macOS), verified end-to-end by the agent through
   `gh`. Goldens recorded locally during Phases 1–2 are treated as provisional;
   Phase 5 re-records them on CI, which becomes the canonical environment.

Phase order honors the user's priority: freeze the fresh adaptive UI first
(local value from day one), behavior smoke second, CI last — with GitHub
onboarding pulled forward as Phase 0 because it is cheap, unblocks Phase 5,
and lets every later phase push to the canonical remote.

## Critical Implementation Details

- **Roborazzi task wiring under the new AGP KMP plugin is unproven.** The
  Gradle plugin generates per-variant tasks (`recordRoborazziDebug` style);
  with `com.android.kotlin.multiplatform.library` + `androidHostTest` the task
  set/name may differ or be absent. Fallback that always works: run the test
  task with system properties —
  `./gradlew :shared:testAndroidHostTest -Droborazzi.test.record=true` (and
  `…verify=true` for gating). Phase 1 resolves this and records the chosen
  invocation in `SmartChessboard/AGENTS.md`; later phases and CI use whatever
  Phase 1 established.
- **Golden output must be a committed source location, not `build/`.**
  Configure Roborazzi's output directory to
  `shared/src/androidHostTest/snapshots/` so goldens are versioned; WebP +
  `roborazzi.record.resizeScale=0.5` keep the tree small.
- **Every golden pins its inputs**: explicit `LocalWindowSizeClass` (the 0×0
  default is a landscape trap), `AppTheme(darkTheme = …)`, fixed shot size.
  Content is state-only (no clocks, no network); board states come from
  `TestPositions`/`PgnFixtures`.
- **Goldens recorded in Phases 1–2 (Mac) are provisional.** Phase 5 re-records
  the whole set on the ubuntu runner in one commit and documents CI as the only
  recording environment thereafter. Expect that commit to touch every golden —
  that is the plan, not drift.
- **`compose.uiTest` in `commonTest` touches the wasm dependency graph** — if
  `kotlin-js-store/yarn.lock` drifts, run
  `./gradlew kotlinWasmUpgradeYarnLock` once (lessons.md rule).
- **uiTest v2 discipline**: `@OptIn(ExperimentalTestApi::class)`,
  `StandardTestDispatcher` semantics (`waitForIdle()` after actions), suspend
  test bodies on wasm. Android-host execution of `runComposeUiTest` under
  Robolectric is a Phase 3 verification point; if it proves unworkable, the
  smoke suite's contract targets are iOS simulator + wasm (documented), since
  Android behavior is already covered by VM/reducer tests.
- **Koin harness must keep secrets out**: override at the repository seams
  (`FakeAuthRepository` pre-signed-in, `FakeGamesRepository` seeded per test),
  never construct `SupabaseClient` (lazy `single {}` guarantees this as long as
  no override resolves it), `stopKoin()` between tests. Acceptance includes a
  run with blanked/absent Supabase credentials.
- **Driving the board Canvas**: squares have no per-square semantics. Add one
  `Modifier.testTag("chess-board")` to `ChessBoardView`'s root and perform
  taps via `performTouchInput` at computed square-center offsets (board is a
  square; offsets are `(file + 0.5)/8 × side`, rank-flipped for orientation).
- **Material3 is pinned at an alpha** — M3 upgrades will legitimately move
  component pixels (rail, top bar, buttons). The re-record ritual (dispatch
  record workflow → review diffs → commit) is documented in
  `SmartChessboard/AGENTS.md` as part of Phase 2.

## Phase 0: GitHub onboarding + gh CLI

### Overview

Move the canonical remote to GitHub and establish the `gh` CLI loop so later
phases (and the agent) can create, trigger, and verify workflows autonomously.
Distribution stays out of scope.

### Changes Required:

#### 1. GitHub repository + remotes

**File**: none (git configuration + hosted repo)

**Intent**: Create the private GitHub repository and make it `origin`;
preserve Bitbucket as a secondary remote until the user retires it.

**Contract**: `gh repo create <user>/smartchessboard --private` (name/owner
confirmed with the user at execution time); existing remote renamed
`origin` → `bitbucket`; new GitHub remote added as `origin`; full history +
tags pushed (`git push origin --all && git push origin --tags`). `git remote -v`
shows GitHub as `origin`.

#### 2. gh CLI availability + auth

**File**: none (local tooling)

**Intent**: Ensure `gh` is installed and authenticated so workflow dispatch,
watching, and artifact download work non-interactively afterwards.

**Contract**: `gh --version` succeeds (install via Homebrew if missing);
`gh auth status` reports an authenticated user with `repo` + `workflow`
scopes. The interactive `gh auth login` is a **user-performed** step (browser
device flow) — the agent names the command and waits; no tokens pasted into
chat (house rule).

### Success Criteria:

#### Automated Verification:

- `gh auth status` exits 0 and shows required scopes
- `gh repo view <user>/smartchessboard --json name,visibility` returns the repo as private
- `git remote -v` lists `origin` → GitHub and `bitbucket` → old remote
- `git push origin main` (no-op push) exits 0

#### Manual Verification:

- User confirms repo visibility and ownership are as intended
- User has completed `gh auth login` on this machine

**Implementation Note**: After completing this phase and all automated
verification passes, pause for manual confirmation before proceeding.

---

## Phase 1: Roborazzi foundation + first board goldens

### Overview

Wire Roborazzi + Robolectric into `:shared`'s `androidHostTest`, build the
screenshot harness (theme × window-size-class × shot size), and land the first
2–3 `ChessBoardView` goldens with working record/verify invocations.

### Changes Required:

#### 1. Version catalog + build wiring

**File**: `SmartChessboard/gradle/libs.versions.toml`,
`SmartChessboard/shared/build.gradle.kts`

**Intent**: Add Roborazzi (1.64.0), Robolectric (current stable 4.x), and the
Compose test rule artifacts needed for host-side capture; apply the Roborazzi
Gradle plugin to `:shared`.

**Contract**: `androidHostTest` dependencies gain Robolectric + `roborazzi` +
`roborazzi-compose` + the JUnit4 Compose rule; plugin application must not
disturb the existing `testAndroidHostTest`, `iosSimulatorArm64Test`,
`wasmJsTest` tasks. Resolve the record/verify invocation (dedicated tasks vs
`-Droborazzi.test.record=true` fallback) and treat it as this phase's main
unknown. Golden output directory configured to
`shared/src/androidHostTest/snapshots/`; WebP + `resizeScale=0.5` via
`gradle.properties`.

#### 2. Screenshot harness

**File**: new
`SmartChessboard/shared/src/androidHostTest/kotlin/org/rurbaniak/smartchessboard/screenshot/ScreenshotHarness.kt`

**Intent**: One helper that renders arbitrary content under pinned inputs so
every golden test is a one-liner and no test can forget the window-class trap.

**Contract**: signature shape (other phases depend on it):
`fun ComposeContentTestRule.golden(name: String, dark: Boolean, windowSizeClass: WindowSizeClass = PORTRAIT_MEDIUM, size: DpSize = DEFAULT_SHOT, content: @Composable () -> Unit)`
— wraps content in `AppTheme(darkTheme = dark)` +
`CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass)`,
fixes the surface size, calls `captureRoboImage` with the shared
`RoborazziOptions` (comparator threshold tolerating antialiasing only).
Named window-class constants for the three arrangements live here
(`PORTRAIT_MEDIUM`, `LANDSCAPE_COMPACT`, `WIDE_EXPANDED`) with dp pairs chosen
against `BREAKPOINTS_V1`.

#### 3. First board goldens

**File**: new
`SmartChessboard/shared/src/androidHostTest/kotlin/org/rurbaniak/smartchessboard/screenshot/ChessBoardViewScreenshotTest.kt`

**Intent**: Prove the pipeline end to end with the highest-value component:
start position (white orientation), a mid-game position with selection +
legal-move highlights, both light theme.

**Contract**: `@RunWith(RobolectricTestRunner::class)` +
`@GraphicsMode(GraphicsMode.Mode.NATIVE)`; piece images (compose resources)
must render — `isIncludeAndroidResources = true` is already set; if resource
loading fails under Robolectric this phase surfaces and solves it.

### Success Criteria:

#### Automated Verification:

- Record invocation produces 2–3 goldens under `shared/src/androidHostTest/snapshots/`
- Verify invocation passes on unchanged code: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest -Droborazzi.test.verify=true --console=plain --no-daemon` (or the dedicated task Phase 1 establishes)
- Deliberate-break check: temporarily change a board color constant → verify fails with a diff image in the report → revert → verify green
- Full existing suite still green: `:shared:testAndroidHostTest` (without roborazzi flags)

#### Manual Verification:

- Open the recorded goldens and confirm the board renders correctly (pieces, colors, no clipping)
- Confirm the HTML report at `shared/build/reports/roborazzi/` is readable as the future diff-review surface

**Implementation Note**: pause for manual confirmation before Phase 2.

---

## Phase 2: Full golden matrix

### Overview

Expand to the agreed full matrix × light/dark across board states, adaptive
scaffolds, and panel components (~44 goldens), tune thresholds, and document
the re-record ritual.

### Changes Required:

#### 1. Board state matrix

**File**: `ChessBoardViewScreenshotTest.kt` (extend)

**Intent**: Freeze the board's visual vocabulary. Nine states × light/dark:
start (white orientation), start (black orientation), selection + legal-move
targets, check highlight, last-move highlight, best-move arrow (UCI arrow),
capture-rich middlegame, promotion overlay (board + `PromotionPicker`),
terminal mate position.

**Contract**: positions sourced from `TestPositions`/`PgnFixtures` (add
fixtures if a state lacks one); every shot named
`board_<state>_<light|dark>`; 18 goldens.

#### 2. Adaptive scaffold matrix

**File**: new `BoardScreenScaffoldScreenshotTest.kt`, new
`AdaptiveScaffoldScreenshotTest.kt` (same package)

**Intent**: Freeze the fresh landscape-merge layout: `BoardScreenScaffold` in
Column / SidePane-compact / SidePane-wide with placeholder banner/board/panel
slot content (distinct solid blocks + text), asserting pane split, banner slot
reservation, and paddings; `AdaptiveScaffold` in TopBar and LeftRail chrome.

**Contract**: window classes via the harness constants; shot sizes match the
class (e.g. 412×892, 892×412, 1280×800 dp); 3×2 + 2×2 = 10 goldens. Slot
content must be deterministic (no real board here — this isolates layout from
board rendering).

#### 3. Panel component matrix

**File**: new `PanelComponentsScreenshotTest.kt` (same package)

**Intent**: Freeze `EvalComponents` (even / white-advantage / mate score),
`MoveList` (INLINE and TABLE modes, same fixture game), `SyncIndicator`
(saving-visible and idle-slot states).

**Contract**: (3 + 2 + 2) × light/dark = 14 goldens; MoveList fixture long
enough to exercise wrapping/scroll bounds at fixed shot height.

#### 4. Ritual documentation

**File**: `SmartChessboard/AGENTS.md` (new short section)

**Intent**: Make the golden workflow discoverable by any future agent/session:
how to record, verify, review diffs, and the rule that CI (after Phase 5) is
the only canonical recording environment; note the M3-alpha-upgrade re-record
expectation.

**Contract**: section titled "Screenshot (golden) tests"; states the exact
record/verify invocations Phase 1 established.

### Success Criteria:

#### Automated Verification:

- Verify invocation green over the complete set (~44 goldens) on repeated runs (determinism: 3 consecutive green verifies)
- Golden tree size sanity: `du -sh shared/src/androidHostTest/snapshots` ≤ ~10 MB
- Full `:shared:testAndroidHostTest` green

#### Manual Verification:

- Visual pass over all goldens (one sitting): each state reads correctly in both themes; scaffold shots show the intended arrangement and banner slots
- Re-record ritual section in AGENTS.md is accurate enough to follow cold

**Implementation Note**: pause for manual confirmation before Phase 3.

---

## Phase 3: uiTest harness + digital happy path

### Overview

Introduce compose.uiTest v2 in `commonTest`, build the real-`App()`-with-Koin-
overrides harness, and land the digital happy-path smoke: NewGame → Play →
move → end game — green on iOS simulator + wasm (Android host if v2-under-
Robolectric proves workable).

### Changes Required:

#### 1. Dependencies

**File**: `SmartChessboard/gradle/libs.versions.toml`,
`SmartChessboard/shared/build.gradle.kts`

**Intent**: Add `compose.uiTest` to `commonTest` (plus any per-target runner
artifacts the v2 API needs).

**Contract**: existing test tasks keep working; if the wasm dependency graph
changes, `kotlinWasmUpgradeYarnLock` is run once and
`kotlin-js-store/yarn.lock` is committed.

#### 2. App test harness

**File**: new
`SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/uitest/AppTestHarness.kt`

**Intent**: One entry point that composes the production `App()` with Koin
started from production modules + a test override module (fake repositories,
pre-signed-in auth, seeded games), and tears Koin down after each test.

**Contract**: override seams are the repository interfaces already faked in
`commonTest`; `SupabaseClient` must never be constructed (lazy DI — verified
by the no-credentials criterion below); harness exposes
`runAppTest(seed: FakeSeed, body: suspend ComposeUiTest.() -> Unit)` built on
v2 `runComposeUiTest`; `stopKoin()` in a `finally`.

#### 3. Board tap support

**File**:
`SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/board/ChessBoardView.kt`

**Intent**: Make the Canvas board addressable from tests.

**Contract**: add `Modifier.testTag("chess-board")` (semantics-only change, no
behavior); tests tap square centers via `performTouchInput` with offsets
computed from node bounds (orientation-aware helper lives in the harness).

#### 4. Digital happy-path smoke

**File**: new
`SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/uitest/DigitalPlaySmokeTest.kt`

**Intent**: One flow: from History (empty), create a pass-and-play game, play
1. e4 (tap e2, tap e4), assert the move appears in the move list, end the game
via `EndGamePicker`, assert return to History showing the finished game.

**Contract**: assertions by semantics (`onNodeWithText`/roles/tags), never
pixels; `waitForIdle()`/`waitUntil { }` after every action (v2 dispatcher);
unique-per-run player names (timestamp suffix) per E2E house rules.

### Success Criteria:

#### Automated Verification:

- `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:iosSimulatorArm64Test --console=plain --no-daemon` green including the smoke test
- `… :shared:wasmJsTest` green including the smoke test
- Android host: either `:shared:testAndroidHostTest` runs the smoke green under Robolectric, or the exclusion + rationale is committed (documented fallback)
- No-secrets run: suite green with Supabase entries in `local.properties` blanked/absent
- `:shared:testAndroidHostTest` (full) green — no regression from Koin/test deps

#### Manual Verification:

- Read the smoke test as documentation: does the flow read like the product flow? (naming, steps)

**Implementation Note**: pause for manual confirmation before Phase 4.

---

## Phase 4: History → Replay + delete smoke

### Overview

Second smoke flow on the same harness: seeded finished game → open Replay →
step through moves → back → kebab delete with confirmation → list empties.
Covers the week-old delete feature and Nav3 push/pop.

### Changes Required:

#### 1. Replay/delete smoke

**File**: new
`SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/uitest/HistoryReplayDeleteSmokeTest.kt`

**Intent**: Assert: History shows the seeded game; tapping it opens Replay
(board + move list render; eval UI may show the fake/eval-unavailable state);
move navigation (next/prev) updates the shown ply; back returns to History;
kebab → delete → confirm removes the row (and the fake repository records the
deletion).

**Contract**: seeded via `FakeSeed` (one finished game from `PgnFixtures`);
`FakeEvalRepository` returns a stable no-eval/deterministic response so Replay
never hits the network; deletion asserted both in UI (row gone) and against
the fake (repository call observed).

### Success Criteria:

#### Automated Verification:

- `:shared:iosSimulatorArm64Test` and `:shared:wasmJsTest` green including both smoke tests
- Android host per the Phase 3 resolution (runs or documented exclusion)
- No-secrets run still green

#### Manual Verification:

- Flow reads correctly as documentation; delete confirmation copy matches the real dialog

**Implementation Note**: pause for manual confirmation before Phase 5.

---

## Phase 5: CI workflows on GitHub Actions

### Overview

Three workflows (PR gate, golden record, iOS nightly), one-time golden
re-record on the canonical CI environment, and an agent-driven verification
loop through `gh` — including a deliberate-failure demonstration.

### Changes Required:

#### 1. PR test gate

**File**: new `.github/workflows/tests.yml`

**Intent**: On every PR (and push to `main`): ubuntu runner executes
`:shared:testAndroidHostTest` with golden **verify** enabled plus
`:shared:wasmJsTest`; on golden failure, the Roborazzi report/diff images
upload as a workflow artifact.

**Contract**: JDK setup matching the build (17+), `gradle/actions/setup-gradle`
caching, Android SDK from the runner image (ubuntu-latest ships one; set
`ANDROID_HOME` accordingly), Chrome headless present for wasm tests (runner
image default). No repository secrets are used anywhere in this workflow.

#### 2. Golden record workflow

**File**: new `.github/workflows/record-goldens.yml`

**Intent**: `workflow_dispatch` (branch-scoped): run the record invocation on
ubuntu and commit+push refreshed goldens back to that branch as
`github-actions[bot]` — the only sanctioned way to update goldens after this
phase.

**Contract**: uses the built-in `GITHUB_TOKEN` with `contents: write`
permission scoped in the workflow; commit message convention
`test(goldens): re-record on CI`; concurrency guard so two dispatches don't
race on one branch.

#### 3. iOS simulator workflow

**File**: new `.github/workflows/ios-tests.yml`

**Intent**: `iosSimulatorArm64Test` on `macos-latest`, nightly (`schedule`
cron) + `workflow_dispatch` — deliberately not a PR gate (×10 minutes).

**Contract**: Xcode/simulator from the runner image; job timeout ≤ 45 min;
failure notification is simply the red run (no extra channels at MVP).

#### 4. One-time golden migration to CI

**File**: `shared/src/androidHostTest/snapshots/**` (bulk refresh)

**Intent**: Replace all Mac-recorded goldens with CI-recorded ones in a single
reviewed commit, making CI the canonical environment.

**Contract**: dispatch record workflow on the phase branch → review the diff
locally (expect broad small font/antialiasing deltas, no structural changes) →
merge. From then on, local record is preview-only; `SmartChessboard/AGENTS.md`
section updated to say so.

### Success Criteria:

#### Automated Verification:

- `gh workflow run tests.yml --ref <branch>` + `gh run watch` → conclusion `success`
- `gh workflow run record-goldens.yml --ref <branch>` produces a bot commit with refreshed goldens; subsequent `tests.yml` run green against them
- Deliberate-break check via CI: push a color change → `tests.yml` fails; `gh run download` fetches the diff-report artifact; revert → green
- `gh workflow run ios-tests.yml` → conclusion `success` (one manual dispatch proves the nightly path)
- Workflow files pass `actionlint` (run via `gh` extension or local binary) — optional but preferred

#### Manual Verification:

- Review the diff-report artifact once: images are readable and reviewable (this is the future review surface)
- Confirm Actions minutes consumption after the first full week is comfortably inside the free tier (Settings → Billing)

**Implementation Note**: this phase ends the plan; run the closing ritual
(SHA write-back, `## Progress` flips, change status update).

---

## Testing Strategy

### Unit Tests:

- No new unit-test surface beyond the layers themselves; existing 57-file
  suite must stay green through every phase (regression gate in each phase's
  criteria).

### Integration Tests:

- The two uiTest smokes ARE the integration layer: real `App()`, real Nav3,
  real ViewModels, fake data layer — executed per-target
  (`iosSimulatorArm64Test`, `wasmJsTest`, Android host if workable).

### Manual Testing Steps:

1. Phase 1/2: eyeball recorded goldens + HTML report once per phase.
2. Phase 5: review one CI diff artifact end-to-end (fail → download → read).
3. After Phase 5: normal development — make any visual tweak, follow the
   AGENTS.md ritual once to confirm it holds in practice.

## Performance Considerations

- Golden suite is JVM-only and runs in seconds; keep it inside
  `testAndroidHostTest` so it adds no new CI job.
- `wasmJsTest` (headless browser) is the slowest PR-gate member (~minutes);
  acceptable. iOS stays off the PR path (×10 minutes).
- WebP + `resizeScale=0.5` keeps the golden tree in low-MB territory; plain
  git, no LFS.

## Migration Notes

- Goldens change canonical home once (Mac → CI) in Phase 5, by design, in one
  commit. Any goldens recorded locally after that are previews and must not be
  committed.
- Remote migration (Phase 0) keeps Bitbucket intact as `bitbucket`; nothing is
  deleted. CI exists only on the GitHub side.

## References

- Research (tool landscape, costs, verification workflows):
  `context/changes/ui-test-layer/research.md`
- Adaptive layout policy + injectable window class:
  `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/layout/AdaptiveLayout.kt:16`
- Slot scaffold under golden test:
  `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/components/BoardScreenScaffold.kt:75`
- Existing fakes: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/**/Fake*.kt`
- House rules consulted: `context/foundation/lessons.md` (Native-green rule,
  wasm Throwable rule, yarn.lock rule, fresh-worktree credentials lesson)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 0: GitHub onboarding + gh CLI

#### Automated

- [x] 0.1 `gh auth status` exits 0 with repo+workflow scopes — 8892540
- [x] 0.2 `gh repo view` returns the private repo — 8892540
- [x] 0.3 `git remote -v` shows origin→GitHub, bitbucket→old remote — 8892540
- [x] 0.4 `git push origin main` exits 0 — 8892540

#### Manual

- [x] 0.5 User confirms repo visibility/ownership — 8892540
- [x] 0.6 User completed `gh auth login` — 8892540

### Phase 1: Roborazzi foundation + first board goldens

#### Automated

- [ ] 1.1 Record invocation produces first goldens in `snapshots/`
- [ ] 1.2 Verify invocation green on unchanged code
- [ ] 1.3 Deliberate-break check: verify fails on color change, green after revert
- [ ] 1.4 Full `:shared:testAndroidHostTest` green

#### Manual

- [ ] 1.5 Goldens visually correct (pieces, colors, no clipping)
- [ ] 1.6 HTML report readable as diff-review surface

### Phase 2: Full golden matrix

#### Automated

- [ ] 2.1 Verify green over complete set; 3 consecutive deterministic runs
- [ ] 2.2 Golden tree ≤ ~10 MB
- [ ] 2.3 Full `:shared:testAndroidHostTest` green

#### Manual

- [ ] 2.4 Visual pass over all goldens in both themes
- [ ] 2.5 AGENTS.md ritual section followable cold

### Phase 3: uiTest harness + digital happy path

#### Automated

- [x] 3.1 `:shared:iosSimulatorArm64Test` green incl. smoke
- [x] 3.2 `:shared:wasmJsTest` green incl. smoke
- [x] 3.3 Android host smoke runs green OR documented exclusion committed
- [x] 3.4 No-secrets run green (blank/absent Supabase creds)
- [x] 3.5 Full `:shared:testAndroidHostTest` green

#### Manual

- [ ] 3.6 Smoke test reads as product-flow documentation

### Phase 4: History → Replay + delete smoke

#### Automated

- [ ] 4.1 Both smokes green on `iosSimulatorArm64Test` + `wasmJsTest`
- [ ] 4.2 Android host per Phase 3 resolution
- [ ] 4.3 No-secrets run still green

#### Manual

- [ ] 4.4 Flow/copy matches the real delete dialog

### Phase 5: CI workflows on GitHub Actions

#### Automated

- [ ] 5.1 `tests.yml` run via `gh` → success
- [ ] 5.2 `record-goldens.yml` produces bot commit; `tests.yml` green against it
- [ ] 5.3 Deliberate-break via CI: fail → artifact downloadable → revert → green
- [ ] 5.4 `ios-tests.yml` manual dispatch → success
- [ ] 5.5 Workflow files pass actionlint (preferred)

#### Manual

- [ ] 5.6 Diff-report artifact reviewed and readable
- [ ] 5.7 Actions minutes checked inside free tier after first week
