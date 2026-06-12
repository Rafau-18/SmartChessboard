<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Replay Seeded Games (S-02)

- **Plan**: context/changes/replay-seeded-games/plan.md
- **Mode**: Deep
- **Date**: 2026-06-12
- **Verdict**: SOUND — all findings triaged & fixed (2026-06-12)
- **Findings**: 0 critical, 1 warning, 4 observations — all resolved

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | PASS |
| Plan Completeness | PASS |

## Grounding

8/8 paths ✓ (`seed.sql` correctly absent — Phase 2 creates it). Symbols ✓:
`legalMoves`/`validate`/`applyMove` (ChessRules.kt), `Position.start()` + `pieceAt()`
(Position.kt), `Move.promoteTo` (Move.kt), `GamesRepository.listMyGames`/`GameSummary`.
`GameStatus` collision is real — `domain/games/GameStatus` (enum IN_PROGRESS/FINISHED in
GameSummary.kt) vs `domain/chess/GameStatus` (sealed). Contract §1.3 confirms `index =
file + 8*rank`, a1=0, h8=63 — matches Position.kt. `config.toml [db.seed] sql_paths =
["./seed.sql"]` ✓. `games_rls.test.sql` auth.users-before-games insert pattern ✓.
Migrations present ✓. brief↔plan consistent. Project pins: CMP 1.11.1, Kotlin 2.4.0,
androidx-lifecycle 2.11.0-beta01, kotlinx-serialization 1.11.0.

Nav3 versions verified with the user (2026-06-12): `navigation3-ui` is stable at **1.1.1**;
`lifecycle-viewmodel-navigation3` has stable `2.10.0` (18 Mar 2026) and `2.11.0-beta01`
(May 2026). Per JetBrains docs, web browser-history support landed in the base library at
**1.1.0**; `@Serializable` NavKeys + explicit polymorphic registration are a permanent
non-JVM constraint (iOS/wasm), not a stability caveat.

## Findings

