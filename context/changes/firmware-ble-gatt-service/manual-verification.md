# Manual Verification — firmware-ble-gatt-service (F-03)

Deferred manual checks for this slice. Code/doc-read rows are recorded here as the
phases land and confirmed in one pass at end-of-slice; on-hardware rows wait for the
board. Tick a row only after a human actually performs it.

## Phase 1 — Protocol Core + Host Test Harness

### 1.4 Spot-check golden frames in `test_protocol.cpp` match `BoardWireCodecTest.kt`

Type: **code-read** (no hardware). Both files hand-derive the same §1.3/§1.4 byte
vectors from `docs/reference/contract-surfaces.md`; this check confirms the firmware
half asserts the exact same literals as the mobile oracle. Quick map (firmware test
→ Kotlin oracle), all confirmed identical during implementation:

| Group | Vectors (both files) | Kotlin source (`BoardWireCodecTest.kt`) |
| --- | --- | --- |
| SQUARE_EVENT | `02 00`, `02 40`, `02 3F`, `02 7F`, `02 0C`, `02 5C` | `encodesSquareEventGoldenFrames` / `decodesSquareEventGoldenFrames` |
| BUTTON_EVENT | `03 00`, `03 01` | `encodes/decodesButtonEventGoldenFrames` |
| BOARD_SNAPSHOT | `01`+`00×8`, `01`+`FF×8`, `01 FF FF 00 00 00 00 FF FF`, `01 00 01 00…` (a2=sq8), `01 80 00…` (h1=sq7) | `encodes/decodesSnapshotGoldenFrames` |
| DEVICE_STATUS | `04 64 01 02 03 00 00 00 00`, `04 32 02 00 01 01 02 03 04` (uptime 67 305 985), `04 00 00 00 00 FF FF FF FF` (uptime 4 294 967 295) | `encodes/decodesDeviceStatusGoldenFrames` |
| Commands | `81 00`, `81 01`, `82`, `83` | `encodes/decodesCommandGoldenFrames` |
| Malformed events | empty, `05 00`, `01 FF`, oversized snapshot (10 B), `02`, `02 85`, `02 C0`, `03 02`, `04 64` | `reportsMalformedEvents` |
| Malformed commands | empty, `84`, `90`, `81 02`, `81`, `82 00`, `83 00` | `reportsMalformedCommands` |

Status: **CONFIRMED by /10x-impl-review 2026-06-19** — the listed vectors were verified byte-for-byte against the Kotlin oracle (`BoardWireCodecTest.kt`), and `pio test -e native` asserts them live (15/15). No hardware needed for this row.

> On-hardware manual checks for Phases 2–3 (advertising, bonding, on-subscribe burst,
> live square/button events, diagnostic stream, reconnect snapshot, malformed-write
> ignore) will be appended here when those phases land, and run against the
> partially-working board + temporary buttons at end-of-slice.

## Phase 2 — NimBLE Peripheral (advertising / bonding / lifecycle)

Type: **on-hardware** (needs the ESP32 flashed + a BLE scanner such as nRF
Connect). Deferred to end-of-slice per the project manual-gate convention — the
board may not be to hand at phase close. Automated gate already green: device
build links with NimBLE (`pio run -e esp32dev`), host tests pass, and the three
GATT UUIDs are recorded in `contract-surfaces.md` §1.2.

Flash with `pio run -t upload` from `firmware/`, then:

| # | Check | Expected |
| --- | --- | --- |
| 2.4 | Scan for the board in nRF Connect | Advertises as `SmartChessboard-XXXX` (XXXX = last 4 hex of the BT MAC); service UUID `787e0001-…` in the advertisement, name in the scan response |
| 2.5 | Connect, then reconnect | First connect bonds via "Just Works" (no PIN); a later reconnect does **not** re-pair (bond persisted in NVS) |
| 2.6 | Enable notifications on `board_event` (`787e0002-…`) | Receive `BOARD_SNAPSHOT` (9 B, tag `0x01`) **then** `DEVICE_STATUS` (9 B: `04 64 01 00 00` + uptime u32 LE) in that order |
| 2.7 | Disconnect the central | Board resumes advertising — reconnectable without a power cycle (FR-FW-012) |

