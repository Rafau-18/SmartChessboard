<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Real Board over BLE (S-09)

- **Plan**: context/changes/real-board-over-ble/plan.md
- **Scope**: All 8 phases (full slice)
- **Date**: 2026-07-01
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 4 warnings, 2 observations

## Automated success criteria (re-run live this review)

| Criterion | Result |
|-----------|--------|
| `:shared:testAndroidHostTest` | PASS ✅ |
| `:shared:iosSimulatorArm64Test` | PASS ✅ |
| `:shared:wasmJsTest` | PASS ✅ |
| ktlint (source, excluding `build/`) | clean ✅ |
| firmware `pio test -e native` | 15/15 ✅ |
| firmware `pio run` | SUCCESS ✅ |

`:shared:assemble` not run standalone; compilation of all three test targets + wasm covers it.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | WARNING |

**Overall: NEEDS ATTENTION** — no critical FAIL; a cluster of documentation/traceability drift from the
Phase 8 encryption→plaintext revert, one substantive firmware half-revert (mostly owned by roadmap S-10),
and the hard manual gate's evidence trail to close before archive. The slice itself is faithful to plan
intent across all 8 phases, all guardrails held, and all core invariants verified.

## Findings

### F1 — Firmware still configured for bonded Just-Works pairing after the plaintext revert

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality (Reliability)
- **Location**: firmware/src/ble_service.cpp:462-465 (contradictory comment :459-460 vs :114-121)
- **Detail**: Characteristics correctly reverted to plaintext (`NOTIFY`/`WRITE`, no `_ENC`, `min_key_size=0`),
  but `sm_bonding=1` / `sm_sc=1` / `sm_*_key_dist |= BLE_SM_PAIR_KEY_DIST_ENC` remain, and the :459-460
  comment still says "bonded, secure-connections, persisted to NVS" — contradicting the plaintext posture at
  :114-121 ("no bonding dependency"). Nothing FORCES a bond now (plaintext), so practical risk is low, but a
  central that Just-Works-bonds anyway persists an LTK that can desync — the exact class S-09 reverted to
  avoid. NOTE: the `BLE_GAP_EVENT_REPEAT_PAIRING` handler (:333-341) is the recovery net (deletes the stale
  bond + re-pairs), NOT part of the problem — do not remove it blindly. The substantive "should we set
  `sm_bonding=0`" decision is already owned by roadmap S-10 (ble-connectivity-robustness, multi-device
  re-test); lessons.md marks the plaintext posture "provisional".
- **Fix A ⭐ Recommended**: Fix the :459-460 comment to match the plaintext posture now; fold the
  `sm_bonding=0` design decision into S-10.
  - Strength: Keeps the F-03-proven trust-on-first-pair net + its stale-LTK recovery handler; resolves the
    doc contradiction immediately; the risky config change is re-tested across devices where it belongs (S-10),
    matching the "provisional" caveat.
  - Tradeoff: Vestigial bonding capability stays advertised until S-10.
  - Confidence: HIGH — lessons.md already scopes the pairing model to S-10.
  - Blind spot: None significant.
- **Fix B**: Set `sm_bonding=0` (+ drop the key-distribution lines) for a true no-bond plaintext MVP now.
  - Strength: Removes the residual stale-LTK surface entirely today.
  - Tradeoff: Untested firmware change on flashed hardware; pre-empts the S-10 re-test; may interact with the
    REPEAT_PAIRING net.
  - Confidence: MED — plaintext already dormant-izes bonding; unverified on iOS this session.
  - Blind spot: Not re-run on real hardware in this session.
- **Decision**: PENDING

### F2 — plan.md / plan-brief.md still describe forcing encryption (never annotated post-revert)

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/changes/real-board-over-ble/plan.md:9,48,172-223 (+ plan-brief.md)
- **Detail**: Code + firmware + primary docs (contract §1.1/§1.2/§1.8, prd-firmware.md, manual-verification.md,
  lessons.md) all correctly reflect the plaintext revert. But plan.md itself (Overview :9, Desired End State
  :48 "bonds **encrypted**", all of Phase 2 :172-223) and its plan-brief.md handoff still describe forcing
  encryption. A future reviewer using plan.md as ground truth would believe encryption shipped. The CODE
  adheres to intent; the plan text is stale.
- **Fix**: Add a dated addendum note at Phase 2 / Desired End State in plan.md (+ plan-brief.md) pointing to
  the 2026-06-30 revert (change.md + lessons.md). Annotate, don't rewrite history.
