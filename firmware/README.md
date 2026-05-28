# Smart Chessboard — Firmware (reed-switch 8×8 matrix bringup)

Diagnostic firmware for the **classic ESP32 (ESP32-WROOM-32)**. It scans an 8×8
reed-switch matrix and prints the live occupancy to the serial console as an
ASCII board + a list of closed squares. Goal: prove the wiring and pin map on
real hardware **before** any game/BLE logic exists.

Stack: **PlatformIO + ESP-IDF** (per `../context/foundation/tech-stack.md`).
Square index convention: `index = file + 8*rank`, a1=0 … h8=63 (matches
`../docs/reference/contract-surfaces.md` §1.3).

---

## 0. Prerequisites — what's MISSING on this machine (do this first)

Checked 2026-05-28. **Build/flash is blocked until step 1 is done.**

| Tool | State | Needed for |
|---|---|---|
| Homebrew | ✅ present (`/opt/homebrew`) | installing PlatformIO |
| Python | ⚠️ 3.14.3 (python.org) | see caveat below |
| **PlatformIO Core (`pio`)** | ❌ **NOT installed** | build / flash / monitor |
| USB-serial driver (CP210x / CH340) | ❔ unknown until board plugged in | flashing over USB |
| VS Code (optional) | ❌ `code` not on PATH | nicer PlatformIO IDE (optional) |

### Step 1 — install PlatformIO via Homebrew (required)

