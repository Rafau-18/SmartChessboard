# Frame Brief: BLE Connectivity Robustness (S-10)

> Framing step before /10x-plan. This document captures what is *actually*
> at issue, separated from what was initially assumed. Consumes
> `research.md` (2026-07-01 S-10 seed) and re-verifies its load-bearing
> claims against the code.

## Reported Observation

During S-09 (`real-board-over-ble`) the BLE link between the KMP app and the
ESP32 board was unreliable, in two distinct shapes:

1. **With link encryption forced (Phase 2 `_ENC` flags):** iOS repeatedly
   tore the connection down within ~200 ms (`reason=531` = HCI 0x13, remote
   user terminated), recoverable only via a manual system-Bluetooth
   "Forget". Encryption + bonding were provisionally reverted to plaintext
   (2026-06-30, commit `f41f766`).
2. **Independently of encryption:** raw connect/reconnect was flaky on the
   Android test tablet. User's dominant remembered symptom (2026-07-02
   narrowing): **reconnecting after toggling Bluetooth off/on** (starting a
   new game after a BT restart) was unreliable; mid-game drops also
   happened "sometimes", with no noticed pattern.
3. Post-revert stability is **under-tested** — too few sessions to say
   whether plaintext alone is stable on the tablet.

## Initial Framing (preserved)

- **User's stated cause or approach**: "Przy szyfrowaniu było coś nie tak"
  — something was wrong in the encryption layer; done properly, a bonded +
  encrypted link should be stable on both Android and iOS.
- **User's proposed direction**: research the problem, then plan and
  implement a more stable version — ultimately with persistent bonding and
  encryption.
- **Pre-dispatch narrowing**: leading concern = "probably both together"
  (link stability AND pairing model — matches the roadmap S-10 outcome);
  post-revert observations insufficient ("too few tests"); whether
  encryption implementation is in S-10 scope: "not decided yet".

## Dimension Map

The observation could originate at any of these dimensions:

1. **Firmware — connection parameters** — peripheral never negotiates
   interval / latency / supervision timeout; the central dictates how long
   a stalled link survives.
2. **Bond-state lifecycle** ← closest to the initial framing, but shifted:
   state management (stale LTK across re-flash, half-finished revert), not
   cryptography being inherently unstable.
3. **RF / hardware** — breadboard harness + jumpers around the PCB antenna,
   default TX power; margin eroder, not a defect.
4. **Central (the tablet)** — weaker radio / aggressive power management;
   free rein because the firmware requests nothing.
5. **App connection flow** — Kable adapter lifecycle + connection screen:
   reconnect state management and Bluetooth-adapter-state blindness.

## Hypothesis Investigation

Three read-only sub-agents (firmware verify, app-lifecycle audit, BT-off/on
trace, 2026-07-02) re-verified `research.md` claims and filled its gaps.

