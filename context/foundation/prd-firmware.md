---
project: "Smart Chessboard"
document: prd-firmware
version: 1
status: draft
created: 2026-05-27
context_type: greenfield
parent_prd: context/foundation/prd.md
contracts: docs/reference/contract-surfaces.md
hardware: ESP32 (family — specific variant TBD)
language: C++
---

## Purpose

This is the firmware-specific PRD for the Smart Chessboard ESP32 firmware. It
scopes what the firmware owns, separate from the mobile and backend
sub-projects, and references `docs/reference/contract-surfaces.md` for the BLE
protocol contract.

This document is thin by design. Most of the inter-component behavior is
fixed by the BLE protocol in the contract document; what remains here is what
only the firmware is responsible for — sensing, debouncing, BLE peripheral
role, and resource use.

## Scope

In scope:

- Reed-switch matrix sampling and debouncing
- Two-button input (chess-clock layout)
- BLE peripheral role (advertising, GATT, notifications)
- Diagnostic-mode behavior
- Resource constraints (power, memory, latency)

Out of scope (covered elsewhere):

- BLE message format / GATT structure → `contract-surfaces.md` §1.2-§1.4
- Promotion handling logic → mobile (contract §1.5); firmware just reports squares
- Move validation, PGN, FEN, replay, analysis → mobile / backend
- OAuth, persistence, cloud sync → mobile / Supabase
- OTA firmware updates over BLE → post-MVP

## Hardware assumptions

- ESP32 family (specific variant TBD: classic, S3, C3, C6 — all BLE-capable).
- 64-position reed-switch matrix (8×8). Wiring topology (multiplexed vs
  direct GPIO) determined by the existing physical prototype.
- Two physical confirmation buttons, one per side, organized chess-clock
  style (PRD FR-009). MVP bring-up reuses the original **DGT chess-clock**
  buttons via diode isolation; their ~1.5 V pressed level is read on **ADC1**
  (GPIO34/35) with a software threshold, not as a digital input. This is a
  firmware/wiring detail with **no contract impact** — `BUTTON_EVENT` is
  unchanged. See `firmware/HARDWARE.md`.
- Power: **USB-only for MVP** (OQ-2 resolved, F-03) — no sleep modes, no battery
  NFR. Battery support is post-MVP.
- No LEDs, display, or speaker required in MVP.

## Functional Requirements

### Sensing

- **FR-FW-001**: Firmware samples the full reed-switch matrix at a rate
  sufficient to detect lift and place events for a piece moved at normal
  play tempo. Target: effective per-square sampling ≥ 50 Hz after debouncing.
  Priority: must-have.

- **FR-FW-002**: Firmware applies debouncing so that a transient or noisy
  switch reading does not generate a spurious `SQUARE_EVENT`. The debouncing
  window is calibrated against the physical board to balance responsiveness
  against false positives. Priority: must-have.

### BLE peripheral

- **FR-FW-003**: Firmware advertises as a BLE peripheral with a fixed local
  name (format per `contract-surfaces.md` §1.1) and a chess-board service
  UUID. Only one central may be connected at a time. Priority: must-have.