- **Decision**: PENDING

### F3 — firmware/AGENTS.md asserts the encrypted-bond delta was verified, omits the revert

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency (module docs a future agent reads first)
- **Location**: firmware/AGENTS.md:11
- **Detail**: "S-09 Phase 2 additionally verified the encrypted-bond delta on the same board (2026-06-30)." —
  reads as if the shipped firmware is encrypted; the Phase 8 plaintext revert is not mentioned. This module
  doc is exactly what a future agent reads to understand firmware state, so the stale claim is load-bearing.
- **Fix**: Amend :11 to note Phase 2's encryption was reverted to plaintext in Phase 8 (link change.md /
  lessons.md).
- **Decision**: PENDING

### F4 — Phase 8 (HARD BLOCKING GATE) rows ticked without SHA write-back or a filled evidence log

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — this is the slice's one irreplaceable manual proof
- **Dimension**: Success Criteria
- **Location**: context/changes/real-board-over-ble/plan.md:660-668 + manual-verification.md §4/§5/§Status
- **Detail**: plan.md rows 8.1–8.6 are `[x]`, but (a) unlike every other phase they carry no ` — <sha>`
  (p8 did land as `f41f766` — the SHA exists, just not written back), and (b) the manual-verification.md
  result logs (§4 Android, §5 iOS) are entirely empty (all ☐, blank device/OS/date) with §Status unchecked.
  change.md records the user ran + accepted the on-hardware gate, so this is a traceability / write-back gap,
  NOT a fabricated pass — but the designated evidence artifact for the slice's hard blocking gate is blank.
- **Fix**: Write `f41f766` onto rows 8.1–8.6 and fill manual-verification.md's result log (device/OS/date +
  the accepted flaky-BLE caveat from change.md) / tick §Status, so the gate has a real evidence trail before
  `/10x-archive`.
- **Decision**: PENDING

### F5 — connect()/reconnect() state-management asymmetries

- **Severity**: 🔎 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Reliability)
- **Location**: KableBoardAdapter.kt (Android:123-127, reconnect :161-168; iOS parallel)
- **Detail**: (a) The connect() failure path guards with `if (peripheral === target)`, but the success path
  sets `Connected` UNguarded (:126-127) — a superseded connect could set Connected for a stale target.
  (b) reconnect() doesn't coordinate with an initial connect() still inside `attemptConnect`, so a manual
  "Reconnect" tap mid-initial-connect can overlap two connect attempts (the doc's idempotency claim is
  slightly overstated). Both are hard to hit (the reducer drives connect serially; Kable tolerates a redundant
  connect()).
- **Fix**: Guard the success branch with `if (peripheral === target)` for parity; optionally no-op reconnect()
  while an initial connect is in flight.
- **Decision**: PENDING

### F6 — Stale "encryption-gated/required" code comments contradict the plaintext link

- **Severity**: 🔎 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: BoardTransport.kt:32, BleBoardAdapterCore.kt:81, KableBoardAdapter.kt:109 & :212 (iOS parallel)
- **Detail**: These comments still call the write path "encryption-gated / the load-bearing bond trigger
  (Phase 2)". Cosmetic, no runtime effect, but contradicts the shipped plaintext reality.
- **Fix**: Reword to plaintext framing; drop the Phase-2 encryption references.
- **Decision**: PENDING

## Notes (not findings)

- 3 untracked working files at repo root (`hardware-bringup-and-status-plan.md`, `manual-verification-report.md`,
  `manual-verification-tasks.md`) — self-marked "temporary / delete after done", cross-change, not part of the
  S-09 diff. Housekeeping cleanup candidate.
- Verified-clean invariants (no findings): `send()` throws `IllegalStateException` on a dead link via the direct
  transport state; malformed decodes dropped (never emitted); `events` `SharedFlow(replay=0)` subscribed before
  connect so the on-connect burst is never missed; `sensedOccupancy` is display-only and absent from every
  acceptance gate; `occupancyDots` defaults null (Replay/Play/web unchanged); h8-safe `1L shl` bit ops; MVI
  justified in a doc comment; Koin single-instance two-port binding with `onClose` teardown; Nav3
  `@Serializable NavKey` + explicit polymorphic registration; permissions correct (Android neverForLocation +
  legacy maxSdk 30; iOS NSBluetooth string, no UIBackgroundModes).
