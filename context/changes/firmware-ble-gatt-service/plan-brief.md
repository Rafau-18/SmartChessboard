# Firmware BLE GATT Service (F-03) — Plan Brief

> Full plan: `context/changes/firmware-ble-gatt-service/plan.md`
> Research: `context/changes/firmware-ble-gatt-service/research.md`

## What & Why

Build the ESP32 **game firmware** that implements the §1 BLE board contract end-to-end — a NimBLE peripheral with one GATT service (`board_event` notify + `mobile_command` write) that speaks the byte protocol byte-for-byte, so a real board is ready for S-09 to drive. F-03 has no user-visible outcome on its own; it satisfies the firmware half of the contract the F-02 emulator already implements on the mobile side.

## Starting Point

The diagnostic firmware already scans the 8×8 reed matrix (~50 Hz), debounces (4 scans / ~80 ms), and packs occupancy into a `uint64_t` with `index = file + 8*rank` — all hardware-verified (2026-05-28). There is **no** BLE, no byte codec, no buttons, no `firmware/lib/`, and no host-test build; `sdkconfig.defaults` is BLE-free. The mobile side already has a complete, golden-vector-tested Kotlin codec (`BoardWireCodec.kt`) that is the byte-for-byte reference.

## Desired End State

A flashed board advertises as `SmartChessboard-XXXX`, bonds one BLE central, pushes a snapshot+status burst on subscribe, streams a `SQUARE_EVENT` per debounced reed transition, emits `BUTTON_EVENT` from two physical buttons, answers the three write commands, pushes periodic/diagnostic snapshots, and re-advertises after disconnect. `pio test -e native` proves the codec/debounce match the Kotlin golden vectors; the device image builds; on-hardware behavior is confirmed best-effort. Full mobile-app end-to-end on a repaired board is S-09.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| BLE host stack | NimBLE (not Bluedroid) | BLE-only, smaller heap/flash, Espressif-recommended, portable across variants | Research (D1) |
| GATT UUIDs | `787e0001/0002/0003-15a4-4fc9-a469-05096dbad1a1` (service/board_event/mobile_command), written to contract §1.2 | Shared contract surface; minted base+offset family | Plan (D2) |
| BLE↔scan architecture | Scan/buttons producer → FreeRTOS queue → BLE consumer notify; `xTimer` for status + diagnostic | Keeps scan timing clean; never call BLE from scan context; meets ≤100 ms NFR | Research (D7) |
| Integration scope | Full firmware (BLE+GATT+buttons+diag+lifecycle); host-test the pure logic; verify radio on hardware | Realizes the roadmap end state; user has a board + temp buttons to test on | Plan (Q1) |
| Confirmation buttons | Implement now on GPIO22 (white) / GPIO23 (black), additive to `pins.h` | Completes FR-FW-007; pins are WROOM-32 bonded-out, pull-up capable | Plan (Q2 + D3) |
| Golden-vector risk | Trust the Kotlin vectors; reconcile only if a discrepancy surfaces | Vectors look self-consistent; no upfront reconciliation phase | Plan (Q3) |
| `lib/` extraction | Pure logic only — codec + snapshot packing + debounce-as-function; scan loop & timing stay in `main.cpp` | Maximizes host-test coverage without rewriting the hardware-verified scan path | Plan (Q4 + D9) |
| `battery_pct` on USB | Constant `100` (0x64) | In the documented 0–100 range, no contract change | Plan (Q5 + D5) |
| Firmware version | `1.0.0` | Matches the emulator's default | Plan (Q6 + D8) |
| Test harness | `firmware/lib/` + `[env:native]` + Unity, asserting the Kotlin golden vectors | Cross-language oracle directly attacks the contract-drift risk | Research (D9) |

## Scope

**In scope:** NimBLE peripheral + advertising + bonding (single central); one GATT service (notify + write); byte encoders for `BOARD_SNAPSHOT`/`SQUARE_EVENT`/`BUTTON_EVENT`/`DEVICE_STATUS`; command decoder for `SET_MODE`/`REQUEST_SNAPSHOT`/`REQUEST_STATUS`; two buttons on GPIO22/23; diagnostic (~10 Hz) + periodic status (~30 s); connect/subscribe burst + reconnect; `firmware/lib/` + native Unity tests on the golden vectors; UUID write-back to the contract; docs unpark + OQ resolutions.

**Out of scope:** reed-scan rewrite; any chess logic on the board; persistence across power cycles; battery management/sleep; OTA/LEDs/multi-board/board-auth; MTU negotiation; a NimBLE host-mock; an upfront golden-vector reconciliation phase; the mobile-side BLE adapter (S-09); the reed-matrix hardware repair (parked).

## Architecture / Approach

The hardware-verified scan/debounce loop and the two button reads run as a **producer** that posts a small frame struct to a FreeRTOS **queue** whenever the debounced `stable` bitmap changes or a button edge fires — never touching BLE APIs. A **consumer** in the NimBLE host context drains the queue and notifies on `board_event`, gated on the CCCD-subscribe flag. Software timers post periodic `DEVICE_STATUS` (~30 s) and, in diagnostic mode, ~10 Hz snapshots onto the same queue. All bytes are built by the pure `firmware/lib/board_protocol` encoders — the same code the host tests assert against the Kotlin golden vectors.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Protocol core + host harness | `firmware/lib/` (codec + debounce) + `[env:native]` Unity tests on the golden vectors | Behavior drift while extracting debounce from `main.cpp` (guarded by the native test) |
| 2. NimBLE peripheral | Advertising, bonding, one GATT service, connect/subscribe burst, re-advertise; UUIDs in contract | `ble_gatts_notify_custom` symbol vs installed ESP-IDF; 31-byte advert budget (name → scan response) |
| 3. Game behavior | Scan→queue→notify `SQUARE_EVENT`s, buttons, write-command dispatch, diagnostic + status timers | Per-transition streaming correctness; buttons untestable until physically wired |
| 4. Docs + contract | Unpark `firmware/AGENTS.md`, resolve `prd-firmware.md` OQs, note buttons in HW docs | Low |

**Prerequisites:** PlatformIO Core installed (`brew install platformio`); the frozen `contract-surfaces.md` §1 (done); the Kotlin golden vectors (done). On-hardware verification needs the (partially-working) board + temporary buttons — automated phases need neither.
**Estimated effort:** ~3–4 sessions across 4 phases (Phase 1 pure-logic + harness; Phases 2–3 the BLE build; Phase 4 docs).

## Open Risks & Assumptions

- **Golden vectors are not yet human-verified** against the contract prose (`BoardWireCodecTest.kt` TODO F-02 manual gate 2.3/2.4). We trust them; a subtly-wrong vector would be agreed-upon by both Kotlin and firmware. Mitigation: reconcile against §1.3/§1.4 prose the moment any discrepancy appears.
- **Buttons and some reed switches are unbuilt/partially-broken hardware** — button firmware and some squares can only be exercised with temporary rigs; full verification is S-09.
- **NimBLE symbol/API drift** (`ble_gatts_notify_custom`) — verify against the installed ESP-IDF at build time.
- **`battery_pct=100` is a placeholder**, not a reading — fine while USB-only.

## Success Criteria (Summary)

- `pio test -e native` passes — encoders/decoder/debounce match the Kotlin golden vectors byte-for-byte.
- `pio run -e esp32dev` builds the full NimBLE device image; the three UUIDs are recorded in `contract-surfaces.md` §1.2.
- On hardware (best-effort): the board advertises, bonds, emits the on-subscribe burst, streams live square/button events, serves diagnostic mode and the three commands, and recovers across a reconnect.
