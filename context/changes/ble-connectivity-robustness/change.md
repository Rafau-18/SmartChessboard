---
change_id: ble-connectivity-robustness
title: BLE connectivity robustness (S-10)
status: preparing
created: 2026-07-02
updated: 2026-07-02
archived_at: null
---

## Notes

<!-- Free-form notes for this change: links, ad-hoc context, decisions that don't belong in research/frame/plan. -->

- 2026-07-01: `research.md` seeded ahead of the change folder (commit `5646f9c`) by a multi-agent research workflow — root-cause hypotheses for the S-09 BLE instability, diagnostic plan, and fix candidates. Findings were hypotheses pending verification.
- 2026-07-02: user decision after frame: **diagnostics first, then plan**. `diagnostics.md` written (in Polish, deliberately — hands-on hardware protocol for the user; placeholders **[UZUPEŁNIJ: ...]** to be filled with measurements). Session paused here; next step once measurements exist: `/10x-plan ble-connectivity-robustness` reading `frame.md` + filled `diagnostics.md`.
- 2026-07-02: `/10x-frame` run → `frame.md` written. Three read-only sub-agents re-verified the research's load-bearing claims (firmware conn-params + half-revert confirmed to file:line; app-lifecycle audit filled the research's stubbed app gap; BT-off/on recovery traced as unhandled/manual-only). Reframed: S-10 = three separable problems — (1) app connection flow blind to Bluetooth-adapter state, (2) unlocalized mid-game-drop causes needing the diagnostics-first matrix, (3) pairing model = bond-state lifecycle management (half-revert today), NOT "encryption is unstable". Confidence MEDIUM (drop-cause ranking LOW until the §5 diagnostics run). User's long-term lean: bonded+encrypted done right; whether it lands in S-10 = open decision for the plan.
