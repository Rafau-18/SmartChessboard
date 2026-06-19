<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Firmware BLE GATT Service (F-03)

- **Plan**: `context/changes/firmware-ble-gatt-service/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-19
- **Verdict**: REVISE → **SOUND** after triage (all 4 findings fixed in `plan.md`, 2026-06-19)
- **Findings**: 1 critical · 0 warnings · 3 observations — all FIXED

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | WARNING |
| Plan Completeness | WARNING |

## Grounding

5/5 paths ✓ (`firmware/src/main.cpp`, `firmware/src/pins.h`, `firmware/platformio.ini`, `firmware/sdkconfig.defaults`, `firmware/src/CMakeLists.txt`; `firmware/lib/` + `firmware/test/` correctly absent) · symbols ✓ (`square_index = file + 8*rank`; debounce `agree[64]` / `kStableScans = 4`; GPIO22/23 are clear of the matrix pin map; the Phase-1 golden vectors match `BoardWireCodecTest.kt` byte-for-byte, valid frames and malformed cases alike) · brief↔plan ✓ (phases, decisions, scope consistent).

## Strengths (validated, not just skimmed)

- **Phase-1 golden vectors match `BoardWireCodecTest.kt` byte-for-byte** — every `SQUARE_EVENT` / `BUTTON_EVENT` / `BOARD_SNAPSHOT` / `DEVICE_STATUS` frame and every malformed/reserved rejection (`empty`, `05 00`, `01 FF`, 10-byte snapshot, `02 85`, `02 C0`, `03 02`, `04 64`, `84`, `90`, `81 02`, `82 00`, `83 00`). The cross-language oracle that de-risks contract drift is real, not aspirational.
- The **CCCD-subscribe vs raw-CONNECT** distinction (a notify with no subscriber is dropped, so the connect burst must fire on the `board_event` CCCD write) is correctly caught — a classic NimBLE trap.
- The **31-byte advertisement budget** (service UUID in the advert, name in the scan response) and the **`ble_gatts_notify_custom` symbol caveat** are both pre-empted.
- Reuse-over-rewrite, the dumb-board / no-chess-logic mandate, and the `lessons.md` SYNC-comment rule are all honored. `firmware_version = 1.0.0` genuinely matches `EmulatedBoard.kt:43` (`FirmwareVersion(1, 0, 0)`).

## Findings

### F1 — Phase-body Success Criteria use `- [ ]` checkboxes (Progress-only syntax)

- **Severity**: ❌ CRITICAL — violates the `## Progress` mechanical contract
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1–4 "Success Criteria" blocks — 25 lines (123–129, 179–188, 238–250, 292–298)
- **Detail**: The four phase-body `#### Automated / Manual Verification` blocks use `- [ ]` task-list checkboxes. The progress-format contract (`references/progress-format.md`) reserves checkboxes for the `## Progress` section only — phase blocks must use plain `- ` bullets. The canonical sibling plan `physical-capture-emulated` (driven cleanly through `/10x-implement`, 5 phase commits) follows this: plain `- ` bullets in phase bodies, `- [ ]` only under `## Progress`. Here there are 25 stray phase-body checkboxes before line 350. The `## Progress` section itself (358–409) is well-formed and maps 1:1 to the phases — the defect is purely the duplicated checkboxes in the bodies, which hand a parser two sources of `[ ]` truth ("next pending = first `- [ ]` in document order" would match line 123, not Progress item 1.1).
- **Fix**: In the four phase-body Success-Criteria blocks, convert each `- [ ]` to a plain `- ` bullet. Leave the `## Progress` checkboxes (358–409) untouched — they are the canonical tracker.
- **Decision**: FIXED (Fix in plan, 2026-06-19) — 25 phase-body checkboxes converted to plain `- ` bullets; `## Progress` (25 items) left intact.

