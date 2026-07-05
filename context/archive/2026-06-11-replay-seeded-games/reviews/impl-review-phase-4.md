<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Replay Seeded Games (S-02)

- **Plan**: context/changes/replay-seeded-games/plan.md
- **Scope**: Phase 4 of 5 (commit 1dabbad)
- **Date**: 2026-06-12
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 3 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Evidence summary

- Drift scan: 9/9 planned items MATCH, zero MISSING, zero scope creep. The §9 deviation (browser binding is NOT in base Nav3 1.1.1; separate `com.github.terrakok:navigation3-browser:1.1.0` artifact used, wasmJs-only) is the plan's own "verify-first" contingency, documented in the commit message and code comments — not silent drift.
- Nav3's headline risk checks out: both NavKeys are `@Serializable` with explicit polymorphic registration (Routes.kt:29-38) and the configuration is actually passed to `rememberNavBackStack` (App.kt:50). Verified against navigation3-runtime 1.1.1 sources: a misconfiguration would fail fast; this code is correct.
- Automated criteria re-verified at review time: `:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`, `:shared:wasmJsTest` — BUILD SUCCESSFUL. ktlint: 0 violations in handwritten sources (see F5 for the generated-code nuance).
- yarn.lock prior (lessons.md): verified clean — terrakok artifact is pure Kotlin/Wasm (no npm deps); committed lock byte-identical to regenerated.
- Manual 4.6 has direct diff evidence (ReplayScreenPreviews.kt cites §4.6 in its KDoc). 4.5/4.7 are unverifiable-by-diff manual claims — accepted.
- Pattern compliance: ReplayViewModel/ReplayScreen/ReplayViewModelTest/AppModules all consistent with History siblings (StateFlow + asStateFlow, sealed UiState, keyed koinViewModel, StandardTestDispatcher, shared FakeGamesRepository extended in Phase 2 commit 4c8bbfb). BrowserNavigation is the module's first expect/actual; platform-suffix convention is standard KMP.

## Findings

### F1 — Web browser-history binding silently dies after sign-out → sign-in (cannot rebind)

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality (reliability, wasm-only)
- **Location**: SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/App.kt:53 + upstream terrakok navigation3-browser library
- **Detail**: The upstream library sets a process-global one-shot flag (`BrowserHistoryIsInUse`, compareAndSet) that is never reset — no try/finally, no onDispose (verified in upstream source and the cached 1.1.0 klib). The binding lives inside the SignedIn branch, so sign-out disposes it; on re-sign-in a new back stack binds, compareAndSet fails, the library logs "BrowserHistory has already been bound to another backstack!" and returns. For the rest of the page session browser Back/Forward stop driving the nav stack and the URL keeps a stale fragment. The popstate listener itself is cleaned up correctly (callbackFlow/awaitClose) — degradation, not a leak or crash. Page reload fully recovers.
- **Fix A ⭐ Recommended**: Accept for MVP; record in lessons.md as a known web constraint (Phase 5 already writes there) and file an upstream issue for flag reset on cancellation.
  - Strength: Zero code risk; sign-out → sign-in inside one web page session is a rare path with a trivial user recovery (reload).
  - Tradeoff: A real, silent degradation ships.
  - Confidence: HIGH — behavior derived from upstream source, not speculation.
  - Blind spot: Not reproduced live end-to-end on web; inferred from library code.
- **Fix B**: Hoist one long-lived back stack + binding above the session gate; rebuild stack contents on auth changes.
  - Strength: Binds exactly once per page session — survives sign-out cycles.
  - Tradeoff: Restructures the App.kt gate S-01 deliberately kept simple; nav stack exists while signed out and needs guarding.
  - Confidence: MED — sound in principle, not prototyped.
  - Blind spot: Interaction with saved-state restore when hoisted is unverified.
- **Decision**: ACCEPTED via Fix A — accepted for MVP; lesson recorded in lessons.md; upstream issue to be filed for flag reset on cancellation.

