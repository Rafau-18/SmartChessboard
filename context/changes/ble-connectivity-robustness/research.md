<!--
  RESEARCH ARTIFACT — not an implementation. Pre-seeds roadmap slice S-10
  (ble-connectivity-robustness). Generated 2026-07-01 by a multi-agent research
  workflow (firmware / hardware-RF / app-flow / BLE-fundamentals -> adversarial
  critique -> synthesis) and lightly reviewed. Findings are hypotheses to be
  confirmed on hardware via the §5 diagnostic plan before any code changes.
  Nothing here is committed to; it is input for a future /10x-new + /10x-plan.
-->

# BLE Connectivity Stability — Root-Cause Research for S-10

## 1. TL;DR

The "it connects but keeps dropping / reconnects slowly" instability on the smart chessboard is **not one bug** — it is a marginal BLE link sitting at the intersection of three compounding weaknesses, none of which is the MCU itself. Ranked by likelihood of being the dominant, fixable root cause:

1. **FIRMWARE — no connection-parameter negotiation (highest confidence, code-confirmed).** The ESP32 NimBLE peripheral never sets a preferred PPCP and never calls `ble_gap_update_params()`, so it passively inherits whatever interval / peripheral-latency / **supervision timeout** the central chooses. It has zero *proactive* influence over the single number that decides how long a stalled link survives before teardown. (Caveat: a peripheral update is only a *request* — see §6 QW-1 — but requesting nothing guarantees the central's defaults win.) This is the most plausible explanation for the iOS-vs-tablet asymmetry and for why a marginal link tips into supervision-timeout drops.
2. **RF / HARDWARE — the breadboard/jumper DevKit-V1 harness as a margin eroder.** A reused prototype harness with jumper wiring around the WROOM PCB antenna and no ground plane / keep-out / decoupling can cost meaningful link margin (magnitude unmeasured — see open question #4). This does not *cause* a defect; it *amplifies* #1 so the tablet (weaker radio) drops where iOS holds.
3. **FIRMWARE — the "plaintext revert" is only half done (a latent bonding hazard).** The team thinks bonding was removed; it was not. Only the characteristic-level `_ENC` flags were stripped. `sm_bonding=1`, `sm_sc=1`, both ENC key-distribution flags, and NVS bond persistence are still live — so the bonding machinery remains **armed**. It does not fire on its own (the stripped `_ENC` flags mean the peripheral never *demands* encryption), but if any central — or an nRF Connect "Bond" action — initiates pairing, an LTK is created and persisted and the stale-LTK / `reason=531` iOS desync path re-opens.
4. **CENTRAL (the Android tablet) — a weaker radio + more variable BLE stack.** Cheap tablets pick their own (often short) supervision timeouts and apply aggressive power management. Because the firmware requests nothing (#1), the tablet is free to impose a fragile profile.
5. **APP FLOW — reasonable, not the cause.** The Kable reconnect layer reacts to drops correctly (bounded backoff, timeouts); it masks root causes rather than creating them. One minor amplifier: `WriteType.WithResponse` on a marginal link surfaces timeouts as errors.

**The single highest-leverage fix** is to make the firmware proactively request Apple-compliant connection parameters on connect (and log what the central actually grants). The single cleanest **diagnostic** is an nRF Connect long-idle-hold test with no app involved, which splits app/central faults from firmware/RF faults in one experiment.

> Note: the app-side investigation agent returned only a stub. App-flow findings below are drawn from the fundamentals agent, which audited `KableBoardAdapter` / `BleBoardAdapterCore` directly. A dedicated app-lifecycle audit remains an open item for S-10.

---

## 2. Why the BT-audio intuition misleads

The intuition — *"a Bluetooth speaker never cuts out, so a once-connected board should hold"* — compares two different radio technologies with different link-maintenance regimes:

| | **BT speaker (A2DP)** | **Chessboard (BLE / GATT)** |
|---|---|---|
| Radio | Bluetooth **Classic** (BR-EDR) | Bluetooth **Low Energy** |
| Link type | Dedicated synchronous audio stream | Connection-interval polling of a background accessory |
| Phone treatment | First-class foreground **media** device, prioritized | Background low-power accessory |
| What keeps it alive | Audio packets flow **every few ms, continuously** — the link is exercised constantly and re-buffers rather than drops | Survival depends on **three negotiated numbers** + RF + stack conformance |
| Device profile | Real PCB, antenna keep-out, decoupling, mains/large battery | Breadboard prototype, detuned antenna, USB-only, no decoupling |

A BLE link's liveness is governed by the **supervision timeout**: the connection is declared lost only after that window elapses with no packet received from either side (configurable 100 ms – 32 s; a miss yields disconnect reason **0x08**, `BLE_ERR_CONN_SPVN_TMO`). **Peripheral latency** is a separate knob: it lets the peripheral skip up to *N* connection events, sleeping for `interval × (latency + 1)` — but that governs how long the peripheral may *stay silent*, not how long the link tolerates silence before teardown. The two must not be conflated: even with latency 0, the link survives RF gaps right up to the supervision timeout. An **idle GATT sensor** that sends nothing for seconds — exactly this board between moves — is the textbook case a too-short supervision timeout kills.

The intuition is *directionally* right about one thing: a *clean* build with negotiated parameters **can** be rock-solid (a well-behaved BLE accessory holds for hours). It just does not follow automatically for *this* build, where the peripheral negotiates nothing and the antenna/power are compromised.

---

## 3. Layered root-cause hypotheses (ranked)

| # | Layer | Hypothesis | Evidence found | Likelihood | Confirm / refute |
|---|-------|-----------|----------------|:----------:|------------------|
| 1 | **FIRMWARE (conn params)** | Peripheral never *requests* params; supervision timeout is 100% central-chosen and never floored | `gap_event()` handles only CONNECT/DISCONNECT/SUBSCRIBE/ADV_COMPLETE/REPEAT_PAIRING — no `BLE_GAP_EVENT_CONN_UPDATE` case, no `ble_gap_update_params` call anywhere (`firmware/src/ble_service.cpp:280-346`). PPCP kconfig all zero (`PPCP_MIN/MAX_CONN_INTERVAL=0`, `PPCP_SLAVE_LATENCY=0`); whole-tree grep for conn-param symbols is empty | **Likely cause** | Add a `CONN_UPDATE` handler that logs the negotiated interval/latency/timeout; or read them in nRF Connect. If the tablet's timeout is short, confirmed |
| 2 | **RF / HARDWARE** | Breadboard/jumper DevKit-V1 harness detunes the WROOM PCB antenna and adds rail noise → meaningful (unmeasured) margin loss, tipping a marginal link into drops | Reused DevKit-V1 "prototype harness" (`firmware/AGENTS.md`); 16 matrix lines packed across both headers straddling the module + 3 DGT-clock wires (`firmware/src/pins.h`); no antenna keep-out / ground plane / decoupling documented in HARDWARE/WIRING/PINOUT; USB-only power | **Contributing (amplifier)** | nRF Connect at 0 cm vs 2–5 m; bare-board vs harnessed-board A/B; log RSSI (target better than −70 dBm at play distance) |
| 3 | **FIRMWARE (bonding half-revert)** | Plaintext revert stripped only `_ENC` char flags; bonding stack still armed → LTK stored *if pairing is initiated*, `reason=531` desync path re-openable | `init()` still sets `sm_bonding=1; sm_sc=1; sm_our_key_dist |= BLE_SM_PAIR_KEY_DIST_ENC; sm_their_key_dist |= …ENC` (`firmware/src/ble_service.cpp:461-465`); `CONFIG_BT_NIMBLE_NVS_PERSIST=y` (`firmware/sdkconfig.defaults:23`); `ble_store_config_init()` called; `REPEAT_PAIRING` handler present (`ble_service.cpp:333-341`). Commit `e109913` touched only the two `.flags` lines. Code/comment contradiction: comment claims "no bonding dependency" (`ble_service.cpp:114-121`) while config enables full bonding | **Contributing / latent (conditional)** | Capture disconnect reason on drops: `0x13`/531 implicates bonding; check the tablet's bond list in nRF Connect for a stored bond |
| 4 | **CENTRAL (the tablet)** | Weaker radio + variable BlueDroid stack + aggressive power management picks a fragile profile the iPhone would not | "Flaky mostly on Android tablet, iOS better" asymmetry; a board *defect* would fail symmetrically across centrals; tablets ship worse antennas/front-ends and Doze/background throttling; because firmware requests nothing, the tablet dictates all params | **Likely cause (of the asymmetry)** | Device matrix: same firmware on tablet + a modern Android phone + iPhone. Only-tablet drops ⇒ central-specific |
| 5 | **APP FLOW** | Reconnect layer is sound and reactive; it masks, does not cause, drops. Minor amplifier: `WriteType.WithResponse` | `KableBoardAdapter` has bounded backoff (1s → 10s, 6 attempts), 30s connect timeout, first-pair retry (3 attempts), manual Reconnect (`SmartChessboard/…/data/board/ble/KableBoardAdapter.kt`, `…/BleBoardAdapterCore.kt`); `writeCommandFrame` uses `WriteType.WithResponse` — a timed-out WithResponse write surfaces as an error a WithoutResponse write would not | **Contributing (amplifier)** | If nRF Connect holds where the app drops, the fault is app/central integration |
| 6 | **MCU / module itself** | The ESP32-WROOM-32/32D silicon is defective | Measured silicon healthy: ESP32-D0WDQ6 rev v1.0, BT efuse set, "BT Classic + BLE 4.2" (`firmware/HARDWARE.md`); mass-deployed proven radio; `board=esp32dev` | **Unlikely** | Would require symmetric failure across all centrals + a bare board — not observed |
| — | **WiFi/BLE coexistence** | Coex contention causes drops | This is a **BLE-only** build; `main.cpp` has zero `esp_wifi` usage; no WiFi is initialized (`firmware/AGENTS.md`) | **Ruled out** | — |

**Secondary firmware notes (not primary suspects):**
- **No TX power set** — runs at ESP-IDF default, not the +9 dBm max. On a detuned antenna this is a cheap one-line margin shortfall (`firmware/sdkconfig.defaults`). *Contributing.*
- **Re-advertise logic is sound** — re-advertises on disconnect, connect-failure, and adv-complete; clears `s_conn_handle` on disconnect (`ble_service.cpp:295-306`, `288-292`, `329-331`). One self-heal gap: `start_advertising()` only logs and returns on failure (`ble_service.cpp:249-277`) — a transient `adv_start` failure leaves the board silently un-discoverable with no retry. *Low-probability but real "stuck not advertising" path.*
- **Producer/consumer notify path** — unlikely to trip supervision timeout (the NimBLE host task is separate and holds the link even if the producer stalls), but has two latent stall risks: mandatory-frame backpressure blocking the scan task up to 100 ms on a full queue (`ble_service.cpp:498-513`), and a full ANSI screen re-render + `fflush(stdout)` over UART0 on every board change at 115200 baud (`firmware/src/main.cpp:174-215, 288-292`). *Data-latency risk, not a disconnect cause.*
- **No MTU / 2M-PHY / DLE tuning** — payloads are small board-event frames so default 23-byte ATT MTU is fine; no attempt at 2M PHY (`ble_gap_set_prefer_le_phy`) which would cut on-air time. *Unlikely to matter for stability.*
- **Power/brown-out** — matrix scan drives 8 columns every ~20 ms + 2 ADC1 reads/cycle on a USB-powered breadboard; current transients + jumper inductance are a credible brown-out/RF-desense source. GPIO12 (flash-voltage strapping pin) held as pull-up input flags an electrically marginal harness. *Contributing.*

---

## 4. Was encryption really the problem?

**No.** `reason=531` (= NimBLE's `BLE_HS_ERR_HCI_BASE(0x200) + 0x13`, i.e. HCI **0x13**, "remote user terminated connection") was a **bond-state-management failure**, not evidence that encryption is inherently unstable. The distinction matters for the long-term design:

- **What actually happened on iOS.** The board was (at some point) re-flashed / NVS-erased while iOS still held the **old LTK**. On reconnect the encryption procedure failed to resolve, so **iOS deliberately tore down the link** (0x13) — which is why it needed a manual *Forget device* every time. Note the reason class: `0x13` is a **deliberate teardown**, categorically different from `0x08` (supervision timeout / RF) and `0x3D` (MIC failure / key-RF). A peripheral cannot force a central to discard its bond.
- **Why the "revert" appeared to help.** Stripping the `_ENC` characteristic flags means the peripheral no longer *demands* encryption, so a normal GATT client (iOS/Android) connecting to the now-plaintext service does not enter pairing, and the desync stopped being triggered in the happy path. But the firmware still has `sm_bonding=1` + NVS persist, so the *machinery* to form and persist a bond is intact — if pairing is ever initiated (a central that insists, or a manual nRF Connect "Bond"), a bond can still form and desync. The hazard is masked, not removed. This is close to the **worst of both worlds**: the reconnect-desync risk is dormant-but-reachable while the security benefit is gone.
- **A correctly implemented encrypted+bonded link can be perfectly stable.** Encryption was not the villain; the bug was state management spanning a surface the app cannot control (the iOS system Bluetooth bond cache — CoreBluetooth gives the app no API to clear an OS-level bond).

### Long-term recommendation

**For the MVP: choose true plaintext, and actually finish the revert.** Right now the firmware retains the machinery to create and persist LTKs for zero security payoff. For a dumb-sensor threat model (a chessboard streaming square events over a single-central link) this is a defensible call — but it must be *complete*:

- Set `ble_hs_cfg.sm_bonding = 0` and drop the ENC key-distribution flags (or set `CONFIG_BT_NIMBLE_NVS_PERSIST=n` so no LTK is ever stored).
- Keep the `BLE_GAP_EVENT_REPEAT_PAIRING` handler as a safety net.
- Resolve the code/comment contradiction at `ble_service.cpp:114-121` vs `461-465`.

**If security/authenticity is ever wanted (roadmap S-10+): encryption-done-right, end-to-end — do NOT conclude "encryption is unstable."** The done-right checklist:

- **Peripheral:** persist keys in NVS (already), distribute keys correctly (already), handle `REPEAT_PAIRING` → `ble_store_util_delete_peer` → RETRY (already present and correct). The one missing discipline: **treat a firmware re-flash / `nvs_flash_erase` as a bond-reset event** — otherwise the central keeps the old LTK and desync is guaranteed.
- **App / central:** on an encryption/pairing failure, add an explicit **recovery UX** — instruct the user to *Forget the board in system Bluetooth, then re-pair* (the app cannot clear the iOS OS bond itself). Test the re-flash and factory-reset paths on iOS specifically. Note the iOS 16+ RPA / Random-Static-Address handling change as a documented source of post-bond resolution problems for peers bonded pre-iOS-16.

| | **Plaintext-forever (MVP)** | **Encryption-done-right (S-10+)** |
|---|---|---|
| Security | None — anyone can connect/read/write | Confidentiality + authenticity |
| Reconnect risk | Zero bond desync (if truly disabled) | Requires re-flash-safe bond handling + app recovery UX |
| Effort | One-line firmware change | Firmware bond-reset discipline + app forget/re-pair flow + iOS testing |
| Fit | Dumb single-central sensor | If the board ever needs trust/authenticity |

---

## 5. Diagnostic plan to localize the fault

The whole point is to **stop guessing** and get the disconnect reason codes + RSSI + negotiated parameters on record. Do the diagnosis **before** writing more code.

### 5.1 The cleanest discriminator: nRF Connect long-idle-hold (no app)

Connect to the board from **nRF Connect** on both the flaky tablet and the iPhone, enable `board_event` notifications, and leave it connected **idle for 30+ minutes** while logging. This removes the app entirely and splits app/central faults from firmware/RF faults in one test, at zero code risk.

### 5.2 Test matrix (devices × scenarios)

| Scenario | Flaky Android tablet | Modern Android phone | iPhone |
|---|---|---|---|
| nRF Connect, idle hold 30 min | ✅ run | ✅ run | ✅ run |
| nRF Connect, active play session | ✅ | ✅ | ✅ |
| App, idle 30 min (screen on) | ✅ | ✅ | ✅ |
| App, idle (backgrounded / screen off) | ✅ | — | ✅ |
| At 0 cm (touching) vs 2–5 m | ✅ | — | ✅ |
| Bare board (nothing wired) vs harnessed | ✅ | — | — |

### 5.3 What to capture per run

- **Disconnect reason code** — firmware already logs `event->disconnect.reason` (`ble_service.cpp:296`). `0x08` ⇒ supervision timeout (params/RF); `0x13`/531 ⇒ remote-terminated (bond/OS); `0x3D` / MIC failure ⇒ key/encryption desync.
- **Negotiated connection parameters** — interval / peripheral latency / supervision timeout, from nRF Connect or a new firmware `CONNECT` + `CONN_UPDATE` log. Capture the **accepted** values (the central has final say), not just what the peripheral requested.
- **RSSI over time** — from nRF Connect (target better than −70 dBm at play distance; worse than ~−80 dBm = margin-starved).
- **App-side** — Kable disconnect cause and time-to-reconnect.

### 5.4 Decision tree (observation → culprit)

```
Does nRF Connect hold 30 min idle on the tablet (no app)?
├─ NO  → firmware and/or RF (this rules the app OUT)
│        ├─ Reason 0x08 / drops rise with distance / low RSSI
│        │     → supervision timeout + RF margin
│        │       → fix conn params (#1) AND clean up RF (#2)
│        ├─ Reason 0x13 / 531
│        │     → bonding desync → finish the plaintext revert (#3)
│        └─ Bare board holds but harnessed board drops
│              → RF degradation from the harness confirmed (#2)
│
└─ YES → the app drops where nRF Connect holds
         → fault is APP / CENTRAL integration (Kable lifecycle,
           background handling, or WithResponse writes) (#5),
           NOT firmware/RF
```

Additional splits:
- **Drops only on the tablet, never iPhone/other Android** → the specific central (tablet radio/stack) (#4); reinforced if tablet RSSI is worse.
- **App drops only when backgrounded / screen off** → Android background BLE throttling / Doze (#4), not firmware.
- **Drops on *all* devices** → firmware/RF (#1/#2), not the central.

---

## 6. Path to a stable solution

### Quick wins (do first — cheap, high leverage, low risk)

**QW-1 — Request Apple-compliant connection parameters (single biggest win).**
In `gap_event`, on `BLE_GAP_EVENT_CONNECT` (`status == 0`), after a short settle, call `ble_gap_update_params()`; also handle `BLE_GAP_EVENT_CONN_UPDATE` to log/verify what the central *accepted*. Set the PPCP kconfig values too so the preference is advertised even before the active update.

**A peripheral parameter update is a request, not a command.** It travels as an L2CAP Connection Parameter Update Request (or an LL update on newer stacks); the central may accept, reject, or counter-offer, and **iOS in particular frequently overrides peripheral-preferred parameters with its own**. So the outcome must be *validated against what the central actually grants* — a rejected request leaves the central's original (possibly short) supervision timeout in force. This is exactly why the diagnostic-first ordering matters: the `CONN_UPDATE` log tells you whether the request stuck.

Apple's rules for a compliant request (Accessory Design Guidelines), all of which must hold:

- Interval Min is a **multiple of 1.25 ms** and **≥ 15 ms**. (There is no "multiple of 15 ms" rule — BLE intervals step in the 1.25 ms LL unit; 15 ms is only the floor.)
- `IntervalMin + 15 ms ≤ IntervalMax`. Apple documents one exception: `IntervalMin == IntervalMax` is permitted **only when both equal 15 ms**.
- `IntervalMax × (Latency + 1) ≤ 2 s`.
- `IntervalMax × (Latency + 1) × 3 < supervisionTimeout`.
- Latency ≤ 30.

Older QA1931 sets supervision timeout 2–6 s; current Accessory Design Guidelines (R21) widen it to 6–18 s — **target the intersection** and verify against the current PDF (see open question #9).

Concrete Apple-safe request for this always-connected game sensor. Because we want low, responsive intervals with latency 0, the `IntervalMin + 15 ms ≤ IntervalMax` rule forces a **range** (equal min/max is only legal at 15 ms), so use min 15 ms / max 30 ms:

```c
struct ble_gap_upd_params p = {
    .itvl_min            = 12,   // 12 × 1.25 ms = 15 ms
    .itvl_max            = 24,   // 24 × 1.25 ms = 30 ms  (15 + 15 ≤ 30 ✓)
    .latency             = 0,    // a game board should not skip events
    .supervision_timeout = 480,  // 480 × 10 ms = 4.8 s  (bump toward 600 = 6 s if R21-strict iOS rejects 4.8 s)
    .min_ce_len          = 0,
    .max_ce_len          = 0,
};
ble_gap_update_params(conn_handle, &p);
```

Check the arithmetic against Apple's rules: `IntervalMax × (Latency+1) = 30 ms ≤ 2 s` ✓; `30 ms × 1 × 3 = 90 ms < 4800 ms` ✓; latency 0 ≤ 30 ✓. Longer supervision timeout = the link tolerates longer RF glitches before teardown; latency 0 keeps move events responsive. This hands the *firmware the initiative* over the number that governs drops — subject to the central agreeing. **Verify** the accepted value in the `CONN_UPDATE` log; if the newest iOS's R21 6-s floor rejects 4.8 s, widen `supervision_timeout` toward `600` (6 s).

**QW-2 — Finish the plaintext revert** (removes the `reason=531` hazard): `sm_bonding = 0`, drop the ENC key-dist flags (or `CONFIG_BT_NIMBLE_NVS_PERSIST=n`); keep the `REPEAT_PAIRING` handler; fix the code/comment contradiction.

**QW-3 — Raise TX power to max** (`esp_ble_tx_power_set` / kconfig default TX power, ~+9 dBm). This is a stationary USB-powered board — one line, buys margin on the detuned antenna. Re-test the tablet at range.

**QW-4 — Add re-advertise self-heal**: `start_advertising()` currently only logs on failure — retry with a short backoff or re-arm from a timer so the board can never get stuck un-discoverable.

**QW-5 — App: reconsider `WriteType.WithoutResponse`** for non-critical writes so a marginal-link write timeout does not surface as a spurious error.

### The real fixes (durable stability)

**RF-1 — Get off the breadboard.** Soldered wiring; route the 16 matrix jumpers + 3 DGT-clock wires **away from the module end** of the DevKit; keep the PCB-antenna region clear of the breadboard and any wire within ~15 mm; lift the module edge off the breadboard. Verify RSSI stays better than ~−70 dBm at play distance on the **worst** device. Consider a WROOM-32U + external (u.FL/IPEX) antenna if RSSI is marginal — that removes the antenna-keep-out variable entirely.

**RF-2 — Power hygiene.** Good USB cable + solid source; bulk cap (100–470 µF) + 0.1 µF across the module's 3V3/GND on the breadboard. Re-test; improvement ⇒ rail noise from USB + scan transients was contributing.

**FW-1 — Reduce producer-task stall risk.** Gate/throttle the full-screen UART render (`main.cpp:174-215`) off the notify-feeding path; verify consumer/host task stack headroom on hardware with `uxTaskGetStackHighWaterMark` under a full square burst (flagged at `ble_service.cpp:483-485`).

**APP-1 — A robust connection state machine.** Make lifecycle states explicit rather than implicit in the adapter:

```
        ┌──────────────┐
        │ Disconnected │◄─────────────────────────┐
        └──────┬───────┘                           │
               │ user/auto connect                 │ backoff exhausted
               ▼                                    │ → surface error +
        ┌──────────────┐  timeout (30s)             │   manual Reconnect
        │  Connecting  │───────────────────────────►│
        └──────┬───────┘                            │
               │ connected + services discovered    │
               ▼                                     │
        ┌──────────────┐  notify subscribed          │
        │   Syncing    │─────────────┐               │
        └──────┬───────┘             ▼               │
               │             ┌──────────────┐        │
               └────────────►│  Connected   │        │
                             └──────┬───────┘        │
                    drop (reason)   │                │
                             ┌──────▼────────┐       │
                             │  Reconnecting │───────┘
                             │ backoff 1→10s │
                             │ max 6 attempts│
                             └───────────────┘
   Special: reason=0x13/531 while encrypted
     → PairingRecovery state → prompt "Forget the board in
       system Bluetooth, then re-pair" (app cannot clear the OS bond)
```

Key properties: distinguish **transient drop** (auto-reconnect with backoff) from **bond desync** (surface the forget/re-pair UX); expose the disconnect **reason** and **RSSI** for diagnostics; keep the 30 s connect timeout so a desynced connect never hangs on "Connecting…". This layer *masks* root causes — ship it alongside QW-1/QW-2, not instead of them.

### Suggested order of operations

1. Diagnose first (Section 5) — nRF Connect idle-hold + device matrix + reason-code logging. No code risk.
2. QW-1 firmware `ble_gap_update_params` on connect **+ `CONN_UPDATE` logging to confirm the central accepted the request.**
3. QW-2 finish the plaintext revert (`sm_bonding=0`).
4. QW-3/QW-4 TX power + re-advertise self-heal.
5. RF-1/RF-2 off the breadboard + power hygiene.
6. APP-1 explicit state machine; QW-5 `WithoutResponse`.
7. Re-run the S-09 hardware gate on **both** iOS and the tablet, capturing reason codes, before declaring stability solved.
8. Defer encryption-done-right + forget/re-pair UX to a later S-10 sub-slice.

---

## 7. Open questions and recommended next experiments

### Open questions

1. **What connection interval / peripheral latency / supervision timeout does the tablet actually impose today?** Unknown until firmware logs the negotiated params or nRF Connect reports them — the single most diagnostic number for the tablet drops.
2. **What is the exact disconnect reason code on the tablet?** `0x08` ⇒ params/RF; `0x13`/531 ⇒ bonding/central. Firmware already logs it (`ble_service.cpp:296`) — it just has not been captured on the tablet.
3. **Is the instability reproducible on a clean, regulated setup, or only on the breadboard harness?** Isolates firmware/params from physical-layer causes.
4. **What is the measured RSSI at play distance on the tablet vs iPhone?** No doc records it; one number confirms or rules out an RF-margin problem — and is a prerequisite before attaching any dB figure to the harness loss (§3 #2 is deliberately left unquantified until this is captured).
5. **Do the tablet drops happen only backgrounded / screen off (Doze), or also foreground?** Points at OS power management vs firmware. (The app applies `keepScreenOn`, but Android background BLE throttling differs sharply foreground vs background.)
6. **Is a bond actually being formed on the tablet** despite the plaintext-characteristics revert (given `sm_bonding=1` + NVS persist)? This is the empirical test of whether hypothesis #3 is live or merely latent — nRF Connect's bond list would show it.
7. **On the flaky iOS case, was the board ever re-flashed / NVS-erased while iOS still held the old bond?** If yes, that fully explains `reason=531` as re-flash-induced LTK desync — the fix is bond-reset-on-reflash + an in-app forget/re-pair prompt, not abandoning encryption.
8. **Is BTDM modem sleep (`CONFIG_BTDM_CTRL_MODEM_SLEEP=y`, MODE_ORIG) contributing?** Generally safe on classic ESP32, but worth one A/B during the idle-hold test.
9. **Exact Apple supervision-timeout target** — QA1931 says 2–6 s, R21 says 6–18 s. Confirm against the current Accessory Design Guidelines PDF and pick a value valid under the newest iOS (verify ~4.8 s is not rejected under R21's 6 s floor; widen toward 6 s if so).
10. **Dedicated app-lifecycle audit** — the app investigation agent returned a stub. A focused audit of `KableBoardAdapter` connect/reconnect state management (the impl-review's F5 asymmetries) is still outstanding.

### Recommended next experiments (in order)

1. **nRF Connect long-idle-hold, no app** — 30+ min on the tablet and the iPhone. The cleanest app-vs-firmware/RF discriminator. (5 min setup.)
2. **RF isolation A — 0 cm vs 2–5 m.** Phone/tablet touching the board vs at play distance. Drops vanish at 0 cm, return at range ⇒ link margin (RF/antenna/TX power), not protocol. (5 min.)
3. **RF isolation B — bare board vs harnessed.** Flash a second dev board with nothing wired; repeat the tablet session at range. Stable bare / flaky harnessed ⇒ the wiring/breadboard is the culprit. (15 min.)
4. **Reason-code + RSSI capture** across the device matrix. Log disconnect reason (firmware) + RSSI (nRF Connect) on every drop. Splits `0x08` (RF/params) from `0x13` (bond).
5. **Device matrix** — same firmware on the tablet + a modern Android phone + iPhone, 30 min idle + active play. Only-tablet ⇒ central; all-Android ⇒ Android params/power-mgmt; all-devices ⇒ firmware/RF.
6. **Wiring dress + re-test** — route jumpers away from the module, clear the antenna region, lift the module edge; re-run experiment 2.
7. **TX-power A/B** — raise to max, re-test the tablet at range.
8. **Power-hygiene A/B** — good cable/source + bulk + 0.1 µF cap; re-run.
9. **(Optional) External-antenna spike** — WROOM-32U + whip; rock-solid where the -D breadboard is flaky ⇒ RF/antenna confirmed as the dominant hardware cause.

---

## 8. Scope note

This is **research for the S-10 slice. Nothing here is implemented.** Every finding is drawn from reading the existing firmware, the sdkconfig, the hardware docs, and the Kable adapter — no code, config, or wiring has been changed. Concrete parameter values (15–30 ms interval range, latency 0, ~4.8 s supervision timeout) and the `sm_bonding=0` change are **proposals to validate**, not applied edits; the Apple supervision-timeout figure still needs confirmation against the current Accessory Design Guidelines, and any peripheral-requested parameters must be re-checked against what the central actually grants (iOS may override). The `~10–25 dB` harness-loss figure from the draft has been intentionally removed: RSSI has not been measured on any device (open question #4), so the harness margin loss is stated qualitatively until that number exists. The diagnostic experiments in Sections 5 and 7 are intended to run **before** any implementation, so the eventual fix targets the confirmed root cause rather than the most plausible one. The app-lifecycle audit (open question 10) should be completed to fill the gap left by the stubbed app investigation before the S-10 plan is finalized.
