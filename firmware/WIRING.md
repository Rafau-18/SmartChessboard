---
project: "Smart Chessboard"
document: matrix-wiring
version: 1
status: draft
created: 2026-05-28
updated: 2026-05-28
---

# Matrix Wiring — where to connect the 8×8 reed-switch matrix

Connection sheet for the 16 matrix wires (8 rows + 8 columns) on the two ESP32
boards. **Use the silkscreen labels printed on YOUR board** (column "Board label")
to find each pin — the GPIO number is often *not* what's printed (e.g. on the
DevKit V1, GPIO16/GPIO17 are silkscreened `RX2`/`TX2`).

Full header reference: `PINOUT.md`. Wiring rules / diodes: `README.md` §1.

> **⚠ CURRENT REALITY — `src/pins.h` is authoritative; this sheet is reference only.**
> The real bring-up wiring **inverts the scan**: the firmware drives the **columns**
> as outputs (LOW) and reads the **rows** as pull-up inputs. Anti-ghosting diodes
> (one 1N4148 per square, **cathode toward the column**) are **installed and required** —
> not optional. The "Rows = MCU outputs / Columns = MCU inputs" framing in the tables
> below is the older row-drive layout, kept for header-location reference only; the
> current authoritative GPIO map is in `src/pins.h` (cols a..h = `{15,4,16,17,5,18,19,21}`,
> rows rank1..8 = `{32,33,25,26,27,14,12,13}`).

---

## Wire list — connect these 16

### Rows — 8 wires (MCU outputs, one per rank)

| Wire | GPIO | DevKit V1 label | V1 side | DevKitC V4 label | V4 side |
|---|---|---|---|---|---|
| ROW0 (rank 1) | 13 | `D13` | LEFT | `IO13` | LEFT |
| ROW1 (rank 2) | 14 | `D14` | LEFT | `IO14` | LEFT |
| ROW2 (rank 3) | 25 | `D25` | LEFT | `IO25` | LEFT |
| ROW3 (rank 4) | 26 | `D26` | LEFT | `IO26` | LEFT |
| ROW4 (rank 5) | 27 | `D27` | LEFT | `IO27` | LEFT |
| ROW5 (rank 6) | 32 | `D32` | LEFT | `IO32` | LEFT |
| ROW6 (rank 7) | 33 | `D33` | LEFT | `IO33` | LEFT |
| ROW7 (rank 8) | 5  | `D5`  | RIGHT | `IO5` | RIGHT |

### Columns — 8 wires (MCU inputs, one per file)

| Wire | GPIO | DevKit V1 label | V1 side | DevKitC V4 label | V4 side |
|---|---|---|---|---|---|
| COL0 (file a) | 16 | `RX2` ⚠ | RIGHT | `IO16` | RIGHT |
| COL1 (file b) | 17 | `TX2` ⚠ | RIGHT | `IO17` | RIGHT |
| COL2 (file c) | 18 | `D18` | RIGHT | `IO18` | RIGHT |
| COL3 (file d) | 19 | `D19` | RIGHT | `IO19` | RIGHT |
| COL4 (file e) | 21 | `D21` | RIGHT | `IO21` | RIGHT |
| COL5 (file f) | 22 | `D22` | RIGHT | `IO22` | RIGHT |
| COL6 (file g) | 23 | `D23` | RIGHT | `IO23` | RIGHT |
| COL7 (file h) | 4  | `D4`  | RIGHT | `IO4` | RIGHT |

⚠ On the DevKit V1, COL0/COL1 are the pins silkscreened **`RX2`/`TX2`**
(= GPIO16/GPIO17), *not* `RX0`/`TX0` (those are GPIO3/GPIO1 — the USB serial
console, do **not** wire the matrix there).

---

## DevKit V1 / DOIT (30-pin) — FIRST FLASH TARGET

`<--` marks a matrix wire. USB at the bottom.

