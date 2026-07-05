# AGENTS.md — firmware (ESP32 reed-matrix)

Module-scoped guidance for the ESP32 reed-matrix firmware. **Monorepo-wide rules live in the root [`../AGENTS.md`](../AGENTS.md)** (imported by `../CLAUDE.md`) — commit conventions, the sub-project map, and the gitignored-files list. This file adds only firmware-specific depth.

## Status: game firmware implemented (F-03); board repaired & on-hardware verified

The **firmware software is greenlit and implemented.** F-03 (`firmware-ble-gatt-service`, 2026-06-19) turned the original diagnostic bringup into the full BLE **game firmware**: a NimBLE peripheral speaking the `../docs/reference/contract-surfaces.md` §1 protocol byte-for-byte. The diagnostic bringup it grew from was verified on real hardware (2026-05-28).

**The physical reed matrix was repaired (2026-06-28)** and the firmware adapted to the real wiring (matrix-scan inversion + DGT-clock ADC buttons on GPIO34/35). It **no longer gates** the mobile S-09 slice — that slice is now in implementation. Resume and modify firmware-software normally when asked.

On-hardware verification of F-03 (advertising, bonding, the on-subscribe burst, live square/button events, the diagnostic stream, reconnect, malformed-write ignore) — gates 2.4–3.10 — was **confirmed 2026-06-29** (nRF Connect, board `SmartChessboard-DA3A`); tracked in [`../context/changes/firmware-ble-gatt-service/manual-verification.md`](../context/changes/firmware-ble-gatt-service/manual-verification.md). S-09 Phase 2 additionally verified the encrypted-bond delta on the same board (2026-06-30).

## What this is

**The one-paragraph "what it is", the dumb-sensor contract, the stack, and square indexing are in [`README.md`](README.md).** This section keeps only the history that explains the current code shape.

It grew from the earlier **diagnostic-only** bringup (8×8 scan → live serial occupancy render), whose sole purpose was to prove the physical wiring and pin map on hardware before any game/BLE logic existed. That scan/debounce core (~50 Hz scan, ~80 ms 4-scan debounce, `index = file + 8*rank`) is reused unchanged; F-03 layered on the BLE radio, the byte codec, the buttons, diagnostic mode, and the connection lifecycle. The serial `render()` survives as a local debug aid, orthogonal to the BLE contract path.

## Build / flash / monitor

