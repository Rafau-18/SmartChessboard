---
project: "Smart Chessboard"
document: hardware-inventory
version: 1
status: draft
created: 2026-05-28
updated: 2026-05-28
---

# Hardware Inventory & Board Comparison

## Purpose

This document inventories the three ESP-family boards the user already owns and
ranks them for the Smart Chessboard firmware. The firmware (ESP-IDF + NimBLE)
has two hard hardware needs: enough usable GPIO to scan an 8x8 reed-switch
matrix natively (16 lines — 8 row outputs + 8 column inputs with internal
pull-ups), and a working **BLE peripheral** radio to stream board events to the
mobile app. Each board is scored against those two needs.

## Summary verdict

1. **ESP32-DevKitC V4 (ESP32-WROOM-32D)** — *primary.* Full BT/BLE radio, ~26
   usable GPIO (well over the 16 needed), and the canonical Espressif reference
   devkit, so it matches every ESP-IDF/board-id default with the fewest
   surprises.
2. **ESP32 DevKit V1 / DOIT (ESP32-WROOM-32)** — *hardware backup.* Same LX6
   chip, same BLE, same GPIO budget; only loses to the DevKitC V4 on board
   provenance/USB-UART consistency. Any old Arduino firmware on it is irrelevant
   — the board is freely re-flashable.
3. **ESP-12E (ESP8266EX)** — **UNSUITABLE.** Two independent blockers: the
   ESP8266 has **no Bluetooth/BLE at all** (WiFi-only), and it exposes too few
   usable GPIO to drive a 16-line matrix natively. Either one alone disqualifies
   it for this project.

## Comparison table

| | **ESP32-DevKitC V4** (WROOM-32D) | **ESP32 DevKit V1 / DOIT** (WROOM-32) | **ESP-12E** (ESP8266EX) |
|---|---|---|---|
| Chip | ESP32-D0WDQ6 (measured) | ESP32-D0WDQ6 (measured) | ESP8266EX (measured) |
| Core(s) / architecture | Dual-core Xtensa LX6 (32-bit) | Dual-core Xtensa LX6 (32-bit) | Single-core Tensilica L106 (32-bit) |
| Clock | up to 240 MHz | up to 240 MHz | up to 160 MHz (default 80 MHz) |
| SRAM | 520 KB on-chip | 520 KB on-chip | ~160 KB total (~36 KB user heap when WiFi connected) |
| Flash (typical) | 4 MB (integrated in module) | 4 MB (integrated in module) | 4 MB (external SPI flash on ESP-12E) |
| WiFi | 802.11 b/g/n | 802.11 b/g/n | 802.11 b/g/n |
| **Bluetooth / BLE** | **Yes — BT Classic + BLE 4.2** | **Yes — BT Classic + BLE 4.2** | **No — none** |
| Total GPIO | 34 GPIO (pins numbered 0-19, 21-23, 25-27, 32-39) | 34 GPIO (same map) | 17 GPIO (most multiplexed) |
| Usable GPIO (after flash / strapping / UART) | ~26 general-purpose; ~22 are full I/O with software pull-ups (excludes input-only 34-39) | same as DevKitC V4 (~26) | ~9-11 practically free (after flash, boot-strapping, and the always-used GPIO0/2/15) |
| **Enough GPIO for 16-line 8x8 matrix natively?** | **Yes** — easily; reference pin map uses 16 safe pins | **Yes** — same pin budget | **No** — cannot supply 8 pull-up-capable inputs + 8 outputs without crippling boot/flash pins |
| ADC channels | 18 (ADC1: 8 ch, ADC2: 10 ch; 12-bit) | 18 (same) | 1 (single 10-bit ADC, ~0-1.0 V) |
| Typical USB-UART chip | **CP2102 (measured on this unit)** | **CP2102 (measured; other V1 batches ship CH340)** | **CP2102 (measured; other ESP-12E boards ship CH340)** |
| macOS driver | none — native, enumerates as `cu.usbserial-*` | none — native, enumerates as `cu.usbserial-*` | none — native, enumerates as `cu.usbserial-*` |
| **Suitable for this project?** | **Yes** — BLE + ample GPIO; best board-id/default match | **Yes** — BLE + ample GPIO; equal capability, fine as backup | **No** — no BLE (hard-requirement fail) **and** too few GPIO |

> GPIO notes that drive the table: on the ESP32, **GPIO6-11 are consumed by the
> integrated SPI flash** and must never be wired. **GPIO34-39 are input-only and
> have no internal pull-up/pull-down**, so they cannot serve as matrix columns
> (columns need internal pull-ups) and cannot be matrix rows (outputs).
> **GPIO0/2/12/15 are strapping pins** and need care. After excluding all of
> those, the firmware's reference pin map still finds 16 safe lines comfortably
> (see `firmware/README.md` §1).

## Verified on hardware (2026-05-28)

All three boards were plugged in (macOS, via a USB hub) and their silicon read
over the bootloader with `esptool.py`. Every one enumerated **natively** as a
CP2102 bridge on `/dev/cu.usbserial-0001` (VID:PID `10C4:EA60`) — no driver
install needed.

