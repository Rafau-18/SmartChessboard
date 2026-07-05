# Research: screenshot & UI testing for Compose Multiplatform (condensed)

Facts verified against live sources 2026-07-03/04 during the planning debate.
Full reasoning lives in the planning conversation; this file preserves what the
implementer needs.

## Tool landscape (as of 2026-07)

| Tool | Version / status | Platforms | Notes |
| --- | --- | --- | --- |
| **Roborazzi** | 1.64.0 (2026-06-13), mature, active | Android via Robolectric RNG (JVM); Desktop + iOS experimental | Chosen. Tasks `recordRoborazzi*` / `compareRoborazzi*` / `verifyRoborazzi*` or system properties `roborazzi.test.record|compare|verify=true`. Requires Robolectric 4.10+, `@GraphicsMode(GraphicsMode.Mode.NATIVE)`. Thresholds: `RoborazziOptions.CompareOptions(changeThreshold)` + `SimpleImageComparator(maxDistance, hShift, vShift)`. WebP goldens via `roborazzi.record.image.extension=webp`, `roborazzi.record.resizeScale`. HTML report under `build/reports/roborazzi`. |
| Paparazzi | 2.0.0-alpha05 (2026-05-20) | Android-only (layoutlib) | Rejected: cannot coexist with Robolectric in one module (issue #425) and we want Robolectric for host-side uiTest. |
| Google `com.android.compose.screenshot` | 0.0.1-alpha15, still alpha | Android-only | Rejected: API breaks between alphas (`@PreviewTest` since alpha10). |
| Roborazzi-iOS | experimental since 1.13.0 | iOS sim | Rejected for now: minimal feature set, maintainer has "no concrete roadmap to unify" (issue #807, 2026-02). |
| swift-snapshot-testing | 1.19.2 | iOS native | Could snapshot `ComposeUIViewController`, but Metal/Skiko layer capture unconfirmed anywhere authoritative. Out of scope. |
| Playwright `toHaveScreenshot` | stable | Web (wasm canvas) | CMP web renders to one canvas; a11y DOM mirror (`cmp_a11y_root`) only fixed to have real dimensions in 2026-05 (compose-multiplatform-core#3035). Out of scope for this change. |
| **compose.uiTest v2** | CMP 1.11 (we're on 1.11.1) | commonTest → Android / iOS sim / desktop / **wasm** | Chosen for smoke flows. Old `runComposeUiTest` deprecated in favor of v2 (`androidx.compose.ui.test.v2.runComposeUiTest`); v2 uses `StandardTestDispatcher` (no eager coroutines — `waitForIdle()`/`waitUntil()` discipline). On web the test body runs with delays skipped and returns a `Promise`. |

## Key community practice

- Shared CMP UI is screenshot-tested **once on the JVM** (common composables
  compile into the Android target; Skia renders everywhere) — JVM goldens are
  the accepted proxy. Per-platform goldens are an escalation, not table stakes.
- Golden determinism rule: **record on the environment that verifies** — macOS
  and Linux rasterize fonts differently; goldens recorded on a Mac routinely
  fail on Linux CI. Teams record on CI.
- PR review of diffs: Roborazzi companion-branch pattern
  (`takahirom/roborazzi-compare-on-github-comment-sample`) posts PR comments
  with diff images; simpler tier = upload diff reports as Actions artifacts.
- Anti-pattern: golden-everything → every intentional UI change reddens dozens
  of tests → rubber-stamp re-records → the layer stops protecting anything.

## Costs (verified 2026-07)

- All chosen tools are OSS — license cost 0.
- GitHub Actions, private repos: 2,000 free min/month; Linux ×1, **macOS ×10**;
  overage Linux $0.006/min, macOS $0.062/min. → JVM golden verify + wasm tests
  on PR are effectively free; iOS simulator suite goes nightly/manual.
- Cloud visual services (Percy $199/mo, Chromatic $179/mo, Argos $100/mo,
  Lost Pixel cloud $100/mo, all with free tiers ~5–7k screenshots) —
  deliberately not used; local HTML report + git goldens suffice at this scale.
- Golden repo weight: ~40–55 component goldens as WebP at `resizeScale 0.5` ≈
  low single-digit MB. Plain git is fine; no LFS needed.

## Repo facts that shaped the plan

- No Compose UI tests, no screenshot infra, no CI existed before this change;
  remote was Bitbucket (`git@bitbucket.org:<user>/smartchessboard.git`).
- `tech-stack.md` had already declared `runComposeUiTest` + fakes-first;
  this change implements that declaration.
- `mobile-landscape-layout` (merged 2026-07-04, `3aec475`) introduced
  `WindowSizeClass` via `LocalWindowSizeClass` + pure policy functions
  (`screenChrome`, `boardArrangement`, `boardResizeEnabled`) and
  `BoardScreenScaffold` — layout *logic* is unit-tested, pixels are not; its
  plan-brief explicitly deferred visual truth to a manual gate.
- `LocalWindowSizeClass` defaults to `WindowSizeClass(0, 0)` → height-compact →
  SidePane+LeftRail. Tests MUST provide an explicit class per variant.
- Fakes already exist: `FakeGamesRepository`, `FakeAuthRepository`,
  `FakeEvalRepository`, `FakeGameJournal` (+ `EmulatedBoard`).
- `withHostTest { isIncludeAndroidResources = true }` already set in
  `shared/build.gradle.kts` — Robolectric-friendly.
- Versions: Kotlin 2.4.0, CMP 1.11.1, AGP 9.0.1, Material3 1.11.0-alpha07
  (alpha pin ⇒ M3 upgrades will move component pixels ⇒ re-record ritual).
