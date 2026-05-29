# AGENTS.md — firmware (ESP32 reed-matrix)

Module-scoped guidance for the ESP32 reed-matrix firmware. **Monorepo-wide rules live in the root [`../AGENTS.md`](../AGENTS.md)** (imported by `../CLAUDE.md`) — commit conventions, the sub-project map, and the gitignored-files list. This file adds only firmware-specific depth.

## Status: PARKED

Bringup is done and was verified on real hardware (2026-05-28). The firmware is intentionally paused — **don't resume or modify it unless explicitly asked.** Editing docs (like this file) is fine.

## What this is

Diagnostic-only firmware that scans an 8×8 reed-switch matrix and prints live occupancy to the serial console. Its sole purpose is to prove the physical wiring and pin map on hardware **before** any game/BLE logic exists. No anti-ghosting diodes, no BLE — both deferred to the future game firmware.

Stack: **PlatformIO + ESP-IDF**, C++. Target board id `esp32dev` (classic ESP32-WROOM-32).

## Build / flash / monitor

Run from this directory. `pio` is the only entry point — it manages the ESP-IDF + xtensa toolchain itself (downloaded into `.pio/` on first `pio run`, several minutes). You do **not** set `IDF_PATH` manually for the `pio` flow (the `$ENV{IDF_PATH}` in `CMakeLists.txt` is only for a direct `idf.py` build, which this project doesn't use).

```bash
pio run                       # build (first run downloads the toolchain)
pio run -t upload             # build + flash (auto-detects port)
pio run -t upload -t monitor  # flash, then watch the console
pio device monitor            # console only, 115200 baud; Ctrl-] to exit
pio device list               # find the serial port
```

Prerequisite: PlatformIO Core is **not** installed by default on this machine — `brew install platformio` (Homebrew, not pipx — system Python 3.14 is unsupported by pio). See `README.md` §0. Always use the `/dev/cu.*` serial node, never `tty.*` (tty can hang flashing).

## Architecture (`src/main.cpp`)

A single FreeRTOS superloop in `app_main` — no tasks, no interrupts:

1. **Scan** (`scan_matrix`): drive each row pin LOW one at a time, read all 8 column inputs (internal pull-ups → idle HIGH, closed reed = LOW), pack results into a **`uint64_t` occupancy bitmap** keyed by square index.
2. **Debounce**: per-square `agree[64]` counters require `kStableScans` (4) consecutive agreeing scans before a bit is committed to the `stable` bitmap. Any disagreement resets that square's counter, filtering bounce and loose-wire noise.
3. **Render** (`render`): only when `stable` changes does it redraw — ANSI clear + home cursor, so the board view stays in place (no scroll spam). A `Change:` line diffs against the previous state (`+` opened / `-` closed).

Scan cadence ~50 Hz (`kScanIntervalMs`=20), so debounce latency is ~80 ms.

**Square indexing** is the contract surface: `index = file + 8*rank` (a1=0 … h8=63), matching `../docs/reference/contract-surfaces.md` §1.3. Orientation is **calibrated in software**: if a flashed board shows the wrong/mirrored/rotated square, edit `square_index()` in `main.cpp` — do **not** rewire and do not change `pins.h`.

## Critical gotcha: two different pin maps

`src/pins.h` is the **single source of truth for what is actually flashed** — and it intentionally **differs** from the pin table in `README.md`/`PINOUT.md`/`WIRING.md`.

- `pins.h` = the **reused DevKit V1 prototype harness** (two consecutive header blocks). It is the bringup/test wiring, not hazard-free. Notable: ROW6 is on **GPIO12** (flash-voltage strapping — first suspect if the board ever boot-loops); COL6 was moved off GPIO2 (onboard LED) to GPIO21.
- `README.md`/`PINOUT.md`/`WIRING.md` = the **hazard-free target** map for a clean build.

Do **not** "fix" `pins.h` to match the README — that would break the verified prototype. If you genuinely need to rewire, change `pins.h` and re-verify on hardware.

## Config & conventions

- `sdkconfig`, `sdkconfig.old`, `sdkconfig.esp32dev` are **gitignored** (generated). The committed seed is `sdkconfig.defaults` — edit that for reproducible config, not the generated files.
- `esp32dev` is a **placeholder** board id (Open Question FW-1); swap in `platformio.ini` when the final ESP32 variant is chosen (`pio boards espressif32` lists options).

## Docs map

- `README.md` — full bringup/build/flash/test/troubleshooting walkthrough + prerequisites (§0).
- `HARDWARE.md` — board inventory & comparison; on-hardware verification log; per-board notes.
- `PINOUT.md` — per-board header pinout diagrams (DevKitC V4 + DevKit V1) with matrix pins marked.
- `WIRING.md` — the 16-wire connection list and per-square wiring.
- Canonical context (one level up): `../context/foundation/prd-firmware.md`, `../context/foundation/tech-stack.md`, `../docs/reference/contract-surfaces.md` §1, `../context/foundation/lessons.md` (recurring rules — consult before changes).