### F1 — Local corrupted-PGN check (4.6) has no owner path

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 2 §3 (local seed) ↔ Phase 4 manual 4.6
- **Detail**: `seed.sql` inserts games for two fixed test-user UUIDs, but the app's
  History calls `listMyGames()`, RLS-scoped to `auth.uid()`. A developer signing into the
  local app via Google OAuth gets a fresh random UUID, not a seeded fixed UUID — so seeded
  games never appear in the local app's History. The cloud script (Phase 2 §4) solves this
  for the hosted project but there is no local equivalent. 4.5 survives ("local stack OR
  cloud" — cloud rescues it); 4.6 (corrupted-PGN truncation banner) is local-ONLY by design
  — the cloud seed omits the corrupted game — so it cannot be exercised through the app as
  written. 2.4 is unaffected (verifies via Studio/psql, not the app).
- **Fix A ⭐ Recommended**: Verify the banner directly via a Compose preview/host fed a
  hand-built corrupted `ReplayGame`, not through History.
  - Strength: Truncation semantics already fully unit-proven in the Phase-1 corpus; the
    banner is a UI concern a direct preview checks better, and on all three targets.
  - Tradeoff: Drops a "real DB row → real History" path for the corrupted case — never
    reachable locally anyway.
  - Confidence: HIGH — Phase 1 already asserts the truncation model.
  - Blind spot: None significant.
- **Fix B**: Give the local app user ownership, mirroring the cloud script (document "note
  your local UUID from Studio → Users, run a local-user seed").
  - Strength: Keeps 4.6 as a true end-to-end check through real History.
  - Tradeoff: Re-introduces the manual UUID gate locally that the fixed-UUID seed avoided.
  - Confidence: MED — depends on local Google OAuth against the local stack working.
  - Blind spot: Whether local OAuth is wired for the local stack isn't established.
- **Decision**: FIXED via Fix A (2026-06-12). Plan edits: Phase 2 §3 drops the corrupted-PGN
  seed row (no consumer left); Phase 4 manual 4.6, Progress 4.6, and Testing Strategy step 3
  now verify the banner via a ReplayScreen preview fed a hand-built corrupted `ReplayGame`;
  cloud-seed contract no longer says "minus the corrupted one".

### F2 — Nav3 dependency versions (was: second artifact unpinned)

- **Severity**: 🔎 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 4 §1 (deps), Critical Details, Phase 5 lessons write-back
- **Detail**: Plan pinned `navigation3-ui:1.0.0-alpha06` and left
  `lifecycle-viewmodel-navigation3` unpinned ("compatible with the existing lifecycle pin"),
  and framed Nav3 as alpha throughout. User confirmed `navigation3-ui` is **stable 1.1.1**.
  The companion wrapper shares the `org.jetbrains.androidx.lifecycle` group with the project's
  `lifecycle-viewmodel-compose`/`runtime-compose` (pinned `2.11.0-beta01`); lifecycle artifacts
  must resolve to one version, so the stable `2.10.0` wrapper would split the group.
- **Fix**: Pin `navigation3-ui = 1.1.1` (new `navigation3` catalog version) and pin
  `lifecycle-viewmodel-navigation3` via the existing `androidx-lifecycle` ref (`2.11.0-beta01`)
  so the lifecycle group stays in lockstep (no new beta surface — project already runs it).
  Drop "alpha" framing in Critical Details + lessons write-back; keep the `@Serializable`
  registration requirement (permanent non-JVM constraint).
- **Decision**: FIXED (plan + brief edited 2026-06-12)

### F3 — iOS/wasm piece rendering not visually checked until Phase 5

- **Severity**: 🔎 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3 §1 + manual 3.4
- **Detail**: 12 Cburnett SVGs are converted to Compose vector drawables; the plan asserts
  "conversion preserves viewport proportions" but the only guard is manual 3.4 — Android
  only. A botched path/viewBox renders identically wrong on iOS/wasm but isn't eyeballed
  until the Phase-5 three-surface E2E.
- **Fix**: Add a cheap web visual check to Phase 3 (`:webApp:wasmJsBrowserDevelopmentRun`
  is already in the toolchain).
- **Decision**: FIXED (2026-06-12). Phase 3 gains manual bullet + Progress 3.5: same board
  renders crisp on web, catching vector-conversion distortion at asset-creation time.

### F4 — Web browser-history excluded on a stale premise

- **Severity**: 🔎 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: "What We're NOT Doing", Phase 5 lessons write-back, manual 5.4
- **Detail**: Plan excluded web browser-URL routing because "Nav3 doesn't support it yet" and
  enshrined that as a permanent `lessons.md` rule. Browser-history support landed in Nav3
  **1.1.0** (so 1.1.1 has it). Recording it as "unsupported" would bake a false constraint
  into the project's lessons.
- **Fix (revised per user, 2026-06-12)**: **Wire web browser-history this slice** rather than
  defer. Nav3 1.1.x's browser-navigation binding (terrakok integration, `wasmJsMain`, route ↔ URL
  fragment) makes browser Back/Forward map to the nav stack (Replay → History). Added as Phase 4
  §9 (`expect`/`actual`, no-op on Android/iOS) with a **verify-first** sub-step (confirm the 1.1.1
  binding API before relying on it — version intel is snapshot-level) and manual checks 4.7 / 5.4.
  Only a designed/shareable URL scheme stays out of scope. No COOP/COEP dependency (that was a
  Room/OPFS concern, absent here). Note: web is roadmap-"Parked" (FR-020, mobile primary), so this
  knowingly pulls a further sliver of FR-020 forward — accepted as a user decision.
- **Decision**: FIXED — wired (plan + brief edited 2026-06-12)

### F5 — Board view risks hardcoding phone size (adaptive guardrails)

- **Severity**: 🔎 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architectural Fitness
- **Location**: Phase 3 §2 (ChessBoardView), Phase 4 §6 (ReplayScreen)
- **Detail**: `ChessBoardView` is the reuse contract S-03/S-04 inherit, and web is a
  first-class target here. Building it phone-only (fixed dp, single column stretched on
  desktop) would force a later rewrite of all consumers. User chose (D) "cheap guardrails
  only" — full adaptive multi-pane is deferred to an app-wide follow-up.
- **Fix**: (1) `ChessBoardView` renders into its `modifier`'s box — `aspectRatio(1f)`, scales
  to available size, no hardcoded phone dp. (2) ReplayScreen stays single-column but caps the
  board's max width and centres it so web/desktop doesn't stretch. Multi-pane explicitly
  out of scope this slice.
- **Decision**: FIXED (plan edited 2026-06-12)

## Follow-ups (out of this slice)

- App-wide adaptive/responsive layout (WindowSizeClass, board + move-list multi-pane on wide
  screens, nav rail vs bottom bar, foldable) — recorded in `roadmap.md` as a scope note under
  **S-03** (its natural home: board + eval + moves side-by-side), per decision D (2026-06-12).
  S-02's size-driven `ChessBoardView` + capped board prepare it. Tooling: `adaptive` + Nav3 scenes.
- Designed/shareable web URL scheme (pretty paths, per-ply deep links) — out of scope; the basic
  browser Back/Forward binding is now wired in-slice (F4).