| Board | Chip (measured) | Features (efuse) | Flash | MAC | Live? |
|---|---|---|---|---|---|
| DevKitC V4 | ESP32-D0WDQ6 rev v1.0 | WiFi, **BT**, dual-core, 240 MHz | 4 MB | `7c:9e:bd:07:57:ac` | ✅ |
| DevKit V1 | ESP32-D0WDQ6 rev v1.0 | WiFi, **BT**, dual-core, 240 MHz | 4 MB | `a4:cf:12:04:da:38` | ✅ |
| ESP-12E | ESP8266EX | **WiFi only — no BT** | — | `50:02:91:6a:86:b3` | ✅ |

The ESP-12E `Features: WiFi` line (no BT) is the empirical confirmation of the
no-BLE blocker. Auto-reset worked through the hub on both ESP32 boards
(`Hard resetting via RTS pin`), so flashing over the hub should succeed despite
the usual hub caveat.

> The two ESP32 boards expose **different physical headers** (DevKitC V4 = 38
> pins, DevKit V1 = 30 pins) — same silicon, different breakout. Per-board
> header maps and how the 16 matrix GPIOs land on each are in
> [`PINOUT.md`](./PINOUT.md).

## Per-board notes

### ESP32-DevKitC V4 (ESP32-WROOM-32D) — primary

Espressif's own reference development board carrying the WROOM-32D module
(dual-core LX6, 520 KB SRAM, 4 MB integrated flash, WiFi + BT/BLE 4.2). It
exposes the full ESP32 GPIO set, of which ~26 are general purpose; after removing
flash pins (6-11), input-only pins (34-39), and treating strapping pins (0/2/12/
15) with care, there are far more than the 16 lines the matrix needs. USB-UART is
normally a CP2102N from Silicon Labs, which recent macOS tends to enumerate
natively; otherwise the Silicon Labs CP210x VCP driver provides the port. Because
it is the canonical Espressif devkit, ESP-IDF board definitions and the
`esp32dev` PlatformIO board id map to it with the fewest surprises — the reason
it is the recommended primary controller.

### ESP32 DevKit V1 / DOIT (ESP32-WROOM-32) — hardware backup

Functionally equivalent to the DevKitC V4 for this firmware: same ESP32-D0WD-class
dual-core LX6 silicon, same 520 KB SRAM, same 4 MB flash, and the **same BLE
4.2** radio. It has the identical GPIO budget, so it also clears the 16-line
matrix requirement natively. The only practical differences are board provenance
(a DOIT/clone layout rather than the Espressif reference) and a less consistent
USB-UART chip — some batches ship CP2102, others CH340 (the latter needs the WCH
`ch34xser_macos` driver on Apple Silicon). The user previously used this exact
board for an earlier chessboard project, **likely in the Arduino ecosystem, so it
may still carry old firmware — this is irrelevant: the board is fully
re-flashable** and `pio run -t upload` overwrites it. It is the clear hardware
backup to the DevKitC V4.

### ESP-12E (ESP8266EX) — UNSUITABLE

The ESP-12E is an **ESP8266** module (single-core Tensilica L106, WiFi-only),
not an ESP32. It fails this project on **two independent blockers**:

1. **No Bluetooth/BLE.** The ESP8266EX has no Bluetooth radio of any kind — it is
   a WiFi-only chip. BLE is a hard requirement here (firmware is ESP-IDF +
   NimBLE, streaming `board_event` notifications to the mobile app), so this
   alone disqualifies the board.
2. **Too few usable GPIO.** The ESP8266 nominally has 17 GPIO, but most are
   multiplexed and several are spoken for: GPIO6-11 serve the SPI flash, GPIO0/2/
   15 are boot-strapping pins that must hold specific levels at reset, and GPIO1/3
   are the UART console. What is left cannot supply 8 pull-up-capable column
   inputs plus 8 row outputs for the 16-line matrix without colliding with
   boot/flash pins.

Note that a **GPIO expander (e.g. an I2C/SPI port expander) could address the
pin-count blocker, but it would NOT fix the missing BLE** — there is no radio to
add Bluetooth to. Because BLE is non-negotiable for this design, the ESP-12E is
out regardless of any GPIO workaround.

## References

Espressif datasheets:

- [ESP32-WROOM-32D & ESP32-WROOM-32U Datasheet](https://www.espressif.com/sites/default/files/documentation/esp32-wroom-32d_esp32-wroom-32u_datasheet_en.pdf) — DevKitC V4 module (board 1)
- [ESP32-WROOM-32 Datasheet](https://www.espressif.com/sites/default/files/documentation/esp32-wroom-32_datasheet_en.pdf) — DevKit V1 / DOIT module (board 2)
- [ESP8266EX Datasheet](https://www.espressif.com/sites/default/files/documentation/0a-esp8266ex_datasheet_en.pdf) — ESP-12E chip (board 3)
- [ESP8266 Technical Reference](https://www.espressif.com/sites/default/files/documentation/esp8266-technical_reference_en.pdf) — GPIO/strapping detail for the ESP-12E

Related project docs:

- `context/foundation/tech-stack.md` — firmware sub-project stack decisions (ESP-IDF + NimBLE)
- `docs/reference/contract-surfaces.md` §1 — BLE protocol (`board_event` / `mobile_command`) + square indexing the firmware must implement
- `firmware/README.md` — bringup pin map, the 16-line matrix wiring, and GPIO-hazard rationale
