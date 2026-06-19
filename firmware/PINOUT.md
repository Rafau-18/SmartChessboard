---
project: "Smart Chessboard"
document: board-pinout
version: 1
status: draft
created: 2026-05-28
updated: 2026-06-19
---

# Board Pinout — ESP32-DevKitC V4 & ESP32 DevKit V1

NodeMCU-style header maps for the two ESP32 boards in the inventory
(`HARDWARE.md`), with the firmware's 8×8 reed-switch matrix pins marked in
place. Pin order verified against the Espressif esp-dev-kits user guide
(DevKitC V4) and the official DOIT/playelek PINOUT PDF (DevKit V1).

## Legend

- `[R0]`..`[R7]` — matrix **row** outputs (driven LOW one at a time). `Rn` = rank n+1.
- `[C0]`..`[C7]` — matrix **column** inputs (internal pull-up). `Cn` = file (a..h).
- `*` — strapping pin (boot-mode sensitive; see caveats below).
- `(in)` — input-only pin (no output, no internal pull-up) → never in the matrix.
- `(fl)` — SPI-flash pin (GPIO6–11) → never use.

The `[Rn]`/`[Cn]` tags below show the **recommended hazard-free map**
(`kRowPins = {13,14,25,26,27,32,33,5}`, `kColPins = {16,17,18,19,21,22,23,4}`).
Square index = `file + 8*rank`.

> **⚠ Current `src/pins.h` differs.** It uses an existing **prototype** wiring on
> the DevKit V1 (rows D32→D13, columns D19→D15) so the bringup runs without
> rewiring. The file-g column was moved off GPIO2 (onboard LED) to GPIO21; the
> only remaining watch item is GPIO12 (flash-strapping). Migrating fully to the
> map drawn here is a tracked TODO in the `pins.h` header.

---

## Board A — ESP32-DevKitC V4 (WROOM-32D, 38 pins) — PRIMARY

```
                    ESP32-DevKitC V4  (38 pins)
                     ___________________
              3V3 --|                   |-- GND
           EN/RST --|                   |-- GPIO23  [C6]
       (in) GPIO36 --|                   |-- GPIO22  [C5]
       (in) GPIO39 --|                   |-- GPIO1   TX0
       (in) GPIO34 --|                   |-- GPIO3   RX0
       (in) GPIO35 --|                   |-- GPIO21  [C4]
       [R5] GPIO32 --|      ESP32        |-- GND
       [R6] GPIO33 --|     WROOM-32D     |-- GPIO19  [C3]
       [R2] GPIO25 --|                   |-- GPIO18  [C2]
       [R3] GPIO26 --|                   |-- GPIO5   [R7] *
       [R4] GPIO27 --|                   |-- GPIO17  [C1]
       [R1] GPIO14 --|                   |-- GPIO16  [C0]
        *   GPIO12 --|                   |-- GPIO4   [C7] *
              GND --|                   |-- GPIO0   * BOOT
       [R0] GPIO13 --|                   |-- GPIO2   * LED
       (fl) GPIO9  --|                   |-- GPIO15  *
       (fl) GPIO10 --|                   |-- GPIO8   (fl)
       (fl) GPIO11 --|                   |-- GPIO7   (fl)
               5V --|___________________|-- GPIO6   (fl)
                          [ USB ]
```

The 6 extra pins vs the V1 are the flash pins GPIO6–11 (`(fl)`) — unusable, so
no practical GPIO is gained over the 30-pin V1.

---

## Board B — ESP32 DevKit V1 / DOIT (WROOM-32, 30 pins) — FIRST FLASH TARGET

```
                  ESP32 DevKit V1 / DOIT (30 pins)
                     ___________________
           EN/RST --|                   |-- GPIO23  [C6]
       (in) GPIO36 --|                   |-- GPIO22  [C5]
       (in) GPIO39 --|                   |-- GPIO1   TX0
       (in) GPIO34 --|                   |-- GPIO3   RX0
       (in) GPIO35 --|      ESP32        |-- GPIO21  [C4]
       [R5] GPIO32 --|     WROOM-32      |-- GPIO19  [C3]
       [R6] GPIO33 --|                   |-- GPIO18  [C2]
       [R2] GPIO25 --|                   |-- GPIO5   [R7] *
       [R3] GPIO26 --|                   |-- GPIO17  [C1]
       [R4] GPIO27 --|                   |-- GPIO16  [C0]
       [R1] GPIO14 --|                   |-- GPIO4   [C7] *
        *   GPIO12 --|                   |-- GPIO2   * LED
       [R0] GPIO13 --|                   |-- GPIO15  *
              GND --|                   |-- 3V3
              VIN --|___________________|-- GND
                          [ USB ]
```