Status: **PENDING on-hardware** (recorded 2026-06-19). Note: the on-subscribe
snapshot is built by the scan task from its own `stable` bitmap; with the
reed-matrix only partially working, expect the snapshot to reflect whatever the
working squares sense (a near-empty board is fine for this phase — 2.6 checks the
burst shape + the `DEVICE_STATUS` bytes, not board contents).

## Phase 3 — Game Behavior (events / buttons / commands / diagnostic)

Type: **on-hardware** (needs the ESP32 flashed + a BLE scanner such as nRF
Connect + two temporary buttons jumpered to GPIO22/GPIO23 → GND). Deferred to
end-of-slice per the project manual-gate convention — the board (and the
temporary buttons) may not be to hand at phase close. Automated gate already
green: device build links (`pio run -e esp32dev`), host tests pass 15/15
including `test_derive_square_events` (`pio test -e native`).

Flash with `pio run -t upload` from `firmware/`, connect + subscribe to
`board_event` (`787e0002-…`), then:

| # | Check | Expected |
| --- | --- | --- |
| 3.4 | Move a magnet on a working square | One `SQUARE_EVENT` lift (`02`, event bits `00`) then place (`02`, event bits `01`) with the correct square index in the low 6 bits — never coalesced |
| 3.5 | Press the temporary white / black button (GPIO22 / GPIO23 → GND, active-LOW) | `BUTTON_EVENT` `03 00` (white) / `03 01` (black), one per debounced press edge |
| 3.6 | Write `SET_MODE(diagnostic)` = `81 01`, then `SET_MODE(game)` = `81 00` to `mobile_command` (`787e0003-…`) | `81 01` starts a ~10 Hz `BOARD_SNAPSHOT` stream (ADDED to, not replacing, square events); `81 00` stops it |
| 3.7 | Write `REQUEST_SNAPSHOT` = `82`, then `REQUEST_STATUS` = `83` | Each yields one immediate frame — `BOARD_SNAPSHOT` (9 B) / `DEVICE_STATUS` (9 B) |
| 3.8 | Stay subscribed ~30 s | A `DEVICE_STATUS` (`04 64 01 00 00` + uptime u32 LE) arrives roughly every ~30 s |
| 3.9 | Disconnect → change a working square offline → reconnect + re-subscribe | The reconnect `BOARD_SNAPSHOT` reflects the offline change (mode also reset to GAME) |
| 3.10 | Write a malformed/reserved command (e.g. `84`, `90`, `81 02`, `82 00`) | Ignored — no crash, no reset, no ATT error; the link stays up and live events keep flowing |

Status: **PENDING on-hardware** (recorded 2026-06-19). Notes: square/button
events are enqueued only while a central is subscribed ("dead link delivers
nothing", §1.7); with the reed-matrix only partially working, run 3.4/3.9
against a square that actually senses. Backpressure policy: SQUARE/BUTTON events
are never silently dropped on a live link (they block briefly then log if ever
dropped); diagnostic snapshots may drop under pressure (latest wins).

## Phase 4 — Docs + Contract Consolidation

Type: **doc-read** (no hardware). The automated gate is already green: the docs
were edited and `pio run -e esp32dev && pio test -e native` was re-run with no
regression. This row asks a human to read the updated firmware module guide once.

| # | Check | Expected |
| --- | --- | --- |
| 4.4 | Read the updated `firmware/AGENTS.md` end to end | Reads coherently and matches what F-03 actually built — BLE/NimBLE peripheral, the `board_event`/`mobile_command` GATT service, the two GPIO22/23 buttons, the `lib/` pure-logic split, and the `pio test -e native` host-test flow. The "two pin maps" gotcha and the matrix-`pins.h` warning are intact; the "PARKED" framing is gone (firmware software greenlit; only the hardware reed-matrix repair stays parked). |

Status: **CONFIRMED by /10x-impl-review 2026-06-19** — `firmware/AGENTS.md` matches what F-03 built (PARKED framing gone; BLE/NimBLE peripheral, the `board_event`/`mobile_command` service, GPIO22/23 buttons, the `lib/` split, and the `pio test -e native` flow all documented; the "two pin maps" gotcha + matrix-`pins.h` warning intact). The §1.2 UUID
write-back (Phase 2), the `prd-firmware.md` OQ-2/4/5 resolutions, and the
`prd.md` §1.2 mirror are verifiable by reading the files — no hardware needed.
