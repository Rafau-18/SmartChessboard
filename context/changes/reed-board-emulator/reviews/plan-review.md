<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Reed-Switch Board Emulator (F-02)

- **Plan**: context/changes/reed-board-emulator/plan.md
- **Mode**: Deep
- **Date**: 2026-06-11
- **Verdict**: REVISE → **SOUND** (all 4 findings resolved in triage 2026-06-11)
- **Findings**: 0 critical · 3 warnings · 1 observation — all FIXED

## Triage outcome (2026-06-11)

All findings resolved in the plan → post-fix verdict **SOUND**.

- **F1** — FIXED (Fix in plan): `statusInterval: Duration = 30.seconds` constructor param added (`INFINITE` = off).
- **F2** — FIXED (Fix A): `EmulatedBoard` + `BoardScenarios` moved to `commonTest`; codec + port stay in `commonMain`.
- **F3** — FIXED (Fix differently): iOS-sim pulled into Phase 2 & 3 verification; WasmJS stays the optional Phase 4 check.
- **F4** — FIXED (Fix a): one dated, no-user-facing-impact `prd.md` mirror line added as Phase 2 step #5.

Mechanical re-check after edits: 4 ↔ 4 phase/progress headings, one `## Progress`, 18/18 progress items intact,
no stray checkboxes, emulator/scenarios consistently under `commonTest`.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | WARNING |
| Blind Spots | WARNING |
| Plan Completeness | WARNING |

## Grounding

paths 6/6 ✓ · symbols 3/3 ✓ (`squareOf`/`fileOf`/`rankOf` in Square.kt) · gradle tasks 8/8 ✓
(`compileAndroidMain`, `compileKotlinWasmJs`, `compileKotlinIosSimulatorArm64`, `testAndroidHostTest`,
`iosSimulatorArm64Test`, `wasmJsTest` all exist) · deps 3/3 ✓ (`kotlinx-coroutines-core`,
`kotlinx-coroutines-test`, `kotlin-test` wired into `:shared`) · golden vectors 4/4 hand-checked ✓
(e4→sq28→`0x5C`; lift e2→`0x0C`; start snapshot→`FF FF 00 00 00 00 FF FF`) · roadmap IDs F-02/S-06–S-09 ✓ ·
PRD OQ-1 + FR-FW-005 ✓ · progress 18/18 mapped to success criteria ✓ · brief↔plan ✓

## Strengths (no action)

- Golden frames hand-derived from §1.3 and arithmetically correct; the encode→decode "message-identical"
  pipeline and hand-written vectors correctly make the research's self-consistency-trap mitigation load-bearing.
- `## Progress` block is well-formed: exactly one heading at the bottom, all 4 phase↔progress headings match
  exactly, every success-criterion bullet has a matching `- [ ] N.M`, zero stray checkboxes in phase bodies.
- Scope ("What We're NOT Doing") is sharp and consistent with PRD OQ-1 (programmatic emulator, no GUI,
  recorded-fixture replay deferred).
- §1.3/§1.4 contract fidelity confirmed across all four board→mobile and three mobile→board message types.

## Findings

### F1 — Always-on ~30 s DEVICE_STATUS timer is hard-coded, not injectable

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Critical Implementation Details · Phase 3 (EmulatedBoard contract) · Phase 4 (demo end-to-end)
- **Detail**: `connect()` starts a periodic ~30 s DEVICE_STATUS job that runs for the whole connected
  lifetime. Phase 4's demo (and the Phase 3 diagnostic test) assert "full ordered event lists" while ALSO
  advancing virtual time for the 10 Hz diagnostic job. Any test whose advanced virtual time crosses a 30 s
  boundary gets DEVICE_STATUS events interleaved at hard-to-predict positions, making exact-ordering
  assertions brittle. The constructor parameterizes `eventDelay` for a *hypothetical future* dev tool, yet
  leaves the ~30 s cadence that tests need to control today hard-wired — an inverted priority.
- **Fix**: Make the status cadence a constructor parameter, mirroring `eventDelay` — e.g.
  `statusInterval: Duration = 30.seconds`, with `Duration.INFINITE` (or null) meaning "no periodic status".
  The demo and most behavior tests set it to INFINITE; the dedicated periodic-status test sets 30 s.
  - Strength: Keeps the plan's "assert exact ordered list" discipline intact; symmetric with the eventDelay
    knob already in the contract.
  - Tradeoff: One more constructor param (small surface bump).
  - Confidence: HIGH — kotlinx-coroutines-test is wired and the jobs run on the injected scope; standard way
    to make periodic coroutine jobs test-controllable.
  - Blind spot: None significant.
- **Decision**: FIXED (Fix in plan) — `statusInterval: Duration = 30.seconds` added to the EmulatedBoard
  constructor; `Duration.INFINITE` disables the periodic status. Updated Critical Implementation Details,
  Phase 3 contract + connect() behavior + tests, and Phase 4 demo (constructed with INFINITE).

