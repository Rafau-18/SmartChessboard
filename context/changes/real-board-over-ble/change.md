---
change_id: real-board-over-ble
title: Real board over BLE
status: implementing
created: 2026-06-29
updated: 2026-06-30
archived_at: null
---

## Notes

<!-- Free-form notes for this change: links, ad-hoc context, decisions that don't belong in research/frame/plan. -->

- 2026-06-29: `research.md` written (5 parallel agents). S-09 is a transport-fill — the `BoardConnection` port, `BoardWireCodec`, and the `SnapshotReceived` reconnect seam already exist; firmware F-03 is on-hardware-verified. Library recommendation: **Kable-first**. FR-012 reconnect/reconcile is **in scope**.
- 2026-06-29: decisions locked — iOS bonding **B (force encryption → firmware+contract+re-verify delta; S-09 not mobile-only)**; connection UX **full MVI screen**; iOS background **foreground-first**; hardware **ready, ADC buttons = MVP target**; emulator **test-only/no UI, device uses the real board**. Kotlin 2.4.0 confirmed; Blue-Falcon evaluated & rejected (no iOS state-restoration). Next: `/10x-plan real-board-over-ble`.
- 2026-06-29: `/10x-plan-review` (deep) → verdict **REVISE (light) → SOUND after triage**. Grounding 15/15 paths, every load-bearing claim verified to the line. 4 findings, all fixed in `plan.md`: **F1** (Phase 2) NimBLE notify-CCCD encryption under-specified → gate the bond via `mobile_command` `WRITE_ENC` (REQUEST_SNAPSHOT-on-connect forces it), CCCD subscribe best-effort + nRF-verified; **F2** (Phase 5) add the mandatory `wasmJsMain` `actual` for the permission `expect`; **F3** (Phase 4) adapter constructed idle (no emulator-style connect-on-bind); **F4** reworded the reducer "TODO(S-09)" breadcrumb (it's a comment, not a marker). Report: `reviews/plan-review.md`. Next: `/10x-implement real-board-over-ble phase 1`.
- 2026-06-29: `plan.md` + `plan-brief.md` written (8 phases). Planning decisions on top of research: firmware encryption delta rides **inside S-09**; adapter is **one object** implementing the `BoardConnection` port + a new `BoardTransport` driver surface (mirrors `EmulatedBoard`); connection screen scope **richer** (RSSI/forget-repair/manual-retry/auto-connect/full error taxonomy); on-hardware acceptance is a **hard blocking phase** on Android **and** iOS. Added a feature beyond research at user request: a **live reed-matrix overlay** on the play board — a new display-only `sensedOccupancy` field (snapshot-reset, event-folded), plain always-on corner dots (toggle), zero extra BLE. Next: `/10x-plan-review real-board-over-ble` then `/10x-implement real-board-over-ble phase 1`.
