<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Reed-Switch Board Emulator (F-02)

- **Plan**: context/changes/reed-board-emulator/plan.md
- **Scope**: Full plan (Phases 1–4)
- **Date**: 2026-06-16
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

Automated success criteria re-run at review time (all green):
`:shared:testAndroidHostTest` + `:shared:iosSimulatorArm64Test` + `:shared:wasmJsTest`
→ BUILD SUCCESSFUL; `ktlint` on all 9 `board/*.kt` files → 0 violations.

The reviewer **independently recomputed every golden-frame vector from
`contract-surfaces.md` §1.3** (manual gate 2.3 — the self-consistency check a passing
suite cannot perform) and all match the literal vectors in `BoardWireCodecTest.kt`:
SQUARE_EVENT corners/e2/e4, BOARD_SNAPSHOT start-position + byte-index pin (a2) + LSB-first
pin (h1), DEVICE_STATUS little-endian uptime + uint32-max (4_294_967_295, not −1), all §1.4
commands, and the malformed set (reserved bits, truncation, oversized snapshot, reserved
command tags). Sub-agent safety pass returned a clean bill on bit-math (every `Byte`→wider
widening masked, `ushr` on unsigned fields, `1L shl N` everywhere — no Kotlin/Native sign
trap), decoder totality (every malformed frame → `Malformed`, no throw / no OOB), coroutine
lifecycle (jobs stored + cancelled on disconnect, launched on injected scope), and
`SharedFlow` config (`replay = 0`, suspending `emit` — no silent drop). Plan-drift pass:
all planned files MATCH, no MISSING symbol, no behavioral drift.

## Findings

### F1 — SquareEvent lacks the validity guard its sibling isOccupied has

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/BoardEvents.kt:16
- **Detail**: `SquareEvent(val square: Int, …)` has no construction guard. Its sibling `BoardSnapshot.isOccupied` (same file, line 39) guards with `require(isValidSquare(square))` — a guard this change itself added in Phase 1 (review F1, commit 8c16c1a). A `SquareEvent(square = 99, …)` hand-constructed by a future S-06/S-09 consumer reaches the encoder at `BoardWireCodec.kt:99` where `(eventBits shl 6) or event.square` silently corrupts the high event bits — wrong wire byte, no error. Not reachable today (emulator validates via `lift`/`place`; decoder masks to `0x3F`), so this is defense-in-depth on a public domain type, not a live bug. The import `isValidSquare` is already present (line 3).
- **Fix**: Add an `init` block to `SquareEvent` matching the `isOccupied` precedent: `init { require(isValidSquare(square)) { "square must be in 0..63, was $square" } }`.
  - Strength: Matches the guard `isOccupied` uses (and the `Square.kt` index-helper convention); closes the silent-corruption path at the domain boundary; the import already exists.
  - Tradeoff: Minor tension with root CLAUDE.md's "don't validate trusted-internal-caller scenarios" — but the local precedent within this very file/change favors the guard.
  - Confidence: HIGH — identical fix already applied to the sibling type in Phase 1.
  - Blind spot: None significant.
- **Decision**: RESOLVED in 8fcb360 — `init { require(isValidSquare(square)) }` added to `SquareEvent`; `BoardEventsTest` covers the guard and the sibling `isOccupied` range check.

### F2 — Contract / firmware-PRD edits exceed the "one sentence / one line" cap

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: docs/reference/contract-surfaces.md §1.3 ; context/foundation/prd-firmware.md
- **Detail**: Plan said "ONE added sentence" (contract) and "ONE dated line" (firmware PRD). Actual edits are a ~5-line/3-sentence paragraph and a 4-line dated sub-bullet. Content is exactly the planned bit-packing clarification, dated and frontmatter-bumped (`updated: 2026-06-13 → 2026-06-16`) — no scope creep, just more verbose. Flagged because the plan's manual gate 2.4 asks to confirm the edit is "minimal one-sentence"; a 2.4 reviewer should know the content is correct but longer than specified. `prd.md`, by contrast, is exactly one dated line — MATCH.
- **Fix**: Accept as-is (content is correct) — or trim §1.3 to one sentence if the "minimal" wording in gate 2.4 is meant strictly.
- **Decision**: ACCEPTED as-is — content is correct and dated; the extra verbosity aids the firmware reader. Manual gate 2.4 reviewer is hereby informed the edits are longer than "one sentence" but substantively correct.

### F3 — EmulatedDeviceStatus is a public type the plan didn't name

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoard.kt:40
- **Detail**: The plan's constructor read `status = default` without naming a type. The impl introduces a public `data class EmulatedDeviceStatus(batteryPct, firmwareVersion, uptimeSeconds)` as that param's type. Reasonable realization; `commonTest`-only (ships in no release binary). EXTRA but benign.
- **Fix**: Accept — sensible fixture type; revisit naming when the emulator promotes to `commonMain` with S-06.
- **Decision**: ACCEPTED — sensible `commonTest`-only fixture type; revisit naming at the S-06 promotion to `commonMain`.

### F4 — Test helpers re-derive the square convention locally

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoardEndToEndTest.kt:245 ; EmulatedBoardTest.kt:245
- **Detail**: Production code correctly reuses `isValidSquare`/`squareOf` from `domain/chess/Square.kt` (the plan's reuse mandate is met). But the two test fixtures compute `file + 8*rank` by hand instead of calling `squareOf` — a second, tiny copy of a load-bearing convention, in test code only.
- **Fix**: Have the `sq()` helper delegate to `squareOf(file, rank)` so the convention lives in one place even in tests.
- **Decision**: RESOLVED in 8fcb360 — `sq()` (E2E test) and the `E4`/`A2` constants (`EmulatedBoardTest`) now delegate to `squareOf`; no test re-derives `file + 8*rank`.

### F5 — Manual-gate checkboxes still unticked though the substance is now verified

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: context/changes/reed-board-emulator/plan.md §Progress (2.3/2.4/3.3/3.4/4.5/4.6)
- **Detail**: `plan.md` Progress still shows 2.3/2.4/3.3/3.4/4.5/4.6 as `[ ]`, and `BoardWireCodecTest.kt:22-24` carries a TODO for the manual gate. This review discharged their substance: 2.3 (golden vectors) independently recomputed → match; 3.3 (single encode→decode emission path, `Malformed` throws) and 3.4 (offline-silent, reveal-on-reconnect, send-offline throws) confirmed by the pipeline read; 4.6 (both capture orders + interleaved castle in asserted streams) confirmed in the E2E test. The canonical Progress understates completion until ticked — future reviews/archive read it as ground truth.
- **Fix**: Tick 2.3/2.4/3.3/3.4/4.5/4.6 in `plan.md` and delete the TODO at `BoardWireCodecTest.kt:22-24` (the independent cross-check is done).
- **Decision**: DEFERRED — the manual gate stays with the user this session ("manualne zostaw"); boxes remain `[ ]` and the `BoardWireCodecTest.kt` TODO stays until the user ticks them. This review's independent recompute is on record as supporting evidence, not a substitute for the human sign-off.