### F2 — Emulator + scenario DSL placed in production commonMain

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architectural Fitness
- **Location**: Phase 3 (EmulatedBoard.kt) · Phase 4 (BoardScenarios.kt, "Main source (not test)")
- **Detail**: EmulatedBoard and BoardScenarios live in commonMain, so they compile into Android / iOS / web
  *release* binaries. Today the only consumers are tests; the plan's stated production consumer is S-06 dev
  tooling ("at the earliest"), which doesn't exist. The plan itself cites the F-01 precedent "no DI wiring
  until a consumer exists" — the same "don't build for a not-yet-existing consumer" logic cuts against
  shipping the emulator in production main now.
- **Fix A ⭐ Recommended**: Place EmulatedBoard + BoardScenarios in commonTest now; promote to commonMain when
  S-06's dev tool becomes a real production consumer.
  - Strength: Matches the F-01 precedent the plan already cites; nothing test-only reaches release binaries;
    codec/port stay in main so S-09 is unaffected.
  - Tradeoff: A later move (and possibly a test-fixtures source set) when S-06 lands.
  - Confidence: MED — within `:shared`, commonTest isn't visible to a future main-source dev screen, so the
    promotion is real work, not a no-op.
  - Blind spot: Whether S-06's dev tooling will be production code or debug-build-only — unverified.
- **Fix B**: Keep in commonMain as planned, but record it as a conscious "ship test-support in release for
  S-06 reuse" decision.
  - Strength: Zero rework when S-06 reuses it; emulator is tiny pure Kotlin with no deps, so binary cost is
    negligible.
  - Tradeoff: Contradicts the cited F-01 precedent; dead code in release until/unless S-06 uses it.
  - Confidence: HIGH — no technical risk; purely a purity/precedent call.
  - Blind spot: None significant.
- **Decision**: FIXED (Fix A) — EmulatedBoard + BoardScenarios moved to the `commonTest` source set;
  codec (`data/board/protocol/`) and port (`domain/board/`) stay in `commonMain`. Added a durable
  placement note in Critical Implementation Details and updated both file paths + Migration Notes.
  Promotion to `commonMain` deferred to S-06 (same-package source-set move, no API change).

### F3 — Cross-target tests deferred entirely to Phase 4

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 2 & Phase 3 Success Criteria (host-only) vs Phase 4
- **Detail**: Phase 1 compiles all three targets, but Phases 2–3 run tests only on `testAndroidHostTest`
  (JVM). The emulator leans hard on kotlinx-coroutines-test virtual time (`runTest`, 10 Hz / 30 s periodic
  jobs). Coroutine dispatcher / virtual-time behavior has documented differences on Kotlin/Native (iOS) and
  Wasm/JS event loops vs JVM. A timing- or ordering-sensitive test green on host can fail on iOS-sim or Wasm
  — and won't surface until Phase 4, after codec + emulator are "done", forcing late rework.
- **Fix**: Add `iosSimulatorArm64Test` + `wasmJsTest` to Phase 3's automated verification (and ideally run the
  codec tests cross-target at the end of Phase 2). Catches target-specific divergence a phase earlier, while
  the surface is small.
- **Decision**: FIXED (Fix differently) — per user: WasmJS is an optional add-on, so only **iOS-sim** was
  pulled forward. `:shared:iosSimulatorArm64Test` added to Phase 2 (criterion 2.1) and Phase 3 (criterion 3.1)
  automated verification (no Progress renumber — existing test criteria expanded). WasmJS stays the optional
  final cross-target check at Phase 4. Added a "Cross-target cadence" note to Testing Strategy with the rationale.

### F4 — §1 change-control mandates mirroring to prd.md AND prd-firmware.md; plan mirrors only to prd-firmware.md

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Critical Implementation Details · Phase 2 changes #3/#4
- **Detail**: `contract-surfaces.md` "Change control" says: Section 1 (BLE) → `prd-firmware.md` AND `prd.md`,
  plus a dated one-line rationale in the affected PRD. The plan deliberately skips `prd.md`. It's defensible
  (no user-facing wording references snapshot encoding) but it's a documented deviation from the contract's
  own mechanical rule that a later contract audit / impl-review will flag. Slight internal tension: the plan
  calls the edit "the natural extension … currently unwritten" (a clarification §1.3 already implies) yet
  invokes change-control to justify the prd-firmware.md mirror — pick one stance.
- **Fix**: Either (a) add a one-line dated note to `prd.md` Implementation Decisions ("§1.3 snapshot
  bit-packing clarified — no user-facing impact; see contract-surfaces.md") to satisfy the literal rule for
  one line, or (b) record explicitly that this is a non-semantic clarification not triggering full §1
  change-control, so the prd.md skip is principled rather than an exception. Recommend (a).
- **Decision**: FIXED (Fix a) — added Phase 2 change-step #5 (one dated, no-user-facing-impact line into
  `prd.md` Implementation Decisions); updated the Critical Implementation Details note, criterion 2.4 (+ Progress
  2.4) to require both PRD mirror lines, and Migration Notes (now three doc files). Contract change-control rule
  satisfied literally.
