<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: UI refresh — theming, scalable board, animations

- **Plan**: `context/changes/ui-theming-and-scaling/plan.md`
- **Scope**: Full plan (Phases 1–7, all Progress checkboxes `[x]`)
- **Date**: 2026-06-29
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical · 1 warning · 4 observations

> Nothing blocks — all manual gates passed and the change is merged. The verdict
> reflects one real-but-reachability-gated contract bug (F1) plus test-coverage gaps.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS (cosmetic drift only) |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | WARNING |

## Findings

### F1 — readBoardSize() is not a total read; throws on wasm for a non-numeric stored value

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Reliability)
- **Location**: data/preferences/SettingsUiPreferences.kt:57
- **Detail**: The class KDoc (lines 18-20) and inline comment (55-56) both claim "Reads are total." `readThemeMode`/`readMoveListMode` honour that via `getStringOrNull` + `entries.firstOrNull`. But `readBoardSize` uses `settings.getFloat(...)` directly. On WasmJS the bound `Settings` is `StorageSettings`, whose `getFloat` is `delegate[key]?.toFloat() ?: default` — `String.toFloat()` throws `NumberFormatException` (a `kotlin.Error` / `Throwable` on wasm) for a non-numeric stored value. This runs in the `_boardSize` field initializer, executed when Koin builds `single<UiPreferences>` at startup → uncaught crash at app launch. `clampBoardSize` cannot help — it runs only AFTER `getFloat` returns. This is exactly the wasm-`Throwable` totality rule `lessons.md` warns about, and the plan (plan.md:30) flagged "follow the rule if a read can throw." Reachability: the app's own writer always uses `putFloat`, so `ui.boardSize` is always numeric in normal operation — the throw needs external/manual store corruption or a future key-type change. Real contract violation + robustness gap, NOT a guaranteed in-app crash. The existing test (SettingsUiPreferencesTest.kt:81) uses `MapSettings`, whose `getFloat` is a safe cast, so the suite structurally cannot catch this — a false "reads are total" signal.
- **Fix**: `clampBoardSize(settings.getFloatOrNull(BOARD_SIZE_KEY) ?: BOARD_SIZE_DEFAULT)` — `StorageSettings.getFloatOrNull` uses `toFloatOrNull()`; Android/iOS `*OrNull` variants are guarded too. Add a `wasmJsTest` over `StorageSettings` (not `MapSettings`) storing a non-numeric `ui.boardSize` and asserting it resolves to the default instead of throwing.
  - Strength: Makes the documented "total read" contract actually hold on all three targets; one-line change, mirrors the enum reads' OrNull pattern already in this file.
  - Tradeoff: None of note.
  - Confidence: HIGH — code path traced firsthand; the adversarial agent reportedly reproduced the throw with a wasmJsTest.
  - Blind spot: Did not re-run the wasm suite in this review to re-confirm the repro (reasoned from the StorageSettings source).
- **Decision**: PENDING

### F2 — Multi-ply jump with a net single-move-shaped diff animates a wrong slide

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality (Compose correctness)
- **Location**: presentation/board/BoardMoveAnimation.kt:70-91 (diffSingleMove)
- **Detail**: `diffSingleMove` is purely observational on the board diff, independent of plies traversed. Common Replay jumps (goToStart/goToEnd, adjacent, most jumpTo) safely resolve to null → instant render (covered by the "multi-ply jump → null" test). Residual edge: a deliberate multi-ply `jumpTo` whose NET board change is single-move-shaped (e.g. a knight leaves and returns over intervening plies, leaving exactly one piece net-displaced) passes the (1,1,0)/(1,0,1) branch and animates a wrong/long slide. Realizable via `jumpTo`, but requires a net-cancelling line — rare. Related (lower): a frame-perfect race where a superseded slide's `LaunchedEffect` runs `slide = null` after a new slide committed could briefly drop one overlay frame (couldn't be grounded as reproducible).
- **Fix**: Gate the slide on a single-ply step — only animate when `abs(newPly - prevPly) == 1`; multi-ply jumps then always render instantly regardless of net diff shape. (Play is unaffected — it only ever advances one ply.)
  - Strength: Eliminates the entire class of net-cancelling-diff misfires with a step-distance guard rather than more diff heuristics.
  - Tradeoff: ChessBoardView would need the ply index (or a "did the user step by one" signal) threaded in; currently it diffs Position instances only.
  - Confidence: MEDIUM — fix shape is clear; the plumbing of ply distance into the board view needs a small contract addition.
  - Blind spot: Whether PhysicalPlay ever presents a multi-ply Position jump (likely not — physical moves are one at a time).
- **Decision**: PENDING