- **FR-FW-004**: Firmware exposes the GATT structure defined in
  `contract-surfaces.md` §1.2: one service, `board_event` characteristic
  (notify) and `mobile_command` characteristic (write). Priority: must-have.
  - 2026-06-30 (S-09): both characteristics now **require an encrypted link**
    (`contract-surfaces.md` §1.1/§1.2). `mobile_command` write is gated by
    `BLE_GATT_CHR_F_WRITE_ENC`; `board_event` subscribe is gated by
    `BLE_GATT_CHR_F_NOTIFY_INDICATE_ENC` (this NimBLE build propagates it to the
    auto-CCCD's write permission). A central must bond — Just-Works
    (`NO_INPUT_OUTPUT`) → encrypted-but-unauthenticated — before subscribing or
    writing. The message catalog and GATT shape are unchanged; only the access
    permission tightens. The write path is the load-bearing bond trigger (the
    app writes `REQUEST_SNAPSHOT` on every (re)connect).

- **FR-FW-005**: On a new connection, firmware emits a `BOARD_SNAPSHOT`
  describing the current state of all 64 squares (so mobile can reconcile
  with its expected position). Priority: must-have.
  - 2026-06-16 (F-02): the `BOARD_SNAPSHOT` byte layout is clarified in
    `contract-surfaces.md` §1.3 — byte `i` bit `j` (LSB-first) = square `i*8 + j`
    (byte 0 = rank 1, `a1` = byte 0 bit 0). Firmware must pack snapshot bytes in
    this order to match the mobile decoder verified against the emulator.

- **FR-FW-006**: Firmware emits `SQUARE_EVENT` for every debounced
  reed-switch lift or place, with square index per the contract's
  `index = file + 8 * rank` convention. Priority: must-have.

- **FR-FW-007**: Firmware emits `BUTTON_EVENT` for each physical button
  press, identifying which side (white / black) pressed. Priority: must-have.

- **FR-FW-008**: Firmware emits `DEVICE_STATUS` (firmware version, uptime,
  optional battery) on connect, on request, and periodically (target every
  ~30 s). Priority: nice-to-have for battery field if USB-powered.

### Mobile commands

- **FR-FW-009**: Firmware handles `SET_MODE` writes by entering either game
  or diagnostic mode. In diagnostic mode it emits `BOARD_SNAPSHOT` at higher
  rate (target 10 Hz) in addition to `SQUARE_EVENT` on change.
  Priority: must-have.

- **FR-FW-010**: Firmware handles `REQUEST_SNAPSHOT` by emitting an
  immediate `BOARD_SNAPSHOT`. Priority: must-have.

- **FR-FW-011**: Firmware handles `REQUEST_STATUS` by emitting an immediate
  `DEVICE_STATUS`. Priority: nice-to-have.

### Operational behavior

- **FR-FW-012**: Firmware survives mobile disconnect/reconnect cycles
  without requiring a power cycle. After a disconnect, firmware continues
  advertising so the mobile central can reconnect. Priority: must-have.

- **FR-FW-013**: Firmware persists no game state across power cycles in
  MVP. After boot, square states are re-sensed from the current physical
  positions of pieces; mobile reconciles via `BOARD_SNAPSHOT` on
  reconnect. Priority: must-have (intentional simplification — no flash
  writes in MVP).

## Non-Functional Requirements

- **Latency**: a `SQUARE_EVENT` for a physical lift or place is delivered
  over BLE no later than 100 ms after the (debounced) state change. This
  budget supports the parent PRD's 500 ms end-to-end NFR.
- **Power**: idle behavior must not prevent USB-powered operation; battery
  power is out of MVP scope unless explicitly added (see Open Questions).
- **Connectivity**: firmware uses BLE only — Wi-Fi peripherals are not
  initialized, no Wi-Fi credentials are stored on the device.
- **Identifiability**: each board has a stable local name (derived from
  MAC) so that re-pairing the same board is reliable.

## Constraints

- **Language**: C++.
- **Toolchain**: ESP-IDF or Arduino framework (decision deferred to
  Open Questions). Either is BLE-capable; the choice affects developer
  ergonomics, not protocol compatibility.
- **Memory**: typical ESP32 RAM/flash is sufficient; no extraordinary
  constraints.

## Out of MVP

- LED feedback or visual hints on the board.
- OTA firmware updates over BLE.
- Multi-board scenarios (one board, one paired mobile in MVP).
- Battery management features (low-battery warning UI, deep sleep, etc.) —
  may be added if battery power is selected.
- **Authenticated** pairing (MITM protection) of the board to the mobile.
  The MVP link is encrypted (Just-Works, encrypted-but-unauthenticated; S-09
  forced encryption on both characteristics, 2026-06-30) — a step up from the
  original plaintext trust-on-first-pair. Authenticated pairing needs a
  display/keypad the board lacks and stays post-MVP.

## Open Questions

1. **Specific ESP32 variant** — classic, S3, C3, C6, or other. Determines
   exact BLE stack APIs and toolchain compatibility. Owner: hardware
   build. Latest acceptable resolution: before firmware implementation.
   Block: no.

2. **Power source for MVP** — USB-only (simplest, no sleep modes, no
   battery NFR) vs battery (adds sleep, battery status, charging UX).
   Recommendation: USB-only for MVP to maximize speed; battery is a
   post-MVP enhancement. Owner: hardware build. Latest acceptable
   resolution: before firmware implementation. Block: no.
   > Resolved 2026-06-19 (F-03): **USB-only** for MVP, as recommended. No
   > sleep modes and no battery NFR; `DEVICE_STATUS.battery_pct` is a
   > deliberate fixed constant `100` (within the documented 0–100 range),
   > not a real reading. Battery power (sleep, charging UX, low-battery
   > warning) stays a post-MVP enhancement.

3. **Reed-switch matrix wiring** — multiplexed (shift registers / row-
   column scan) vs direct GPIO. The pre-existing physical prototype
   already commits to a wiring; this Open Question is about documenting
   it. Owner: hardware build. Latest acceptable resolution: before
   sensing code is written. Block: no.
   > Resolved 2026-06-28: **8×8 direct GPIO**, native scan. The real bring-up
   > wiring drives the columns as outputs (LOW one at a time) and reads the rows
   > as pull-up inputs (anti-ghosting diodes one-per-square, cathode toward the
   > column — installed and required). Authoritative pin map: `firmware/src/pins.h`;
   > per-board detail in `firmware/HARDWARE.md` / `PINOUT.md`.

4. **Firmware toolchain** — ESP-IDF (more control over BLE stack and
   power management) vs Arduino framework (faster start, smaller learning
   curve). Owner: firmware implementer. Latest acceptable resolution:
   before first firmware commit. Block: no.
   > Resolved 2026-06-19 (F-03): **ESP-IDF** (driven via PlatformIO), with
   > the **NimBLE** host as the BLE stack — smaller heap/flash footprint
   > than Bluedroid, single connection, one GATT service. This matches the
   > `tech-stack.md` firmware decision (ESP-IDF + NimBLE). No Arduino
   > framework.

5. **GATT service / characteristic UUIDs** — to be assigned and recorded
   back into `contract-surfaces.md` §1.2 during firmware implementation.
   Owner: firmware implementer. Latest acceptable resolution: before
   first firmware commit. Block: no.
   > Resolved 2026-06-19 (F-03): UUIDs assigned and recorded in
   > `contract-surfaces.md` §1.2 — 128-bit base
   > `787e000X-15a4-4fc9-a469-05096dbad1a1`, where `X` = 1 (primary
   > service) / 2 (`board_event`, notify) / 3 (`mobile_command`, write).
   > Firmware and the mobile BLE adapter (S-09) must use these exact bytes.
   > Per the contract's §1 change-control rule, the same §1.2 assignment is
   > mirrored into `prd.md`'s Implementation Decisions.

## Validation strategy (no-hardware)

Per the parent PRD's Open Question 1 resolution (2026-05-26), the MVP
combines:

1. Unit tests of pure firmware-side logic (debouncing, square indexing,
   BLE message encoding) where possible without hardware.
2. A programmatic emulator on the mobile side — an in-app implementation
   of the BLE peripheral interface that produces the same `board_event`
   stream as a real board, driven by test scripts. This lets the
   physical-mode flow be exercised end-to-end without the board.
3. (Nice-to-have) Recorded hardware fixtures: real BLE message logs from
   the physical board, replayable for regression coverage of debouncing
   artifacts.

Firmware-only validation (without the mobile) is limited to unit-testable
pure logic in MVP; full firmware-on-hardware integration tests are
manual.

## References

- Parent PRD (system + mobile FRs): `context/foundation/prd.md`
- Interface contracts (BLE protocol, etc.): `docs/reference/contract-surfaces.md`