| Hypothesis | Evidence | Verdict |
| --- | --- | --- |
| 1. Firmware never negotiates conn params | No `ble_gap_update_params` in tree; `gap_event()` has no `CONN_UPDATE` case (`firmware/src/ble_service.cpp:282-333`); PPCP kconfig unset; no TX-power call anywhere | **STRONG** (as a fact; its share of mid-game drops unmeasured) |
| 2. iOS desync was bond-*state* failure, not crypto | `reason=531` is a deliberate central-side teardown, not a MIC/crypto error; revert is half-done: `sm_bonding=1`, `sm_sc=1`, ENC key-dist (`ble_service.cpp:462-465`), NVS persist (`sdkconfig.defaults:23`) vs. a "no bonding dependency" comment (`:114-121`); `f41f766` stripped only the two `_ENC` flags (impl-review F1 concurs). Re-flash-while-bonded as the LTK-staleness trigger: user can't confirm (**PLAUSIBLE**, retest when bonding returns) | **STRONG** |
| 3. RF/harness margin loss | Credible (jumpers straddle the module, default TX power) but **zero RSSI measurements exist** — unverifiable without hardware diagnostics | **WEAK (unmeasured)** |
| 4. Tablet central imposes fragile profile | Circumstantial: Kable wiring is byte-for-byte identical across platforms, so the Android-vs-iOS asymmetry must originate below shared app code; no device matrix yet | **WEAK (needs device matrix)** |
| 5. App flow amplifies/creates reconnect failures | Not a cause of live-link drops, but: no observation of Bluetooth-adapter state anywhere (BT-off known only via scan-failure catch, `KableBoardAdapter.kt:84-87`); after BT restart the reconnect loop direct-connects a cached, never-rebuilt `Peripheral` (`:106`, `:173-194`) — 6 silent attempts, no per-attempt timeout (vs. `connect()`'s 30 s), then dead end; Reconnect banner blindly retries the same dead object; no cancel-scan affordance; repeated `connect()` leaks the prior `stateJob`, whose unguarded mirror can inject phantom `Disconnected` (F5+) | **STRONG for the reconnect-shaped symptom** |

## Narrowing Signals

- User: the dominant tablet symptom was **recovery after BT off/on**, not
  spontaneous drops; mid-game drops occurred but with no observed pattern.
  → rules the reconnect-shaped symptom INTO dimension 5; leaves mid-game
  drops split across 1/3/4.
- User: post-revert testing insufficient → whether plaintext-alone is
  stable on the tablet is **unknown**, not established.
- User: re-flash history at iOS desync time unknown → hypothesis 2's
  trigger detail stays PLAUSIBLE; its mechanism (531 = bond-state teardown)
  stands regardless.
- BT-off/on trace: recovery is unhandled-by-design, manual-only, and the
  manual path (Reconnect button) retries a dead Peripheral — corroborates
  the user's felt experience precisely.

## Cross-System Convention

Well-behaved BLE accessories (a) request Apple-compliant connection
parameters and log what the central grants, (b) observe adapter state and
rebuild peripherals after a BT restart, and (c) treat firmware re-flash as
a bond-reset event with an explicit forget/re-pair recovery UX. This
codebase currently does none of the three — consistent with the reframe,
and with F-03's plaintext nRF-Connect stability (a *clean* link can hold).

## Reframed Problem Statement

> **The actual problem to plan around is**: S-10 is three separable
> problems, none of which is "encryption is unstable": (1) the app's
> connection flow is blind to Bluetooth-adapter state and cannot recover
> from a BT restart without manual, often-futile action; (2) the raw link
> runs with zero negotiated parameters, unmeasured RF margin, and an
> unlocalized mid-game-drop cause that only the hardware diagnostic matrix
> can rank; (3) the pairing model sits in a worst-of-both-worlds
> half-revert (bonding machinery armed, zero security benefit), and the
> iOS failure it reacted to was bond-lifecycle state management — so
> "bonded+encrypted done right" remains a viable target *if* re-flash
> bond-reset discipline and a forget/re-pair recovery UX come with it.

The initial framing ("the encryption layer was at fault") is **reframed**:
encryption was the trigger surface, but the defect was bond-state
lifecycle management — and the user's headline instability (BT off/on
recovery) has nothing to do with encryption at all. The user's long-term
direction (bonded + encrypted) is *not* refuted by the evidence; it is
conditional on doing the state management right.

## Confidence

**MEDIUM** overall — composed of:

- HIGH for (1) app BT-state blindness and (3) half-revert/bond-state
  mechanism (verified to file:line, user-corroborated symptom).
- LOW for ranking the *mid-game drop* causes (dimensions 1/3/4): no reason
  codes, negotiated parameters, or RSSI have ever been captured. The
  `research.md` §5 diagnostic matrix (nRF-Connect idle-hold, device
  matrix, reason-code + RSSI capture) is the specific verification step
  that must precede or open the implementation plan.

## What Changes for /10x-plan

Plan S-10 as three tracks with a diagnostics-first gate, not as "fix
encryption": (a) app connection-flow robustness (BT-state observation,
peripheral rebuild, reconnect timeouts/cancel affordances, stale-job fix);
(b) link-stability work ordered by what the §5 diagnostics actually
confirm (conn-param request, TX power, RF hygiene per `research.md` §6);
(c) an explicit pairing-model decision gate — complete the plaintext
revert as the baseline, and treat bonded+encrypted-done-right (bond-reset
discipline + forget/re-pair UX + multi-device iOS validation) as the
user-preferred candidate to confirm or reject, deciding whether its
implementation lands in S-10 or a follow-up slice.

## References

- Research: `context/changes/ble-connectivity-robustness/research.md`
  (2026-07-01 seed; §3 hypothesis table, §5 diagnostics, §6 fixes)
- S-09 history: `context/changes/real-board-over-ble/change.md` (2026-06-30
  revert decision), `reviews/impl-review.md` (F1 half-revert, F5
  asymmetries), `context/foundation/lessons.md` ("Force-encryption to
  trigger iOS bonding backfired")
- Roadmap: `context/foundation/roadmap.md` §S-10 (outcome = reliability
  across devices + settled pairing-model decision)
- Key code: `firmware/src/ble_service.cpp:114-121,282-341,462-465`,
  `firmware/sdkconfig.defaults:23`,
  `SmartChessboard/shared/src/androidMain/.../ble/KableBoardAdapter.kt:84-87,106,121-194`,
  `.../commonMain/.../ble/BleBoardAdapterCore.kt:57-102`,
  `.../presentation/connection/ConnectionReducer.kt:79-95`
- Investigation: 3 sub-agent runs, 2026-07-02 (firmware-verify,
  app-lifecycle-audit, bt-off-on-trace)
