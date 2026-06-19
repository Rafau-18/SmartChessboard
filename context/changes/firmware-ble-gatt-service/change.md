---
change_id: firmware-ble-gatt-service
title: Firmware ble gatt service
status: plan_reviewed
created: 2026-06-19
updated: 2026-06-19
archived_at: null
---

## Notes

<!-- Free-form notes for this change: links, ad-hoc context, decisions that don't belong in research/frame/plan. -->

- 2026-06-19: `/10x-research` completed → `research.md`. F-03 = ESP32 BLE GATT service implementing `contract-surfaces.md` §1. Key finding: the mobile side already has a complete, unit-tested byte codec (`BoardWireCodec.kt`, commonMain) that is the byte-for-byte reference; the F-02 emulator round-trips through it. Recommended stack: NimBLE. Open decisions consolidated for `/10x-plan` (D1–D9). Next: `/10x-plan firmware-ble-gatt-service`.
- 2026-06-19: `/10x-plan-review` completed → `reviews/plan-review.md`. Verdict **REVISE** (1 critical, 0 warnings, 3 observations). Golden vectors confirmed byte-for-byte against `BoardWireCodecTest.kt`. F1 (critical/low): 25 phase-body `- [ ]` checkboxes must become plain `- ` (Progress-only syntax). F2 (warning/med): cross-task torn read of 64-bit `stable` on the snapshot path. F3/F4 (observations): prd.md change-control mirror; queue depth/backpressure. All findings `Decision: PENDING`. Resume triage: `/10x-plan-review context/changes/firmware-ble-gatt-service/reviews/plan-review.md`.
- 2026-06-19: triage complete — all 4 findings FIXED in `plan.md`. F1 → plain bullets (Progress intact, 25 items). F2 → Fix A (snapshots producer-built via snapshot-request; no cross-task `stable` read). F3 → Phase 4 mandates prd.md UUID mirror. F4 → Phase 3 §1 queue depth/full-policy. Verdict after fixes: **SOUND**. Plan is `/10x-implement`-ready.
