<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Real Board over BLE (S-09)

- **Plan**: `context/changes/real-board-over-ble/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-29
- **Verdict**: REVISE (light) → SOUND after triage (all 4 findings fixed)
- **Findings**: 0 critical · 2 warnings · 2 observations

## Verdicts

| Dimension | Verdict (pre-triage) | After fixes |
|-----------|----------------------|-------------|
| End-State Alignment | PASS | PASS |
| Lean Execution | PASS | PASS |
| Architectural Fitness | PASS | PASS |
| Blind Spots | WARNING (F1, F3) | PASS |
| Plan Completeness | WARNING (F2, F4) | PASS |

## Grounding

15/15 plan-claimed paths exist. Verified inline with file:line evidence:
`BoardConnection` port (`:18-33`), `BoardWireCodec` total `Decoded|Malformed` (`decodeEvent` `:126`),
Kotlin 2.4.0 (`libs.versions.toml:14`, Kable absent), reducer seam (`PhysicalPlayReducer.kt` —
`BoardConnected:103` → `Send(RequestSnapshot):112`, `SnapshotReceived:124`, `resumeVerified:137`
parallel structure Phase 6 mirrors), DI leak (`TODO(S-09)` `PlatformModule.android.kt:34-37`, emulator
auto-connect `:40`), firmware GATT (plain `NOTIFY`/`WRITE` `:119/:129`, no `_ENC`; SM fully configured
`:452-456`; subscribe burst on `BLE_GAP_EVENT_SUBSCRIBE:299-318`). Blast radius: `ChessBoardView` 6 call
sites, isolated by the defaulted `occupancyDots` param; `isOccupied` is `internal`, reusable. Progress↔Phase
consistent (8/8 phases, body Success-Criteria counts == Progress N.M counts). brief↔plan consistent.

## Summary

Exemplary, exceptionally well-grounded transport-fill plan: below a stable `BoardConnection` port, mirrors
`EmulatedBoard`'s "one object, two faces", reuses the `SnapshotReceived` reconcile seam, the `ChessBoardView`
optional-overlay precedent, and `ReedDiagnosticsGrid.isOccupied`. Every load-bearing claim verified to the
line. Two real pre-code gaps (Phase 2 NimBLE notify-CCCD mechanism; Phase 5 wasm `actual`) plus two minor
clarity nits — all four triaged and patched into `plan.md`.

## Findings

### F1 — Notify-CCCD encryption mechanism under-specified (the iOS-bond trigger)

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 2 — Firmware encryption (F-03 delta)
- **Detail**: Phase 2 exists because "iOS only bonds on first access to an encryption-required
  characteristic," but the contract said only "add the NimBLE encryption flags to `k_chrs`." That is
  under-specified for the load-bearing notify path: `board_event` is notify-only (`ble_service.cpp:119`);
  the central never reads/writes its value — it writes the NimBLE-auto-added CCCD to subscribe, and the
  burst fires on `BLE_GAP_EVENT_SUBSCRIBE` (`:299-318`). There is no `BLE_GATT_CHR_F_NOTIFY_ENC`, and
  whether a characteristic `_ENC` flag propagates to the auto-CCCD's write permission is version-dependent
  in NimBLE — so flagging the characteristic may not gate the subscribe, and iOS may never bond. SM is
  already fully configured (`NO_INPUT_OUTPUT`, `sm_bonding=1`, `sm_sc=1`, `:452-456`); the gap is purely
  "what forces the bond on subscribe," and the plan gave no fallback.
- **Fix A ⭐ Recommended**: Name the per-path flags + a write-triggered fallback
  - Strength: Stays declarative and matches the locked "encryption-required characteristic" decision; the
    app writes `REQUEST_SNAPSHOT` on every (re)connect (`BoardConnected` arm → `Send`), so gating
    `mobile_command` `WRITE_ENC` forces the bond before the burst is usable even if the CCCD subscribe
    isn't gated. Assert "unbonded WRITE refused" at the nRF gate; record whether subscribe also gates.
  - Tradeoff: A pure subscribe-only central wouldn't bond — acceptable since the real app always writes.
  - Confidence: HIGH — `WRITE_ENC` gating is well-trodden NimBLE.
  - Blind spot: Exact CCCD-flag behavior on this ESP-IDF NimBLE version still needs the nRF gate to confirm.
- **Fix B**: Peripheral-initiates security on GAP connect (`ble_gap_security_initiate`)
  - Strength: Deterministic bond on every connect regardless of which attribute is touched first.
  - Tradeoff: Peripheral-initiated security against an iOS central can be refused/awkward; deviates from
    the "central triggers on access" model.
  - Confidence: MEDIUM — behavior vs an iOS central not verified here.
  - Blind spot: Interaction with the existing `REPEAT_PAIRING` handler (`:324`).
- **Decision**: FIXED via Fix A — Phase 2 #1 Contract now names `WRITE_ENC` on `mobile_command` as the
  load-bearing trigger (REQUEST_SNAPSHOT-on-connect forces the bond), with a best-effort CCCD-subscribe
  gate verified at the nRF gate; Critical Implementation Details + manual verification 2.3 + Progress 2.3
  updated to match.

### F2 — Phase 5 expect/actual permission gate omits the mandatory wasm actual

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 5 — #3 Permissions + platform manifests
- **Detail**: Phase 5 specified "an expect/actual runtime-permission requester (androidMain/iosMain)" —
  only two actuals. This project has no intermediate mobile source set (`shared/build.gradle.kts` is plain
  common/android/ios/wasm, no `applyDefaultHierarchy`/`dependsOn`), and every `commonMain` `expect` has a
  `wasmJsMain` `actual` (verified: `supportsPhysicalBoard=false`, `platformModule`, `bindBrowserNavigation`).
  The connection VM is in `commonMain`, so its permission `expect` is in `commonMain` → `wasmJsMain` must
  provide an `actual` or `:webApp` won't compile — which is Phase 5's own criterion 5.2.
- **Fix**: Add a `wasmJsMain` no-op/`Unsupported` `actual` for the permission gate (mirror the
  `supportsPhysicalBoard = false` precedent) and list it in Phase 5 #3 alongside the android/ios actuals.
- **Decision**: FIXED (Fix in plan) — Phase 5 #3 File + Contract now declare the gate in `commonMain` with
  android/ios/**wasmJsMain** actuals and explain the no-intermediate-source-set rule.

### F3 — Phase 4 doesn't call out dropping the emulator's connect-on-bind

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 4 — DI swap + teardown
- **Detail**: `EmulatedBoard` is bound with `.also { scope.launch { board.connect() } }`
  (`PlatformModule.android.kt:40`) — it auto-connects on DI bind. The real adapter must be passive until
  the connection screen drives `connect(id)` (Phase 5); auto-connecting on bind would start scan/connect
  before the user picks a board. Phase 4 said "replace the body + add teardown" but not "drop the
  connect-on-bind."
- **Fix**: Add one line to Phase 4's contract: the adapter is constructed idle (no connect on bind);
  connection is driven only by `BoardTransport`.
- **Decision**: FIXED (Fix in plan) — Phase 4 #1 Contract now states the adapter is constructed idle,
  contrasting the emulator's `:40` auto-connect.

### F4 — Stale breadcrumb label: reducer "TODO(S-09)" is a comment, not a TODO

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Current State Analysis + References
- **Detail**: The plan said `PhysicalPlayReducer.kt` "carries the explicit `TODO(S-09)` at `:107-108`."
  Those lines are a descriptive comment ("S-08 leaves this arm unchanged; S-09/FR-012 routes BLE
  reconnect-reconcile through the shared SnapshotReceived board-match seam below"), not a literal
  `TODO(S-09)` marker. The seam and line numbers are accurate; only the "TODO" label is wrong. (The real
  `TODO(S-09)` markers live in the DI modules `:34-37`, which the plan cites correctly.)
- **Fix**: Reword to "the S-09 breadcrumb comment at :107-108."
- **Decision**: FIXED (Fix in plan) — reworded in Current State Analysis and References.