> **Silkscreen note:** some 30-pin DOIT revisions break out `GPIO0` on the right
> column (between GPIO4 and GPIO2) and shuffle the bottom power pins. It does not
> affect this design — `GPIO0` is **not** used by the matrix. Trust the GPIO
> numbers on your board's silkscreen over absolute positions.

---

## Matrix pin cross-reference (both boards)

All 16 pins are broken out on **both** boards. Seven row pins (R0–R6) sit on the
**left** header; R7 (GPIO5) and all eight columns sit on the **right** header —
the same on both boards.

| Matrix | GPIO | Header (V4 / V1) | Caveat |
|---|---|---|---|
| R0 (rank 1) | 13 | LEFT / LEFT | JTAG TCK — only matters if you attach a JTAG probe |
| R1 (rank 2) | 14 | LEFT / LEFT | emits a brief PWM pulse at boot; safe as a row driver |
| R2 (rank 3) | 25 | LEFT / LEFT | — (also DAC1) |
| R3 (rank 4) | 26 | LEFT / LEFT | — (also DAC2) |
| R4 (rank 5) | 27 | LEFT / LEFT | — |
| R5 (rank 6) | 32 | LEFT / LEFT | — |
| R6 (rank 7) | 33 | LEFT / LEFT | — |
| R7 (rank 8) | 5  | RIGHT / RIGHT | **strapping** — must be HIGH at boot; driving it as a row is fine, just no strong external pull-down at reset |
| C0 (file a) | 16 | RIGHT / RIGHT | — (RX2; only reserved on WROVER/PSRAM modules, not these) |
| C1 (file b) | 17 | RIGHT / RIGHT | — (TX2; same note) |
| C2 (file c) | 18 | RIGHT / RIGHT | — |
| C3 (file d) | 19 | RIGHT / RIGHT | — |
| C4 (file e) | 21 | RIGHT / RIGHT | default I2C SDA (unused here) |
| C5 (file f) | 22 | RIGHT / RIGHT | default I2C SCL (unused here) |
| C6 (file g) | 23 | RIGHT / RIGHT | — |
| C7 (file h) | 4  | RIGHT / RIGHT | **strapping** (weak boot pull-down) — harmless: the internal pull-up we enable on columns overrides it |

**Not exposed / avoided:** GPIO6–11 (flash), GPIO34/35/36/39 (input-only, no
pull-up), GPIO0/2/12/15 (strapping, kept out of the matrix). None are used.

---

## Confirmation buttons (F-03) — additive, watch the target-map overlap

The F-03 game firmware reads two confirmation buttons (FR-FW-007) on
**GPIO22 (white → `BUTTON_EVENT 0x00`)** and **GPIO23 (black → `0x01`)**, wired
as momentary switches to **GND** with the internal pull-up (idle HIGH, pressed
LOW). They are **additive** — none of the matrix wiring above changes.

> ⚠ **Overlap with the hazard-free target map.** The recommended column map at
> the top of this file claims GPIO22 = **C5 (file f)** and GPIO23 = **C6
> (file g)** — the buttons sit on those same two pins. This is harmless
> **today** because the current `src/pins.h` prototype uses a *different*
> column set (`{19,18,5,17,16,4,21,15}`), leaving GPIO22/23 free. But a future
> clean build that adopts the target map drawn here must relocate **either**
> the two buttons **or** files f/g — they cannot both occupy GPIO22/23.

## Wiring tip

Per square: one reed switch bridges its **row line** (left header) to its
**column line** (right header). For a bringup with ≤2 magnets no diodes are
needed; full gameplay needs one 1N4148 per square (see `README.md` §1 / §6).

Because the two boards are pin-equivalent for every usable GPIO, the same
`src/pins.h` and the same wiring work on **both** the DevKit V1 and the
DevKitC V4 — swap the board, not the firmware.

## References

- [ESP32-DevKitC V4 — Espressif esp-dev-kits user guide](https://docs.espressif.com/projects/esp-dev-kits/en/latest/esp32/esp32-devkitc/user_guide.html)
- [DOIT ESP32 DevKit V1 — official PINOUT PDF](https://dratek.cz/docs/produkty/0/719/pinout.pdf)
- [DOIT ESP32 DevKit V1 — espboards.dev](https://www.espboards.dev/esp32/esp32doit-devkit-v1/)
- [ESP32 Pinout Reference — Last Minute Engineers](https://lastminuteengineers.com/esp32-pinout-reference/)
- Project: `firmware/HARDWARE.md`, `firmware/README.md` §1, `firmware/src/pins.h`
