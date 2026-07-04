# Smart Chessboard — ESP32 firmware

BLE game firmware for the physical board: scans an 8×8 reed-switch matrix (~50 Hz,
debounced), reads the two confirmation buttons, and streams square lift/place events,
button presses, and board snapshots to the mobile app over one GATT service —
byte-for-byte per [`contract-surfaces.md` §1](../docs/reference/contract-surfaces.md).

The board is a **dumb sensor**: it reports raw square transitions and button presses
only. All chess logic (move derivation, validation, turn tracking) lives in the
mobile app.

**Stack:** PlatformIO + ESP-IDF + NimBLE, C++. Board id `esp32dev` (classic
ESP32-WROOM-32). Advertises as `SmartChessboard-XXXX` (MAC-derived suffix).
Square indexing: `index = file + 8*rank`, a1=0 … h8=63.

## Prerequisites

- **PlatformIO Core** — on macOS: `brew install platformio` (Homebrew, not
  pipx/installer script — pio does not support Python 3.14). The ESP-IDF SDK and
  xtensa toolchain download automatically on the first `pio run` (several minutes,
  one time).
- **USB-serial driver** — only if the board doesn't appear in `pio device list`
  (CP210x on most DevKitC boards, CH340 on cheaper clones). Always use the
  `/dev/cu.*` node, never `tty.*` (tty can hang flashing).

## Build · flash · test

Run from this directory:

```bash
pio run                       # build
pio run -t upload             # build + flash (auto-detects port)
pio run -t upload -t monitor  # flash, then watch the serial console
pio device monitor            # console only — 115200 baud, Ctrl-] to exit
pio test -e native            # host unit tests (no hardware, no xtensa toolchain)
```

`pio test -e native` runs the pure-logic tests (Unity) on the dev machine: the byte
codec is asserted against the **same golden vectors** as the mobile
`BoardWireCodecTest.kt`, making it the contract-drift guard between firmware and app.
Run it before declaring any `lib/` change green.

If upload fails with `Failed to connect`: hold **BOOT (IO0)**, tap **EN (reset)**,
release BOOT, retry.

## Code layout

- `lib/board_protocol/` — pure C++ byte codec for the §1 wire protocol: encoders for
  `BOARD_SNAPSHOT` / `SQUARE_EVENT` / `BUTTON_EVENT` / `DEVICE_STATUS`, total decoder
  for `SET_MODE` / `REQUEST_SNAPSHOT` / `REQUEST_STATUS` (malformed input never
  throws). Host-tested.
- `lib/debounce/` — per-square agreement-counter debounce (4 stable scans ≈ 80 ms).
  Host-tested.
- `src/main.cpp` — FreeRTOS scan loop: drives the matrix, feeds the debouncer, posts
  per-square events and button-press edges onto an event queue. Sole owner of the
  board state; never calls BLE.
- `src/ble_service.{h,cpp}` — NimBLE peripheral: one GATT service (`board_event`
  notify + `mobile_command` write), advertising/reconnect lifecycle, queue consumer,
  periodic `DEVICE_STATUS` and diagnostic-mode snapshot timers.

The serial console renders a live ASCII board (redraws only on debounced change) — a
local debug aid, orthogonal to the BLE path.

## Hardware & pin maps — read before touching wiring

**`src/pins.h` is the single source of truth for what is actually flashed.** It maps
the reused DevKit V1 prototype harness — bringup/test wiring, not hazard-free (ROW6
sits on strapping pin GPIO12) — and it intentionally differs from the hazard-free
target map documented in [`PINOUT.md`](PINOUT.md) / [`WIRING.md`](WIRING.md). Do
**not** "fix" `pins.h` to match the docs; that would break the verified board. If a
square reads wrong/mirrored/rotated, calibrate `square_index()` in `main.cpp` — do
not rewire.

- [`HARDWARE.md`](HARDWARE.md) — board inventory, on-hardware verification log, and
  the confirmation buttons: the original DGT chess-clock buttons read on ADC1
  (GPIO34 white / GPIO35 black); common ground with the powered-on clock is
  mandatory.
- [`PINOUT.md`](PINOUT.md) — per-board header diagrams with matrix pins marked.
- [`WIRING.md`](WIRING.md) — the 16-wire connection list, per-square wiring, and the
  anti-ghosting diodes (one 1N4148 per square, required).

Config: edit `sdkconfig.defaults` (the committed seed) — never the generated
`sdkconfig*` files (gitignored, regenerated on build).

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Port missing from `pio device list` | USB-serial driver (§ Prerequisites); charge-only cable; USB hub — plug direct |
| `Failed to connect` on upload | BOOT + EN sequence above; use `cu.*` not `tty.*`; close any open monitor |
| Boot-loops after wiring changes | a strapping pin pulled at boot — ROW6 is on GPIO12 in the flashed harness, first suspect |
| Garbled console | wrong baud — must be 115200 |
| Whole row/column reads closed | row pin stuck LOW or column lost its pull-up |
| Phantom squares | ghosting — check the per-square diodes |

## References

- [`AGENTS.md`](AGENTS.md) — deeper architecture notes and agent/contributor rules
- [`../context/foundation/prd-firmware.md`](../context/foundation/prd-firmware.md) —
  firmware PRD
- [`../docs/reference/contract-surfaces.md`](../docs/reference/contract-surfaces.md)
  §1 — the BLE protocol contract