### F2 — restoreKey never returns null → duplicate HistoryKey root entry on first web load

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (wasm-only)
- **Location**: SmartChessboard/shared/src/wasmJsMain/kotlin/org/rurbaniak/smartchessboard/presentation/navigation/BrowserNavigation.wasmJs.kt:34-44
- **Detail**: On first visit (`window.history.state == null`) the library calls `restoreKey(window.location.hash)` and adds a non-null result to the stack. The app maps everything — including the blank hash of a plain first visit — to `HistoryKey`, so the stack deterministically becomes `[HistoryKey, HistoryKey]`, which is then persisted into history.state and restored on every reload. Invisible today (History has no back affordance) but breaks the "root is a single HistoryKey" assumption for future predictive-back / scenes work. The library's own sample returns null for unrecognized fragments.
- **Fix**: Return null for a blank fragment; keep `HistoryKey` only for an explicit `#history` fragment (and `ReplayKey` for `#replay?id=…`).
- **Decision**: FIXED — zmieniono `else -> HistoryKey` na `"history" -> HistoryKey` + `else -> null` w `BrowserNavigation.wasmJs.kt:34-44`.

### F3 — Plan's Phase 5 lessons.md write-back text fossilizes the wrong binding coordinates

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/changes/replay-seeded-games/plan.md:488-496 (Phase 5 §2 contract) + plan.md:54-63 (Key Discoveries)
- **Detail**: Phase 4 legitimately discovered the browser binding is NOT in base navigation3-ui 1.1.1 and used `com.github.terrakok:navigation3-browser:1.1.0` (wasmJs-only, verified via klib symbol dump per the commit message). But the plan's Phase 5 contract still dictates a lessons.md entry saying browser-history is wired via "Nav3's browser-navigation binding" merged into the base library. If Phase 5 executes that text verbatim, a durable project rule records dependency coordinates that don't exist — and future sessions trust lessons.md.
- **Fix**: Amend plan.md — Phase 5 §2 contract text and the Key Discoveries bullet — to record the terrakok navigation3-browser:1.1.0 reality.
- **Decision**: FIXED — poprawiono plan.md: Key Discoveries (linia ~61) i Phase 5 §2 contract (linia ~493) odzwierciedlają `navigation3-browser:1.1.0` jako osobny artefakt.

### F4 — PGN parsing runs on the main dispatcher

- **Severity**: 👁 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (performance)
- **Location**: SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/replay/ReplayViewModel.kt:66
- **Detail**: `parsePgn` (one legalMoves enumeration per ply) executes once per load inside viewModelScope on Main. Fine for bounded seeded games and a no-op on single-threaded wasm; a pathological 300-move PGN could jank mobile once real games arrive (S-04).
- **Fix**: Optional now — wrap the getGame+parse body in `withContext(Dispatchers.Default)`; or defer to S-04.
- **Decision**: FIXED — dodano `withContext(Dispatchers.Default) { parsePgn(record.pgn) }` w `ReplayViewModel.kt:68`; `getGame` (I/O suspend) pozostaje na Main.

### F5 — "ktlint clean" criterion isn't reproducible verbatim

- **Severity**: 👁 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: context/changes/replay-seeded-games/plan.md success criteria (4.4, and 1.4/3.3 wording)
- **Detail**: Bare `ktlint` from SmartChessboard/ exits 1 after any build — 222 violations, ALL in shared/build/generated/ (BuildKonfig, Compose resource collectors). Handwritten sources: 0 violations, so the criterion's substance holds, but the literal command only passes on a clean checkout.
- **Fix**: Scope the invocation, e.g. `ktlint '!**/build/**'`, in Phase 5's regression step and future plans.
- **Decision**: SKIPPED — kryterium substancją poprawne; komenda zostanie uściślona gdy Phase 5 regression step będzie pisany.