### F2 — Shared `stable` bitmap is read cross-task for snapshots (torn-read race)

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 2 §4 (status/snapshot source helpers) + Phase 3 §1 (producer/consumer pipeline)
- **Detail**: The producer/consumer design carefully decouples notify *delivery* (the scan task posts to a FreeRTOS queue; only the BLE host context calls notify). But the snapshot path bypasses the queue: Phase 2 §4 says the snapshot helper "reads the live debounced `stable` bitmap", and that read happens in the BLE host context (on-subscribe burst, `REQUEST_SNAPSHOT`) and in the diagnostic timer — while the scan task writes `stable`. `stable` is a `uint64_t`; on the 32-bit dual-core ESP32 a 64-bit read is two non-atomic word loads, so a reader on another task/core can observe a half-updated value → an occasional corrupt `BOARD_SNAPSHOT`. That is exactly the connect/reconnect frame S-08 resume relies on, and a torn snapshot is painful to diagnose later. The plan guards the write side ("never call BLE from scan context") but not the read side.
- **Fix A ⭐ Recommended**: Source snapshots from the producer
  - Approach: On-subscribe / `REQUEST_SNAPSHOT` / diagnostic-tick set a request the scan task services; the scan task reads its own `stable`, encodes `BOARD_SNAPSHOT`, and posts it on the same event queue.
  - Strength: Scan task stays the sole owner of `stable`; no lock on the hot scan path; preserves the "every frame flows through one queue" invariant the design already has.
  - Tradeoff: Adds a small request back-channel (flag / second queue) from BLE + timer context to the scan task.
  - Confidence: HIGH — the producer/consumer skeleton already exists; this routes one more frame type through it.
  - Blind spot: On-subscribe snapshot gains ~one scan cycle (~20 ms) of latency — comfortable under the ≤100 ms NFR.
- **Fix B**: Guard `stable` with a critical section / atomic mirror
  - Approach: Scan task publishes `stable` under `portMUX` (`taskENTER_CRITICAL`) or to an atomically-updated mirror; every cross-task reader takes the same guard.
  - Strength: Minimal, localized; readers stay where they are.
  - Tradeoff: Lock on the scan commit; correctness depends on guarding EVERY read site (incl. the diagnostic timer) — the exact discipline that's easy to drop later.
  - Confidence: MED — a bare `volatile uint64_t` is NOT torn-read-safe; needs real `portMUX`, verified across cores.
- **Decision**: FIXED (Fix A, 2026-06-19) — added a Critical Implementation Detail making `BOARD_SNAPSHOT` a producer-built frame sourced via a snapshot-request on the event queue; aligned Phase 2 §3 (connect burst), Phase 2 §4 (snapshot helper), Phase 3 §3 (REQUEST_SNAPSHOT), and Phase 3 §4 (diagnostic timer) to never read `stable` cross-task.

### F3 — §1.2 UUID write-back invokes change-control but skips the prd.md mirror

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 2 §2 (UUID write-back) + Phase 4 §2 (PRD resolutions)
- **Detail**: `contract-surfaces.md` change-control (lines 44–54) says any Section 1 (BLE) change must mirror into BOTH `prd-firmware.md` AND `prd.md`, with a dated rationale. Phase 2 §2 fills §1.2 and explicitly cites "the doc's own change-control rule" for the `updated:` bump; Phase 4 §2 updates `prd-firmware.md` (OQ-5) — but nothing touches `prd.md`. Either the UUID fill is a real §1 change (then `prd.md` needs a one-line note too) or filling a reserved placeholder is exempt (then say so). As written the plan applies the rule half-way.
- **Fix**: Add a one-line dated note to `prd.md`'s Implementation Decisions for the §1.2 UUID assignment, OR add a sentence to Phase 4 stating why the `prd.md` mirror is N/A (UUIDs are an internal, non-product-visible detail). Decide consciously.
- **Decision**: FIXED (Fix in plan, 2026-06-19) — Phase 4 §2 now mandates mirroring the §1.2 UUID assignment into `prd.md` per the contract change-control rule; criterion 4.2 (body + Progress) extended to verify the `prd.md` note.

### F4 — Queue depth / backpressure policy unspecified vs the "never coalesce" invariant

- **Severity**: 📝 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3 §1 (producer/consumer event pipeline)
- **Detail**: The plan defines the queue and says unsubscribed game-mode events are dropped (correct, per §1.7), but never states the queue depth or what happens when a *subscribed* queue fills. The contract mandates per-transition `SQUARE_EVENT`s "never coalesced" — a silently dropped `SQUARE_EVENT` under backpressure corrupts `SequenceInterpreter` resolution. At human chess speed overflow is unlikely, but diagnostic 10 Hz snapshots + rapid lifts is the stress case.
- **Fix**: State a queue depth and a full-queue policy in Phase 3 §1: `SQUARE_EVENT` / `BUTTON_EVENT` must never be silently dropped on a live link; diagnostic `BOARD_SNAPSHOT`s may be coalesced/dropped (idempotent — latest wins). One sentence settles it.
- **Decision**: FIXED (Fix in plan, 2026-06-19) — Phase 3 §1 Contract now states the queue depth + full-policy: SQUARE/BUTTON events never silently dropped on a live link; diagnostic snapshots may coalesce.
