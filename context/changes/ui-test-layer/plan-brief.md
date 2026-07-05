# UI Test Layer — Plan Brief

> Full plan: `context/changes/ui-test-layer/plan.md`
> Research: `context/changes/ui-test-layer/research.md`

## What & Why

Add the app's missing UI-testing layer: JVM golden (screenshot) tests freezing
the freshly-merged adaptive UI, compose.uiTest v2 smoke flows on all three
targets, and a GitHub Actions test gate. Logic coverage is already strong
(perft engine, ViewModels, emulator E2E); pixels and composition have zero
coverage, and the last two UI slices relied entirely on manual fleet passes
for visual truth.

## Starting Point

57 test files but no Compose UI tests (despite `tech-stack.md` declaring
`runComposeUiTest`), no screenshot infra, and **no CI at all** — remote on
Bitbucket, GitHub move declared mandatory by the owner.
`mobile-landscape-layout` (just merged) made layout policy injectable
(`LocalWindowSizeClass` + pure functions), which makes landscape/wide goldens
cheap. Hand-written fakes and `PgnFixtures` already exist for the harness.

## Desired End State

Every PR runs a Linux gate: full host-test suite **including golden
verification** (~44 goldens: board states, adaptive scaffolds, panel
components — full matrix × light/dark) plus wasm tests including two smoke
flows; iOS simulator runs nightly/manual (macOS = ×10 minutes). Goldens are
recorded by a dispatchable CI workflow (canonical environment) and reviewed as
diff-image artifacts. The agent drives and verifies the loop with `gh`.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Screenshot tool | Roborazzi 1.64.0 on JVM (`androidHostTest`) | Mature, device-free, coexists with Robolectric-based uiTest; common UI compiles into the Android target so JVM goldens are the community-standard CMP proxy |
| Golden scope | ChessBoardView states + adaptive scaffolds + panel components; **no ReedDiagnosticsGrid** | User selection: highest-value visuals incl. the fresh landscape work |
| Variant matrix | Full matrix × light/dark (~44 goldens) | User chose max coverage, accepting the re-record discipline documented in AGENTS.md |
| Golden environment | Record **on CI** (dispatch workflow commits to branch); goldens in plain git as PNG | Mac↔Linux font rasterization drift; record-where-you-verify; Phases 1–2 record locally as provisional, Phase 5 re-records once. Format was WebP until a post-merge fix (2026-07-05) — its codec non-deterministically failed to read its own writes; see `lessons.md` |
| Smoke flows | Digital happy path + History→Replay+delete | User selection; auth & physical flows out |
| Smoke harness | Real `App()` root + Koin repository overrides (existing fakes) | Tests real Nav3+DI integration; suite must pass with no Supabase credentials |
| CI shape | PR gate on ubuntu (host tests + goldens + wasm); iOS nightly/manual on macOS | Free-tier friendly (macOS ×10); iOS regressions surface next morning, not on PR |
| Sequencing | GitHub onboarding first (Phase 0), then goldens → uiTest → CI | User priority: freeze visuals ASAP; Phase 0 pulled forward so the agent can verify CI with `gh` |
| Not buying anything | No Percy/Chromatic/Argos/Lost Pixel, no LFS | OSS + git + HTML reports suffice at ~44 goldens / low-MB tree |

## Scope

**In scope:** GitHub repo migration + `gh` CLI loop; Roborazzi wiring in
`:shared` `androidHostTest`; screenshot harness (theme × window class × size);
~44-golden matrix; re-record ritual in `SmartChessboard/AGENTS.md`;
compose.uiTest v2 harness with Koin overrides; two smoke flows; `testTag` on
the board Canvas; three GitHub workflows (PR gate, golden record, iOS
nightly); one-time golden migration to CI.

**Out of scope:** ReedDiagnosticsGrid goldens; iOS/desktop/wasm screenshots;
Playwright/Maestro; cloud visual services; screen-level goldens through live
ViewModels; auth/physical smoke flows; distribution CI
(`github-ci-and-distribution`); branch protection; coverage tooling.

## Architecture / Approach

Two test layers over one philosophy, then a CI roof. Goldens: Robolectric
Native Graphics renders common composables on the JVM; a harness pins
`AppTheme` + `LocalWindowSizeClass` (its 0×0 default is a landscape trap) +
shot size; goldens live in `shared/src/androidHostTest/snapshots/` as PNG.
Smokes: v2 `runComposeUiTest` composes the production `App()` with fake
repositories via Koin (lazy DI keeps `SupabaseClient` unconstructed), drives
flows by semantics + board-tap offsets. CI: `tests.yml` (PR, ubuntu),
`record-goldens.yml` (dispatch, bot-commits goldens), `ios-tests.yml`
(nightly, macOS) — all verifiable headlessly via `gh`.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 0. GitHub + gh CLI | Canonical GitHub origin; authenticated `gh` loop | User-side `gh auth login`; owner/visibility choices |
| 1. Roborazzi foundation | Pipeline proven; first board goldens; record/verify invocations settled | Task wiring under AGP 9 KMP plugin (fallback: `-Droborazzi.test.*` properties) |
| 2. Full golden matrix | ~44 goldens across board/scaffolds/panel × themes; ritual documented | Over-coverage → rubber-stamping; mitigated by documented ritual |
| 3. uiTest + happy path | Harness + digital flow green on iOS sim + wasm | v2-under-Robolectric on Android host unproven (documented fallback) |
| 4. Replay+delete smoke | Second flow incl. Nav3 pop + delete confirmation | Eval UI must be network-free via fake |
| 5. CI workflows | PR gate, CI-recorded goldens, iOS nightly; deliberate-break demo | wasm/Chrome + Android SDK on runners; golden re-record diff is large by design |

**Prerequisites:** user performs `gh auth login`; GitHub account ready; no
repository secrets needed for any test workflow.
**Estimated effort:** ~4–6 sessions across 6 phases (0–5).

## Open Risks & Assumptions

- Roborazzi Gradle-plugin task generation under the new AGP KMP plugin is
  unverified — property-based invocation is the guaranteed fallback.
- `runComposeUiTest` v2 on the Android host (Robolectric) may not work; smoke
  contract targets are iOS sim + wasm, Android host is best-effort.
- Material3 alpha pin means M3 upgrades will legitimately move pixels —
  expected, handled by the re-record ritual.
- Assumes the GitHub repo can be private on the free plan within Actions free
  tier (2,000 min/mo; PR gate is Linux-only by design).

## Success Criteria (Summary)

- A deliberately broken board color fails the PR gate with a readable diff
  artifact; reverting turns it green (demonstrated in Phase 5).
- Both smoke flows green on iOS simulator + wasm with zero secrets configured.
- An intentional UI tweak can be shipped by following the AGENTS.md ritual
  (dispatch record → review diffs → merge) without touching a local Mac
  recording.
