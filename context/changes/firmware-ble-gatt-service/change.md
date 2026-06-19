---
change_id: firmware-ble-gatt-service
title: Firmware ble gatt service
status: implementing
created: 2026-06-19
updated: 2026-06-19
archived_at: null
---

## Notes

<!-- Free-form notes for this change: links, ad-hoc context, decisions that don't belong in research/frame/plan. -->

- 2026-06-19: `/10x-research` completed → `research.md`. F-03 = ESP32 BLE GATT service implementing `contract-surfaces.md` §1. Key finding: the mobile side already has a complete, unit-tested byte codec (`BoardWireCodec.kt`, commonMain) that is the byte-for-byte reference; the F-02 emulator round-trips through it. Recommended stack: NimBLE. Open decisions consolidated for `/10x-plan` (D1–D9). Next: `/10x-plan firmware-ble-gatt-service`.
- 2026-06-19: `/10x-plan-review` completed → `reviews/plan-review.md`. Verdict **REVISE** (1 critical, 0 warnings, 3 observations). Golden vectors confirmed byte-for-byte against `BoardWireCodecTest.kt`. F1 (critical/low): 25 phase-body `- [ ]` checkboxes must become plain `- ` (Progress-only syntax). F2 (warning/med): cross-task torn read of 64-bit `stable` on the snapshot path. F3/F4 (observations): prd.md change-control mirror; queue depth/backpressure. All findings `Decision: PENDING`. Resume triage: `/10x-plan-review context/changes/firmware-ble-gatt-service/reviews/plan-review.md`.
- 2026-06-19: triage complete — all 4 findings FIXED in `plan.md`. F1 → plain bullets (Progress intact, 25 items). F2 → Fix A (snapshots producer-built via snapshot-request; no cross-task `stable` read). F3 → Phase 4 mandates prd.md UUID mirror. F4 → Phase 3 §1 queue depth/full-policy. Verdict after fixes: **SOUND**. Plan is `/10x-implement`-ready.
- 2026-06-19: Phase 1 implemented (c3d3665) — `firmware/lib/` board_protocol + debounce, `[env:native]` Unity tests green vs Kotlin golden vectors. Manual 1.4 (golden-frame spot-check) deferred to `manual-verification.md`.
- 2026-06-19: Phase 2 implemented — NimBLE peripheral (ESP-IDF 6.0). `sdkconfig.defaults` enables BT/NimBLE/NVS-persist, single connection, peripheral-only. New `src/ble_service.{h,cpp}`: one GATT service (`board_event` notify + `mobile_command` write), Just-Works bonding, advertising (service UUID in adv, name in scan-rsp), GAP lifecycle, on-subscribe `BOARD_SNAPSHOT`→`DEVICE_STATUS` burst via a producer/consumer queue spine (scan task builds `stable`-derived frames — no cross-task read). UUIDs `787e000{1,2,3}-…` recorded in contract §1.2. Device build links with NimBLE; host tests still green. On-hardware checks 2.4–2.7 deferred to `manual-verification.md`.
- 2026-06-19: Phase 3 implemented — game behavior on the producer/consumer spine. `main.cpp` switched to `lib/debounce` (matrix debounce now the host-tested unit), streams one `SQUARE_EVENT` per debounced transition via `deriveSquareEvents` (never coalesced, gated on `is_subscribed()`), and reads two confirmation buttons (GPIO22/23, active-LOW, edge-debounced) into `BUTTON_EVENT`s. `ble_service.cpp` gained the full `mobile_command` write handler (`SET_MODE`/`REQUEST_SNAPSHOT`/`REQUEST_STATUS`; malformed = no-op) and two FreeRTOS timers (~30 s `DEVICE_STATUS` on subscribe, ~100 ms diagnostic `BOARD_SNAPSHOT` on `SET_MODE→diagnostic`) — timers/handler only post Requests the producer turns into frames (no cross-task `stable` read). Backpressure keyed on §1.3 tag: SQUARE/BUTTON block-then-log (never silent drop), snapshots/status droppable. `pins.h` + `src/CMakeLists.txt` extended additively. Device build links; host tests 15/15 (incl. `test_derive_square_events`). On-hardware checks 3.4–3.10 deferred to `manual-verification.md`.