### F3 — ThemeViewModel.cycle() wrap-around has no unit test

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria (test coverage)
- **Location**: presentation/theme/ThemeViewModel.kt:25-33
- **Detail**: `cycle()` is deterministic pure logic implementing the plan's Phase-1 contract (System → Light → Dark → System wrap). It's the only new pure ViewModel intent in the change with no test — covered only by manual gate 1.6. A broken wrap-around (e.g. DARK → LIGHT, or a dropped state) would compile and ship silently — the regression class the plan's automated discipline exists to catch. `setThemeMode` persistence IS tested; the cycle ordering is not.
- **Fix**: Add `ThemeViewModelTest` in commonTest asserting `cycle()` drives SYSTEM→LIGHT→DARK→SYSTEM over `ThemeViewModel(SettingsUiPreferences(MapSettings()))`. Cheap, runs on Native.
  - Strength: Pins the only untested pure intent in the change; trivial and Native-safe.
  - Tradeoff: None.
  - Confidence: HIGH — pattern identical to existing SettingsUiPreferencesTest construction.
  - Blind spot: None significant.
- **Decision**: PENDING

### F4 — boardSide() sizing geometry (incl. the phone reserved-width fix) has no automated regression net

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria (test coverage)
- **Location**: presentation/board/ResizableBoardBox.kt:113-130
- **Detail**: Phase 2 extracted + tested `clampBoardSize` (good). But `boardSide()` — the function with the non-trivial branching (`usableWidth − reservedWidth`, `BOARD_MAX_SIDE` cap, viewport-height bound, MIN floor) added/changed in Phases 6–7 — is private and takes Compose `Dp`, so it's untestable as written. The reserved-width-on-narrow fix (the one that stopped the eval bar being pushed off-screen on phones, gate 6.5) and the height-bound interaction have zero automated coverage. `Dp` math is value-class, so this is a missing-regression-net concern, not a Native-divergence risk.
- **Fix**: Extract the pure side computation (return `Float`/`Dp`; make it `internal` or move to `domain`) and add table tests: narrow ignores size & subtracts reservedWidth; wide scales then caps at BOARD_MAX_SIDE; height bound wins when shorter; floor holds. Or explicitly accept manual-only coverage.
  - Strength: Protects the phone eval-bar fix and the cap/height-bound interaction from silent regression.
  - Tradeoff: A small refactor to make the computation testable (extract from the composable).
  - Confidence: MEDIUM — extraction is straightforward; deciding the exact signature (Dp vs Float) needs a glance at call sites.
  - Blind spot: None significant.
- **Decision**: PENDING

### F5 — Eval-bar label-legibility threshold (0.08f) is unpinned and marginal on the smallest bar

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria (test coverage)
- **Location**: presentation/replay/EvalComponents.kt:135
- **Detail**: The plan's Phase-3 "all-dark forced-mate-for-Black" legibility case is implemented as `labelColor = if (animatedFraction <= 0.08f) fill else label`. Hold-last and 0.0→0.5 WERE extracted and tested; this threshold was left inline in the composable, so no test pins it. Endpoints are legible; the only marginal band is the fill edge crossing the glyph box on the smallest ~200dp bar (a ~1dp dark-on-dark sliver) — cosmetic, not illegible.
- **Fix**: Optional — extract `evalBarLabelColor(fraction, fill, label)` + one test (0.05 → fill, 0.5 → label); or raise the threshold slightly / add a thin label scrim. Accept-as-is is also defensible (display-only).
  - Strength: Pins the legibility contract the plan explicitly called out, and removes the marginal small-bar sliver.
  - Tradeoff: Minor; a tiny extraction for a display-only colour choice.
  - Confidence: HIGH — isolated pure helper.
  - Blind spot: None significant.
- **Decision**: PENDING

## Notes (not findings)

- **Plan Adherence is PASS** with cosmetic, behaviour-correct drift only: the History theme control is a `TextButton`+live-label (not the planned `IconButton` — arguably better); `BOARD_MAX_SIDE` lives in `ResizableBoardBox.kt` and `clampBoardSize` in `domain/preferences/BoardSize.kt` rather than where the Phase 6/2 text placed them (a documented improvement).
- **Scope clean**: all source changes confined to `presentation/` / `*preferences/` / DI / `App.kt` root; no game-logic, persistence-schema, BLE, or Edge-Function changes. Post-plan refinements R.1–R.5 are documented in `manual-verification.md`.
- **Automated re-run this review**: `:shared:testAndroidHostTest` green, ktlint clean. iOS-sim / wasm / assembleDebug passed at commit time, not re-run here.
- **Color migration verified clean**: all `Color(0x` literals confined to `presentation/theme/`.
- **Review method**: two parallel generalist passes (plan-drift + safety/quality/pattern) reported essentially clean; a four-agent adversarial verification pass (piece-slide, eval hold-last, state/wasm, completeness critic) surfaced F1–F5, which the generalist passes missed.