```
                  ESP32 DevKit V1 / DOIT
                   ___________________
           EN/RST --|                   |-- D23   <-- COL6 (g)
       (in)  VP   --|                   |-- D22   <-- COL5 (f)
       (in)  VN   --|                   |-- TX0      (console - skip)
       (in)  D34  --|                   |-- RX0      (console - skip)
       (in)  D35  --|      ESP32        |-- D21   <-- COL4 (e)
   ROW5 --> D32  --|     WROOM-32      |-- D19   <-- COL3 (d)
   ROW6 --> D33  --|                   |-- D18   <-- COL2 (c)
   ROW2 --> D25  --|                   |-- D5    <-- ROW7 (rank8) *
   ROW3 --> D26  --|                   |-- TX2   <-- COL1 (b)  [=GPIO17]
   ROW4 --> D27  --|                   |-- RX2   <-- COL0 (a)  [=GPIO16]
   ROW1 --> D14  --|                   |-- D4    <-- COL7 (h) *
            D12 * --|                   |-- D2       (* / LED - skip)
   ROW0 --> D13  --|                   |-- D15      (* - skip)
            GND  --|                   |-- 3V3
            VIN  --|___________________|-- GND
                          [ USB ]
```

7 rows on the LEFT; ROW7 + all 8 columns on the RIGHT.

---

## DevKitC V4 (38-pin) — same wiring, different header

```
                    ESP32-DevKitC V4
                   ___________________
            3V3  --|                   |-- GND
         EN/RST  --|                   |-- IO23  <-- COL6 (g)
       (in) IO36 --|                   |-- IO22  <-- COL5 (f)
       (in) IO39 --|                   |-- TX       (console - skip)
       (in) IO34 --|                   |-- RX       (console - skip)
       (in) IO35 --|      ESP32        |-- IO21  <-- COL4 (e)
   ROW5 --> IO32 --|     WROOM-32D     |-- GND
   ROW6 --> IO33 --|                   |-- IO19  <-- COL3 (d)
   ROW2 --> IO25 --|                   |-- IO18  <-- COL2 (c)
   ROW3 --> IO26 --|                   |-- IO5   <-- ROW7 (rank8) *
   ROW4 --> IO27 --|                   |-- IO17  <-- COL1 (b)
   ROW1 --> IO14 --|                   |-- IO16  <-- COL0 (a)
            IO12*--|                   |-- IO4   <-- COL7 (h) *
            GND  --|                   |-- IO0      (* BOOT - skip)
   ROW0 --> IO13 --|                   |-- IO2      (* LED - skip)
       (fl) IO9  --|                   |-- IO15     (* - skip)
       (fl) IO10 --|                   |-- IO8   (fl)
       (fl) IO11 --|                   |-- IO7   (fl)
             5V  --|___________________|-- IO6   (fl)
                          [ USB ]
```

---

## Per-square connection

Each square = one reed switch bridging its **row wire** and its **column wire**:

```
   ROW line (e.g. D13 = rank 1) ──┬── reed switch ──┬── COL line (e.g. RX2 = file a)
                                  square a1
```

- A square at (file, rank) connects `COL[file]` ↔ `ROW[rank]`. Example: **e4** =
  COL4 (e) ↔ ROW3 (rank 4) = `D21` ↔ `D26` on the V1.
- No external resistors — the firmware enables internal pull-ups on the **rows** (inputs).
- **Diodes installed and required:** one 1N4148 per square, **cathode toward the
  column** (the scan drives columns LOW and reads rows). No longer "optional for
  ≥3 magnets" — it is part of the working scan.

## Sanity check after flashing

1. Bare board (nothing wired) → serial shows an all-`.` grid, `Closed: (none)`.
2. Touch a jumper between one ROW pin and one COL pin → the corresponding square
   flips to `X` (e.g. `D26`↔`D21` → `e4`). Confirms that row/col pair before you
   solder reeds.
3. If the wrong square lights up, your physical wire order ≠ `pins.h` — either
   rewire to this sheet, or edit the arrays in `src/pins.h`.

## References

- `firmware/PINOUT.md` — full per-board header maps
- `firmware/src/pins.h` — the authoritative pin arrays
- `firmware/README.md` §1, §6 — wiring rules, diodes, debounce