Prerequisites and the command set live in [`README.md`](README.md). `pio` is the only entry point — it manages the ESP-IDF + xtensa toolchain itself (downloaded into `.pio/` on first `pio run`); you do **not** set `IDF_PATH` manually for the `pio` flow (the `$ENV{IDF_PATH}` in `CMakeLists.txt` is only for a direct `idf.py` build, which this project doesn't use).

Agent rule: run `pio test -e native` before declaring any `lib/` change green — it asserts the byte codec against the **same golden vectors** as the Kotlin `BoardWireCodecTest.kt` (plus debounce and `stable`-diff → square-event logic), so it is the fast contract-drift guard between this firmware and the mobile emulator. `pio run -e esp32dev` is the explicit device build (same as a bare `pio run`).

## Architecture

The firmware is split into pure, host-testable logic (`lib/`) and the device runtime (`src/`).

### Pure logic — `lib/` (host-tested, no ESP-IDF / GPIO)

- `lib/board_protocol/` — the byte codec: encoders for the four board→mobile events (`BOARD_SNAPSHOT` / `SQUARE_EVENT` / `BUTTON_EVENT` / `DEVICE_STATUS`), the decoder for the three mobile→board commands (`SET_MODE` / `REQUEST_SNAPSHOT` / `REQUEST_STATUS`), snapshot bit-packing, and the `stable`-diff → square-event derivation. Byte-for-byte to `../docs/reference/contract-surfaces.md` §1; decoding is **total** (malformed/unknown/wrong-length/reserved frames map to a `Malformed` result, never a throw). Single source of truth for wire bytes — a twin of the mobile `BoardWireCodec.kt`.
- `lib/debounce/` — the per-square agreement-counter debounce (`kStableScans=4`) extracted from the old superloop into a pure `step(rawScan) → stable` unit.

Both are pure C++ (`<cstdint>` / `<cstddef>` only) so they compile into the `[env:native]` host-test build.

### Device runtime — `src/`

- `src/main.cpp` — the scan/button **producer**. A FreeRTOS loop drives the matrix scan (`scan_matrix`), feeds `lib/debounce`, computes the `placed`/`lifted` diff each cycle, and posts **one event per changed square (never coalesced)** plus button-press edges onto a FreeRTOS **event queue**. It also services *snapshot-requests* by reading its **own** `stable` bitmap and enqueuing a `BOARD_SNAPSHOT` — the producer is the sole owner of `stable`, so no other context reads it (a cross-task 64-bit read can tear on the dual-core ESP32). The producer **never** calls BLE APIs.
- `src/ble_service.{h,cpp}` — the NimBLE peripheral + the queue **consumer**. Initializes NVS + the NimBLE host, registers the one GATT service, advertises (service UUID in the advertisement, `SmartChessboard-XXXX` name in the scan response), runs the GAP/GATT lifecycle (Just-Works bonding, single central, re-advertise on disconnect), drains the event queue and calls `ble_gatts_notify_custom` (gated on connected + the `board_event` CCCD subscribe flag), decodes `mobile_command` writes, and runs the ~30 s `DEVICE_STATUS` and ~100 ms diagnostic-snapshot software timers. Timers and the write handler only **enqueue** requests — they never touch `stable` or call notify directly.

Scan cadence ~50 Hz (`kScanIntervalMs`=20), so debounce latency is ~80 ms; the queue→notify hop is sub-ms, comfortably inside the ≤100 ms latency NFR.

**Connection-lifecycle nuance:** the contract's "on connect, emit `BOARD_SNAPSHOT` then `DEVICE_STATUS`" actually fires on the **CCCD subscribe** (a notify with no subscriber is dropped), not the raw GAP connect; `mode` resets to GAME on connect, and a disconnect stops the timers and re-arms advertising.

**Backpressure** is keyed on the §1.3 tag: `SQUARE_EVENT` / `BUTTON_EVENT` carry the non-coalesceable transition stream `SequenceInterpreter` depends on, so they are never silently dropped on a live subscribed link (block-then-log); diagnostic `BOARD_SNAPSHOT`s are idempotent (latest wins) and may drop under pressure.

**Square indexing** is the contract surface: `index = file + 8*rank` (a1=0 … h8=63), matching `../docs/reference/contract-surfaces.md` §1.3. Orientation is **calibrated in software**: if a flashed board shows the wrong/mirrored/rotated square, edit `square_index()` in `main.cpp` — do **not** rewire and do not change `pins.h`.

### Confirmation buttons

Two momentary confirmation buttons (FR-FW-007), **additive to the matrix** — the original **DGT chess-clock** buttons brought out via diode isolation and read on **ADC1: GPIO34 (white)** / **GPIO35 (black)**. Terminal C idles near 0 V and rises to ~1.5 V when pressed — below the ESP32 digital-HIGH threshold — so the firmware thresholds the ADC value (Schmitt hysteresis) rather than reading a digital pin, emitting `BUTTON_EVENT` `0x00` (white) / `0x01` (black), one per press edge. They are bare events: the firmware does no turn validation (the mobile re-derives whose turn it is). **Common ground (clock battery-minus ↔ ESP32 GND) is mandatory and the DGT clock must be powered on**, or the buttons read nothing. GPIO34/35 are input-only ADC1 pins, unused by the matrix; the earlier GPIO22/23 digital pins are now free. ADC-direct is the MVP-target read (an NPN transistor buffer is an optional post-MVP refinement). Full wiring + electrical detail: `HARDWARE.md` ("Confirmation buttons (F-03)").

## Critical gotcha: two different pin maps

`src/pins.h` is the **single source of truth for what is actually flashed** — and it intentionally **differs** from the pin tables in `PINOUT.md`/`WIRING.md`.

- `pins.h` = the **reused DevKit V1 prototype harness** (two consecutive header blocks). It is the bringup/test wiring, not hazard-free. Notable: ROW6 is on **GPIO12** (flash-voltage strapping — first suspect if the board ever boot-loops); COL6 was moved off GPIO2 (onboard LED) to GPIO21.
- `PINOUT.md`/`WIRING.md` = the **hazard-free target** map for a clean build.

Do **not** "fix" `pins.h` to match the README — that would break the verified prototype. If you genuinely need to rewire, change `pins.h` and re-verify on hardware.

## Config & conventions

- `sdkconfig`, `sdkconfig.old`, `sdkconfig.esp32dev` are **gitignored** (generated). The committed seed is `sdkconfig.defaults` — edit that for reproducible config, not the generated files.
- `sdkconfig.defaults` now enables **BT + the NimBLE host + NVS-backed bond persistence**, constrained to a single connection (peripheral-only). It is BLE-only — no Wi-Fi is initialized. Edit the seed, never the generated `sdkconfig*` (re-generated on the next build).
- `esp32dev` is a **placeholder** board id (Open Question FW-1); swap in `platformio.ini` when the final ESP32 variant is chosen (`pio boards espressif32` lists options).

## Docs map

- `README.md` — what the firmware is, prerequisites, build/flash/test commands, code layout, troubleshooting.
- `HARDWARE.md` — board inventory & comparison; on-hardware verification log; per-board notes; the F-03 confirmation-button pins.
- `PINOUT.md` — per-board header pinout diagrams (DevKitC V4 + DevKit V1) with matrix pins marked; the F-03 button-pin note.
- `WIRING.md` — the 16-wire connection list and per-square wiring.
- `../context/changes/firmware-ble-gatt-service/` — the F-03 change folder (plan, research, and the `manual-verification.md` log for the BLE game firmware).
- Canonical context (one level up): `../context/foundation/prd-firmware.md`, `../context/foundation/tech-stack.md`, `../docs/reference/contract-surfaces.md` §1, `../context/foundation/lessons.md` (recurring rules — consult before changes).