> **Why Homebrew, not pipx/installer-script:** your system Python is **3.14**,
> which PlatformIO Core 6.x does **not** officially support yet
> ([core#5372](https://github.com/platformio/platformio-core/issues/5372)).
> The `pipx`/`get-platformio.py` routes would break on 3.14. Homebrew's
> `platformio` formula vendors its own compatible (patched) Python, sidestepping
> the problem. So on this machine, use Homebrew.

```bash
brew install platformio
pio --version          # verify; pio lands in /opt/homebrew/bin/pio
```

The ESP-IDF SDK + xtensa toolchain are **not** installed separately — the first
`pio run` downloads them automatically (several minutes, one time).

### Step 2 — USB-serial driver (only if the board doesn't appear)

When you have the ESP32, plug it in and run `pio device list`. If a new
`/dev/cu.*` port appears, no driver is needed. If nothing appears, install based
on the board's USB-UART chip:

- **CP2102 (Silicon Labs)** — most WROOM-32 DevKitC boards. Recent macOS often
  enumerates it natively as `/dev/cu.usbserial-XXXX`. If not, install the
  [Silicon Labs CP210x VCP driver](https://www.silabs.com/software-and-tools/usb-to-uart-bridge-vcp-drivers)
  → appears as `/dev/cu.SLAB_USBtoUART`.
- **CH340/CH9102 (WCH)** — common on cheaper clones. macOS' built-in driver is
  flaky on Apple Silicon; install [WCH `ch34xser_macos`](https://github.com/WCHSoftGroup/ch34xser_macos)
  → appears as `/dev/cu.wchusbserialXXXX`.

Always use the **`cu.*`** node, never `tty.*` (the `tty.` node can hang flashing).

---

## 1. Hardware

### Pin map (classic ESP32-WROOM-32)

Edit `src/pins.h` to match your wiring — the table there is the single source of
truth. Rows are **outputs** (driven LOW one at a time); columns are **inputs**
with the internal pull-up (idle HIGH, closed reed = LOW). For per-board header
diagrams (DevKitC V4 + DevKit V1) with these pins marked in place, see
[`PINOUT.md`](./PINOUT.md).

| ROW (output) → rank | GPIO | | COL (input, pull-up) → file | GPIO |
|---|---|---|---|---|
| ROW0 → rank 1 | 13 | | COL0 → file a | 16 |
| ROW1 → rank 2 | 14 | | COL1 → file b | 17 |
| ROW2 → rank 3 | 25 | | COL2 → file c | 18 |
| ROW3 → rank 4 | 26 | | COL3 → file d | 19 |
| ROW4 → rank 5 | 27 | | COL4 → file e | 21 |
| ROW5 → rank 6 | 32 | | COL5 → file f | 22 |
| ROW6 → rank 7 | 33 | | COL6 → file g | 23 |
| ROW7 → rank 8 | **5** | | COL7 → file h | **4** |

Pins were chosen to avoid every hazard on the WROOM-32: flash pins GPIO6–11,
UART0 GPIO1/3 (the console), input-only GPIO34–39 (no pull-up), and the
dangerous strapping pins (GPIO12 = flash-voltage, GPIO0/2 = boot-mode). GPIO5 is
the only "mild" strapping pin used and it sits on the safe output side.

> **If your board doesn't break out GPIO4:** move COL7 to `GPIO_NUM_15` in
> `src/pins.h` (it has an internal pull-up and boots HIGH, so it's a tolerable
> column). Never put the matrix on GPIO12/0/2.

### Wiring per cell

```
   row line (output) ──┬── reed switch ──┬── column line (input, pull-up)
                       (one switch per square; 64 total)
```

- **Pull-ups:** internal (enabled in firmware) — no external resistors needed
  for bringup.
- **Anti-ghosting diodes:** **omitted for bringup, OK with ≤2 magnets.** Ghosting
  needs ≥3 closed switches in an L-pattern. For real gameplay you must add one
  diode (1N4148) in series per square — deferred to game firmware.

---

## 2. Build

```bash
cd claude/firmware
pio run                 # first run downloads ESP-IDF + toolchain (minutes)
```

A clean build with no board attached is the fastest way to confirm the project
is wired correctly. (Until `pio` is installed this step cannot run — see §0.)

## 3. Flash

```bash
pio run -t upload                 # build + flash (auto-detects port)
# or flash then immediately watch the console:
pio run -t upload -t monitor
```

**Boot behavior:** genuine boards auto-reset into the bootloader, so this "just
works". If you see `Failed to connect ... No serial data received`:
hold **BOOT (IO0)**, tap **EN (reset)**, release BOOT — then re-run upload.

## 4. Boot & test (the console)

```bash
pio device monitor           # 115200 baud (set in platformio.ini); Ctrl-] to exit
```

The console clears and **redraws in place only when the (debounced) board state
changes** — no scrolling. A `Change:` line lists what just opened (`-`) or
closed (`+`). Example after dropping a magnet on e4:

```
Smart Chessboard - reed matrix  (in-place, redraws on change)

8 . . . . . . . .
7 . . . . . . . .
6 . . . . . . . .
5 . . . . . . . .
4 . . . . X . . .
3 . . . . . . . .
2 . . . . . . . .
1 . . . . . . . .
  a b c d e f g h

Closed: e4
Change: +e4
```

Scanning runs at ~50 Hz with a 4-scan debounce (~80 ms), so brief bounce and
loose-wire noise are filtered out and the view stays still until something real
changes.

**How to verify it works:**

1. Empty board → grid is all `.` and `Closed: (none)`.
2. Place a magnet on one square → exactly that square flips to `X` and appears
   in the `Closed:` line by name (e.g. `e4`).
3. Walk a magnet across a row and a column → confirms each row pin and column
   pin individually. If the *wrong* square lights up, your physical row/col
   wiring doesn't match `src/pins.h` — fix the pin arrays (or the `square_index`
   mapping in `main.cpp` if the board is rotated/mirrored).
4. A whole stuck row/column reading `X` usually means that row pin is stuck LOW
   or that column lost its pull-up (check the pin against §1).

---

## 5. Troubleshooting

| Symptom | Likely cause |
|---|---|
| Port not in `pio device list` | missing USB-serial driver (§0 step 2); charge-only/cheap cable; USB hub — plug direct |
| `Failed to connect` on upload | hold BOOT + tap EN; use `cu.*` not `tty.*`; close any open monitor; add `upload_speed = 115200` in `platformio.ini` |
| Board won't boot at all after wiring | a matrix wire is pulling GPIO12 HIGH (flash-voltage trap) or GPIO0/2 at boot — keep the matrix off those pins |
| Garbled console | wrong baud — must be 115200 |
| Phantom squares with many magnets | ghosting — expected without diodes; test with ≤2 magnets, or add the 64 diodes |

---

## 6. Deferred (not in this bringup)

- 64 anti-ghosting diodes (required for real gameplay).
- Per-cell debounce (N consecutive stable scans) — the 250 ms refresh is enough
  for a diagnostic.
- BLE GATT (`board_event` / `mobile_command`) per `contract-surfaces.md` §1 —
  this firmware is matrix-only.
- Final ESP32 variant (Open Question FW-1) — `esp32dev` is a placeholder board
  id in `platformio.ini`; swap when the real controller is known.

## References

- `../context/foundation/tech-stack.md` — firmware sub-project decisions
- `../context/foundation/prd-firmware.md` — firmware PRD
- `../docs/reference/contract-surfaces.md` §1 — BLE protocol + square indexing
- `../docs/bootstrap-verification.md` — bootstrap log (firmware section)
