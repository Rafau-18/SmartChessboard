# Firmware BLE GATT Service (F-03) Implementation Plan

## Overview

Build the full ESP32 **game firmware** that implements the §1 BLE board contract end-to-end, on top of the existing, hardware-verified reed-matrix scan/debounce. The firmware becomes a NimBLE peripheral exposing one GATT service (`board_event` notify + `mobile_command` write), encodes `BOARD_SNAPSHOT` / `SQUARE_EVENT` / `BUTTON_EVENT` / `DEVICE_STATUS` byte-for-byte to `contract-surfaces.md` §1, handles `SET_MODE` / `REQUEST_SNAPSHOT` / `REQUEST_STATUS`, adds two physical confirmation buttons, and survives disconnect/reconnect cycles.

The work is overwhelmingly **additive**: the ~50 Hz scan, the 4-scan (~80 ms) debounce, and the `index = file + 8*rank` square convention already exist and are hardware-verified (2026-05-28). F-03 adds BLE, the byte codec, buttons, diagnostic mode, and the connection lifecycle.

The one real risk — contract drift between the mobile emulator and real firmware — is attacked directly: the pure logic is extracted into `firmware/lib/`, compiled into a host test build, and asserted against **the same golden byte vectors** the Kotlin `BoardWireCodecTest.kt` uses, making the firmware a byte-for-byte twin of the emulator's stream.

## Current State Analysis

**Implemented today** (diagnostic-only, reuse as-is):

- Matrix scan → `uint64_t` occupancy bitmap, active-row multiplexing, ~50 Hz (`scan_matrix()`, [main.cpp:65-76](firmware/src/main.cpp:65); cadence `kScanIntervalMs=20`, [main.cpp:26](firmware/src/main.cpp:26)).
- Debounce: per-square agreement counters, `kStableScans=4` (~80 ms) committed to a `stable` bitmap ([main.cpp:114-145](firmware/src/main.cpp:114)).
- Square index: `index = col + 8*row = file + 8*rank`, a1=0…h8=63, software-calibratable ([main.cpp:31-33](firmware/src/main.cpp:31)).
- Serial render (debug only, orthogonal to the contract) ([main.cpp:79-112](firmware/src/main.cpp:79)).
- Pin map ([pins.h:37-58](firmware/src/pins.h:37)): rows `{32,33,25,26,27,14,12,13}`, cols `{19,18,5,17,16,4,21,15}` — 16 pins. `pins.h` is the single source of truth for what is flashed; do **not** "fix" it to match the README.
- Build: `pio run` / `pio run -t upload` (PlatformIO + ESP-IDF, [platformio.ini:7-10](firmware/platformio.ini:7)). `sdkconfig.defaults` is the committed seed and is **BLE-free** today.

**Missing for F-03 (the gap)** — none of this exists yet:

- BLE peripheral init + advertising; one GATT service with `board_event` (notify) + `mobile_command` (write).
- Byte encoders for the four board→mobile messages; a decoder for the three mobile→board commands.
- Write handler dispatching `SET_MODE` / `REQUEST_SNAPSHOT` / `REQUEST_STATUS`.
- Two physical confirmation buttons (FR-FW-007) — **no button GPIO is wired in `pins.h` at all**.
- Diagnostic mode (~10 Hz snapshot push) and periodic `DEVICE_STATUS` (~30 s).
- Connection lifecycle: bond on first connect, single central, re-advertise after disconnect.
- No `firmware/lib/`, no `test/`, no `[env:native]` host-test environment.

### Key Discoveries:

- **The wire format is already implemented and tested in Kotlin.** [BoardWireCodec.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/protocol/BoardWireCodec.kt) is the byte-for-byte reference for all 7 messages; its header comment names the firmware as the future consumer. The golden-frame tests [BoardWireCodecTest.kt](SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/data/board/protocol/BoardWireCodecTest.kt) are the cross-language oracle the firmware host tests reuse.
- **The board is a "dumb" sensor.** It reports raw square lift/place transitions and button presses only. The mobile `SequenceInterpreter` reconstructs moves. The firmware must **not** add chess logic (lessons.md: "Engine move-geometry mirrored outside `domain/chess` must be SYNC-commented" — a firmware third interpretation would silently diverge).
- **Snapshot bit order is load-bearing** (`contract-surfaces.md` §1.3, clarified 2026-06-16): byte `i` bit `j` (LSB-first) = square `i*8 + j`. Start position = `01 FF FF 00 00 00 00 FF FF`. The existing `uint64_t occ` already matches.
- **Behavioral spec** ([EmulatedBoard.kt:84-93](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoard.kt:84)): on (re)connect emit `BOARD_SNAPSHOT` then `DEVICE_STATUS` and reset mode to GAME; per-transition `SQUARE_EVENT` streaming is mandatory (never coalesce); diagnostic mode adds ~10 Hz snapshots; disconnect stops emission but keeps sensing.
- **Decoding is total** in the codec — empty/unknown-tag/wrong-length/reserved-bit/out-of-range frames map to a "Malformed" result, never a throw. The firmware must likewise **ignore** malformed/unknown `mobile_command` writes rather than fault.
- **F-03 software is greenlit** (roadmap 2026-06-19); only the hardware reed-matrix repair stays parked and gates S-09. `firmware/AGENTS.md` still carries a stale "PARKED" banner that predates the greenlight.

## Desired End State

The ESP32, flashed with this firmware, advertises as `SmartChessboard-XXXX`, accepts a single bonded BLE central, and speaks the §1 protocol byte-for-byte: it pushes a snapshot + status burst on subscribe, streams a `SQUARE_EVENT` for every debounced reed transition, emits `BUTTON_EVENT` on the two physical buttons, answers `SET_MODE` / `REQUEST_SNAPSHOT` / `REQUEST_STATUS`, pushes periodic `DEVICE_STATUS`, and re-advertises after a disconnect. The three GATT UUIDs are recorded in `contract-surfaces.md` §1.2.

**Verification:** `pio test -e native` proves the encoders/decoder/debounce match the Kotlin golden vectors; `pio run -e esp32dev` builds the full BLE device image; and a best-effort on-hardware pass (against the partially-working board + temporary buttons) confirms advertising, connect/bond, the on-subscribe burst, live square/button events, diagnostic mode, and reconnect. Full mobile-app end-to-end on a fully-repaired board is **S-09**, not this change.

## What We're NOT Doing

- **No reed-scan rewrite.** The hardware-verified scan loop and matrix `pins.h` mapping are reused untouched; button pins are *additive*.
- **No chess logic on the firmware.** No promotion/castling/en-passant awareness, no move validation, no turn tracking — the board is dumb; the mobile re-derives geometry. No third copy of move geometry (lessons.md SYNC rule).
- **No persistence across power cycles** (FR-FW-013) — no flash writes of game state; state is re-sensed on boot.
- **No battery management** — USB-only MVP; `battery_pct` is a fixed constant, no sleep modes, no low-battery UX.
- **No OTA, no LEDs, no multi-board, no board→mobile authentication** beyond trust-on-first-pair (contract §1.8).
- **No MTU negotiation** — the largest frame is 9 bytes, well within the default 23-byte ATT MTU.
- **No NimBLE host-mock / loopback harness** — the off-hardware test boundary is the pure `lib/` logic; the radio path is verified on hardware.
- **No upfront golden-vector reconciliation phase** — we trust the Kotlin vectors and reconcile only if a discrepancy surfaces during firmware work (see Open Risks).
- **No mobile-side BLE adapter** (that is S-09) and **no reed-matrix hardware repair** (parked).

## Implementation Approach

Four phases, each independently flashable/testable, ordered so the fully-automatable work lands first and the radio path is layered on a tested core:

1. **Protocol core + host test harness** — extract the pure logic into `firmware/lib/` and stand up a `native` Unity test build that asserts the Kotlin golden vectors. Fully automated; closes the contract-drift risk before any radio code exists.
2. **NimBLE peripheral** — turn the device into an advertising, bonding, single-central BLE peripheral with the one GATT service and the connection lifecycle (on-subscribe snapshot+status burst, re-advertise on disconnect). Mint and record the UUIDs.
3. **Game behavior** — wire the scan/debounce loop as a producer onto a FreeRTOS queue drained by the BLE consumer; stream `SQUARE_EVENT`s, add the two buttons, handle the three write commands, and drive diagnostic (~10 Hz) and periodic-status (~30 s) timers.
4. **Docs + contract consolidation** — unpark `firmware/AGENTS.md`, resolve the `prd-firmware.md` open questions this change settles, and note the new buttons in the hardware docs.

Concurrency model (research D7): the scan/debounce loop and the button reads run as a **producer** that posts a small `{tag, payload}` event struct to a FreeRTOS queue whenever `stable` changes or a button edge fires — it **never** calls BLE APIs. A **consumer** owned by the BLE host context drains the queue and calls the notify API, gated on the `board_event` CCCD-subscribe flag. Periodic status (~30 s auto-reload `xTimer`) and the diagnostic ~100 ms snapshot timer (started on `SET_MODE→diagnostic`, stopped on `→game`) also post onto the same queue. The ≤100 ms latency NFR has comfortable margin (~80 ms debounce + sub-ms queue→notify hop).

## Critical Implementation Details

- **Connect burst fires on CCCD *subscribe*, not raw connect.** A notify with no subscriber is dropped, so the contract's "on connect emit `BOARD_SNAPSHOT` then `DEVICE_STATUS`" maps to "on the central enabling notifications on `board_event` (CCCD write)". Reset `mode = GAME` and stop the diagnostic timer on the GAP `CONNECT` event; emit the burst on the `SUBSCRIBE` event. The emulator models this as "on connect" only because it has no CCCD layer.
- **Advertising 31-byte budget.** A 128-bit service UUID (16 B) plus a ~16-char name overflows the 31-byte legacy advertisement. Put the **service UUID in the advertisement** and the **name in the scan response**. Re-arm advertising in the GAP `DISCONNECT` event (FR-FW-012).
- **Per-transition `SQUARE_EVENT` is mandatory — never coalesce.** Compute the change set from the debounced `stable` bitmap diff each scan: `placed = stable & ~prevStable`, `lifted = prevStable & ~stable`; emit one `SQUARE_EVENT` per changed square (the lift on a capture destination is the discriminator a bare snapshot diff cannot provide). Dropping/reordering/coalescing breaks `SequenceInterpreter` resolution.
- **Malformed/unknown writes are ignored, never faulted.** Mirror the codec's totality: an unknown tag, wrong length, reserved bits, out-of-range enum, or stray payload on a tag-only command all become a no-op in the write handler (reserved command space `0x84–0x9F` included).
- **`ble_gatts_notify_custom` symbol check.** Confirm the symbol against the actually-installed ESP-IDF/NimBLE headers — very old NimBLE used `ble_gattc_notify_custom`. Resolve at build time, not by assumption.
- **`battery_pct = 100` is a deliberate USB constant**, in the documented 0–100 range — not a real reading. `firmware_version = 1.0.0`. `uptime_seconds` is an **unsigned** 32-bit little-endian field (`esp_timer`/tick-derived seconds).
- **`BOARD_SNAPSHOT` is always built by the scan/producer task — never read `stable` cross-task.** `stable` is a 64-bit value owned by the scan task; a read from the BLE host or a timer can tear on the 32-bit dual-core ESP32 (two non-atomic word loads → a half-updated occupancy). So on-subscribe, `REQUEST_SNAPSHOT`, and each diagnostic tick post a lightweight *snapshot-request* onto the event queue; the scan task reads its own `stable`, encodes the frame via the `board_protocol` encoder, and enqueues it. `DEVICE_STATUS` is exempt — it reads only constants + `esp_timer` uptime, never `stable`, so any context may build it.

## Phase 1: Protocol Core + Host Test Harness

### Overview

Extract the hardware-free logic into `firmware/lib/` and add a PlatformIO `native` Unity test environment that asserts the same golden byte vectors as the Kotlin codec. No ESP-IDF, NimBLE, or GPIO in this code. This phase is fully automatically verifiable and de-risks the contract before any radio work.

### Changes Required:

#### 1. Board-protocol library (pure C++)

**File**: `firmware/lib/board_protocol/board_protocol.h`, `firmware/lib/board_protocol/board_protocol.cpp`

**Intent**: House the byte codec the firmware uses at runtime — encoders for the four board→mobile events and a decoder for the three mobile→board commands — plus the snapshot bit-packing and the `stable`-diff → square-event derivation. Pure, freestanding C++ usable by both the device build and the host test build.

**Contract**: No ESP-IDF / Arduino / GPIO includes (only `<cstdint>`/`<cstddef>`). Square/snapshot packing locked to §1.3: byte `i` bit `j` (LSB-first) = square `i*8+j`; snapshot byte `i = (occ >> (i*8)) & 0xFF`. Tags and field layouts exactly as `BoardWireCodec.kt`. A fixed-capacity frame type (max 9 bytes + length). Command decode returns a tagged result with an explicit `Malformed` case (never aborts). Encoders cover: `BOARD_SNAPSHOT` (`0x01` + 8 bytes), `SQUARE_EVENT` (`0x02`, `(eventBits<<6)|square`, `00`=lift `01`=place), `BUTTON_EVENT` (`0x03`, `0x00` white / `0x01` black), `DEVICE_STATUS` (`0x04` + battery + major/minor/patch + uptime u32 LE). Command decoder covers `SET_MODE` (`0x81`), `REQUEST_SNAPSHOT` (`0x82`), `REQUEST_STATUS` (`0x83`) with exact frame-size checks and reserved-tag rejection.

#### 2. Debounce library (pure C++)

**File**: `firmware/lib/debounce/debounce.h`, `firmware/lib/debounce/debounce.cpp`

**Intent**: Extract the debounce state machine from `main.cpp` into a pure, host-testable unit operating on the 64-bit raw/stable bitmaps and the per-square agreement counters — behavior-identical to the current loop (`kStableScans=4`: N agreeing scans commit; any disagreement resets).

**Contract**: A small struct holding `agree[64]` + `stable`; a `step(rawScan)` that returns the updated `stable` bitmap. No timing/GPIO — the caller owns scan cadence. `main.cpp` will consume this in Phase 3; in Phase 1 the device build still compiles with the loop intact (the extraction is wired in Phase 3 to keep this phase test-only-additive).

#### 3. PlatformIO native test environment

**File**: `firmware/platformio.ini`

**Intent**: Add an `[env:native]` host environment using Unity so `pio test -e native` builds and runs the `lib/` tests on the dev machine, separate from the device build.

**Contract**: `[env:native]` with `platform = native`, `test_framework = unity`. Default `test_build_src = false` keeps `main.cpp` out of the host binary; `lib/` is picked up by the Library Dependency Finder. The existing `[env:esp32dev]` is unchanged.

#### 4. Golden-vector tests

**File**: `firmware/test/test_protocol/test_protocol.cpp`, `firmware/test/test_debounce/test_debounce.cpp`

**Intent**: Assert the encoders/decoder against the **exact** golden frames from `BoardWireCodecTest.kt` (the cross-language oracle), and assert the debounce commit/reset semantics.

**Contract**: Protocol vectors asserted verbatim — `SQUARE_EVENT`: `02 00`, `02 40`, `02 3F`, `02 7F`, `02 0C`, `02 5C`; `BUTTON_EVENT`: `03 00`, `03 01`; `BOARD_SNAPSHOT`: `01`+`00×8`, `01`+`FF×8`, `01 FF FF 00 00 00 00 FF FF`, `01 00 01 00 00 00 00 00 00` (a2=sq8), `01 80 00 00 00 00 00 00 00` (h1=sq7); `DEVICE_STATUS`: `04 64 01 02 03 00 00 00 00`, `04 32 02 00 01 01 02 03 04` (uptime 67 305 985), `04 00 00 00 00 FF FF FF FF` (uptime 4 294 967 295, not −1); commands `81 00`, `81 01`, `82`, `83`. Malformed cases rejected: empty, `05 00`, `01 FF`, oversized snapshot (10 B), `02`, `02 85`, `02 C0`, `03 02`, `04 64`; commands empty, `84`, `90`, `81 02`, `81`, `82 00`, `83 00`. Debounce: a square flips to occupied only after 4 consecutive agreeing scans; a single disagreeing scan resets the counter.

### Success Criteria:

#### Automated Verification:

- Host tests pass: `cd firmware && pio test -e native`
- Device build still compiles: `cd firmware && pio run -e esp32dev`
- `firmware/lib/` sources contain no ESP-IDF / Arduino / GPIO includes (grep for `driver/gpio.h`, `esp_`, `Arduino.h` returns nothing under `lib/`)

#### Manual Verification:

- Spot-check that the asserted golden frames in `test_protocol.cpp` match the literals in `BoardWireCodecTest.kt` (same bytes, both directions)

**Implementation Note**: After this phase and its automated verification pass, pause for manual confirmation before Phase 2.

---

## Phase 2: NimBLE Peripheral — GATT, Advertising, Bonding, Lifecycle

### Overview

Make the device a BLE peripheral: enable NimBLE, mint and record the UUIDs, advertise, accept a single bonded central, expose the one GATT service, and implement the connection lifecycle (on-subscribe snapshot+status burst, re-advertise on disconnect). Square/button streaming and commands arrive in Phase 3 — this phase delivers a connectable, bonding board that emits the initial burst.

### Changes Required:

#### 1. Enable BLE/NimBLE in the config seed

**File**: `firmware/sdkconfig.defaults`

**Intent**: Turn on Bluetooth + the NimBLE host (BLE-only peripheral) and enable NVS-backed bond storage, editing the committed seed (never the generated `sdkconfig*`).

**Contract**: Add `CONFIG_BT_ENABLED=y` and `CONFIG_BT_NIMBLE_ENABLED=y`. Constrain to one connection (`CONFIG_BT_NIMBLE_MAX_CONNECTIONS=1`) and peripheral-only role where the options exist. Ensure NVS persistence for bonds is enabled (NimBLE default + `nvs_flash_init()` at boot). Keep `CONFIG_ESP_CONSOLE_UART_DEFAULT=y`.

#### 2. Record the GATT UUIDs in the contract

**File**: `docs/reference/contract-surfaces.md` (§1.2)

**Intent**: Fill the §1.2 "UUIDs assigned during firmware implementation" slot with the minted family so mobile (S-09) and firmware share identical bytes. Resolves OQ-5.

**Contract**: Service `787e0001-15a4-4fc9-a469-05096dbad1a1`; `board_event` (notify) `787e0002-15a4-4fc9-a469-05096dbad1a1`; `mobile_command` (write) `787e0003-15a4-4fc9-a469-05096dbad1a1`. Bump `updated:` in the frontmatter and add the one-line rationale per the doc's own change-control rule.

#### 3. BLE peripheral + GATT server

**File**: `firmware/src/ble_service.h`, `firmware/src/ble_service.cpp` (new), wired from `firmware/src/main.cpp`; `firmware/src/CMakeLists.txt` updated to register the new source and the `nimble`/`nvs_flash` component requirements.

**Intent**: Initialize NVS + NimBLE host, register one primary service with the two characteristics, advertise, and run the GAP/GATT lifecycle. Expose a tiny internal API the Phase-3 producer/consumer will call (a "notify this frame if subscribed" entry point and the subscribe/connection flags).

**Contract**: One primary service (`BLE_UUID128_INIT(service)`); `board_event` = `BLE_GATT_CHR_F_NOTIFY` (NimBLE auto-adds the 0x2902 CCCD), `mobile_command` = `BLE_GATT_CHR_F_WRITE`. Advertising: name `SmartChessboard-XXXX` (last 4 hex of `esp_read_mac(..., ESP_MAC_BT)`) in the **scan response**, service UUID in the **advertisement**; undirected connectable. Pairing "Just Works" (`sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT`, `sm_bonding=1`, `sm_sc=1`). GAP events: `CONNECT` → mark connected, `mode=GAME`, stop diagnostic timer; `SUBSCRIBE` (board_event CCCD enabled) → post a connect-burst request the **producer** services by enqueuing `BOARD_SNAPSHOT` (built from `stable` on the scan task — see Critical Details) then `DEVICE_STATUS`, in that order; `DISCONNECT` → clear flags, stop timers, re-advertise. Notify via `ble_gatts_notify_custom(conn, board_event_handle, om)` (verify symbol per Critical Details).

#### 4. Status/snapshot source helpers

**File**: `firmware/src/ble_service.cpp` (or a small `device_state` unit)

**Intent**: Provide the current-occupancy snapshot frame and the `DEVICE_STATUS` frame (battery 100, fw 1.0.0, uptime from `esp_timer`) using the Phase-1 encoders.

**Contract**: The snapshot frame is built by the **scan/producer task** from its own `stable` bitmap on a snapshot-request — **never read cross-task from the BLE context** (a 64-bit `stable` read off-task can tear on the dual-core ESP32; see Critical Details). Status reads constants + uptime seconds and may be built in any context. Frames built only via `board_protocol` encoders (single source of truth).

### Success Criteria:

#### Automated Verification:

- Device build links with NimBLE: `cd firmware && pio run -e esp32dev`
- Host tests still pass: `cd firmware && pio test -e native`
- `contract-surfaces.md` §1.2 contains all three UUIDs and an updated `updated:` date

#### Manual Verification:

- Flashed board advertises as `SmartChessboard-XXXX` (visible in a BLE scanner, e.g. nRF Connect)
- A central connects and bonds ("Just Works", no PIN); reconnect does not re-pair
- On enabling notifications for `board_event`, the scanner receives `BOARD_SNAPSHOT` (9 B) then `DEVICE_STATUS` (9 B: battery `0x64`, fw `01 00 00`)
- Disconnecting the central leaves the board advertising again (reconnectable without power cycle)

**Implementation Note**: After this phase and its automated verification pass, pause for manual confirmation before Phase 3.

---

## Phase 3: Game Behavior — Events, Buttons, Commands, Diagnostic

### Overview

Wire the live behavior on top of the BLE skeleton: the scan/debounce loop and button reads become producers onto a FreeRTOS queue drained by the BLE consumer; `SQUARE_EVENT`s stream per transition; the two buttons emit `BUTTON_EVENT`; the write handler dispatches the three commands; diagnostic (~10 Hz) and periodic-status (~30 s) timers run. This completes the §1 contract.

### Changes Required:

#### 1. Producer/consumer event pipeline

**File**: `firmware/src/main.cpp`, `firmware/src/ble_service.cpp`

**Intent**: Refactor `app_main` so the scan/debounce loop (now using `lib/debounce`) runs as a producer that posts an event struct to a FreeRTOS queue whenever `stable` changes; a consumer in the BLE host context drains the queue and notifies (gated on the subscribe flag + connection).

**Contract**: A `BoardEventMsg { uint8_t bytes[9]; uint8_t len; }` queue (or a `{kind,payload}` struct encoded at drain time). Producer posts one `SQUARE_EVENT` per changed square from the `stable` diff (`placed`/`lifted` as in Critical Details), never coalescing. Producer **never** calls BLE APIs. Consumer calls `ble_gatts_notify_custom` only when connected and subscribed; otherwise drops (game-mode events while unsubscribed are not buffered, per contract §1.7 "dead link delivers nothing"). Queue depth + full-policy: size the queue for the worst burst (diagnostic ~10 Hz snapshot-requests + simultaneous square changes) and **never silently drop `SQUARE_EVENT` / `BUTTON_EVENT` on a live, subscribed link** — they carry the non-coalesceable transition stream `SequenceInterpreter` depends on (contract §1.3); only diagnostic `BOARD_SNAPSHOT`s may coalesce/drop under pressure (idempotent — latest wins). The existing serial `render()` may stay for local debug but is not on the contract path.

#### 2. Physical confirmation buttons

**File**: `firmware/src/pins.h`, `firmware/src/main.cpp`

**Intent**: Add two debounced confirmation buttons (FR-FW-007) and emit `BUTTON_EVENT(white|black)` on press — additively, without touching the matrix mapping.

**Contract**: `pins.h` gains `kButtonWhitePin = GPIO_NUM_22`, `kButtonBlackPin = GPIO_NUM_23` (WROOM-32 bonded-out, internal pull-up capable), configured as inputs with pull-ups (idle HIGH, pressed = LOW). A short press-debounce (reuse the agreement-counter idea or a simple edge + settle) posts `BUTTON_EVENT` to the same queue on a press edge. White = `0x00`, black = `0x01`. Buttons are bare events — no turn validation.

#### 3. Mobile-command write handler

**File**: `firmware/src/ble_service.cpp`

**Intent**: Decode `mobile_command` writes via `lib/board_protocol` and act on them; ignore anything malformed/unknown without faulting.

**Contract**: In the `BLE_GATT_ACCESS_OP_WRITE_CHR` callback, flatten the mbuf into a byte buffer, call the command decoder, and dispatch: `SET_MODE(game)` → stop diagnostic timer; `SET_MODE(diagnostic)` → start the ~100 ms snapshot timer; `REQUEST_SNAPSHOT` → post a snapshot-request (the producer enqueues the `BOARD_SNAPSHOT` from `stable`); `REQUEST_STATUS` → post a `DEVICE_STATUS`. A `Malformed` result (bad length, unknown/reserved tag `0x84–0x9F`, out-of-range mode, stray payload) is a no-op. Return a success ATT status regardless (no error responses that could confuse a central).

#### 4. Periodic status + diagnostic timers

**File**: `firmware/src/ble_service.cpp`

**Intent**: Drive the ~30 s `DEVICE_STATUS` and the diagnostic-mode ~10 Hz `BOARD_SNAPSHOT` via FreeRTOS software timers that post onto the event queue.

**Contract**: A ~30 s auto-reload `xTimer` started on subscribe (stopped on disconnect) posts `DEVICE_STATUS`. A ~100 ms `xTimer` started on `SET_MODE→diagnostic` and stopped on `SET_MODE→game` (and on disconnect) posts a snapshot-request (the producer enqueues the `BOARD_SNAPSHOT`). Rate is a target; modest variance is acceptable (contract §1.6). Timer callbacks only enqueue — they never call BLE APIs directly.

### Success Criteria:

#### Automated Verification:

- Device build compiles and links: `cd firmware && pio run -e esp32dev`
- Host tests still pass (debounce + protocol unchanged): `cd firmware && pio test -e native`
- If the `stable`-diff → square-event derivation is in `lib/`, it has a native test asserting lift/place events for a representative diff

#### Manual Verification (on hardware, best-effort — partially-working board + temporary buttons):

- Moving a magnet on a working square produces `SQUARE_EVENT` lift then place in the scanner, with the correct index and event bits (`00`=lift, `01`=place)
- Pressing the temporary white/black button produces `BUTTON_EVENT` `03 00` / `03 01`
- `SET_MODE(diagnostic)` (`81 01`) starts a ~10 Hz `BOARD_SNAPSHOT` stream; `SET_MODE(game)` (`81 00`) stops it
- `REQUEST_SNAPSHOT` (`82`) and `REQUEST_STATUS` (`83`) each yield an immediate frame
- A `DEVICE_STATUS` arrives roughly every ~30 s while subscribed
- Disconnect → change a square offline → reconnect: the reconnect snapshot reflects the offline change
- Writing a malformed/reserved command (e.g. `84`, `81 02`) is ignored — no crash, no reset

**Implementation Note**: After this phase and its automated verification pass, pause for manual confirmation before Phase 4. Per the project's manual-gate convention, on-hardware items that need the board may be recorded to `manual-verification.md` and confirmed at end-of-slice if the board isn't to hand at phase close.

---

## Phase 4: Docs + Contract Consolidation

### Overview

Record what F-03 built and resolve the open questions this change settles. (The UUID write-back already happened in Phase 2.)

### Changes Required:

#### 1. Unpark and update the firmware module guide

**File**: `firmware/AGENTS.md`

**Intent**: Replace the stale "PARKED" banner with the accurate status (firmware **software** greenlit; only the hardware reed-matrix repair stays parked and gates S-09) and document the new surface: NimBLE peripheral, the `board_event`/`mobile_command` service, the two buttons (GPIO22/23), `firmware/lib/`, and the `pio test -e native` host-test flow.

**Contract**: Status section reframed; architecture/build sections extended to cover BLE + buttons + lib + native tests. No change to the "two pin maps" gotcha or the matrix `pins.h` warning.

#### 2. Resolve firmware PRD open questions

**File**: `context/foundation/prd-firmware.md`

**Intent**: Mark the OQs this change settles: OQ-2 power = USB-only (battery_pct constant 100), OQ-4 toolchain = ESP-IDF, OQ-5 UUIDs assigned (recorded in contract §1.2). Note the NimBLE choice and the `battery_pct=100` USB convention.

**Contract**: Append dated resolutions in the Open Questions section (and/or an Implementation Decisions note); keep OQ-1 (exact variant) and OQ-3 (matrix wiring documentation) open as non-blocking. **Also mirror the §1.2 UUID assignment into `context/foundation/prd.md`** — the contract's change-control rule requires Section 1 (BLE) changes to land in *both* `prd-firmware.md` and `prd.md`; add a one-line dated note to `prd.md`'s Implementation Decisions naming the three minted UUIDs (no product-FR change).

#### 3. Note the buttons in hardware docs

**File**: `firmware/HARDWARE.md` (and/or `PINOUT.md`)

**Intent**: Record that GPIO22 (white) / GPIO23 (black) are now the confirmation-button pins so a future clean build wires them.

**Contract**: A short additive note; no change to the matrix wiring tables.

### Success Criteria:

#### Automated Verification:

- `firmware/AGENTS.md` no longer presents the firmware software as parked (the "Status: PARKED / don't resume" framing is updated)
- `prd-firmware.md` OQ-2/OQ-4/OQ-5 carry dated resolutions; `prd.md` carries the dated §1.2 UUID mirror note (contract change-control)
- No build/test regression: `cd firmware && pio run -e esp32dev && pio test -e native`

#### Manual Verification:

- The updated `firmware/AGENTS.md` reads coherently and matches what was built (BLE, buttons, lib, native tests)

**Implementation Note**: Final phase — after automated verification and a docs read-through, the change is ready for `/10x-impl-review`.

---

## Testing Strategy

### Unit Tests (host, `pio test -e native`):

- Protocol encoders/decoder asserted against the exact `BoardWireCodecTest.kt` golden vectors (both the valid frames and the malformed/reserved rejections).
- Debounce state machine: commit after 4 agreeing scans, reset on any disagreement.
- (If extracted) `stable`-diff → `SQUARE_EVENT` derivation: lift/place sets for representative diffs.

### Integration Tests:

- None automatable off-hardware (the radio/GPIO path). The device build compiling/linking (`pio run -e esp32dev`) is the automated gate; behavior is verified on hardware.

### Manual Testing Steps (on hardware, best-effort):

1. Flash (`pio run -t upload`), confirm advertising name in a BLE scanner.
2. Connect + bond; verify the on-subscribe `BOARD_SNAPSHOT` → `DEVICE_STATUS` burst.
3. Move a magnet on a working square → correct `SQUARE_EVENT`(s).
4. Press a temporary button → `BUTTON_EVENT`.
5. `SET_MODE(diagnostic)` → ~10 Hz snapshots; `SET_MODE(game)` → stop.
6. `REQUEST_SNAPSHOT` / `REQUEST_STATUS` → immediate frames; wait for the ~30 s periodic status.
7. Disconnect → offline square change → reconnect → snapshot reflects the change.
8. Write a malformed/reserved command → ignored, no crash.

## Performance Considerations

- **Latency NFR ≤100 ms**: ~80 ms debounce + sub-ms queue→notify hop — comfortable margin.
- **Diagnostic 10 Hz / status 30 s**: software-timer targets; modest variance acceptable. Diagnostic snapshots add to (do not replace) per-transition `SQUARE_EVENT`s.
- **Heap/flash**: NimBLE is the smaller stack (vs Bluedroid); single connection, one service. No MTU negotiation (max 9-byte frame).

## Migration Notes

- `sdkconfig.defaults` gains BLE/NimBLE/NVS options — edit the **seed**, never the gitignored generated `sdkconfig*` (re-generated on next build).
- `pins.h` button pins are **additive**; the matrix mapping and the deliberate `pins.h`-vs-README divergence are untouched.
- The debounce extraction must be behavior-identical to the current loop — the native test is the guard against regression.

## References

- Research: `context/changes/firmware-ble-gatt-service/research.md`
- Contract (frozen interface): `docs/reference/contract-surfaces.md` §1
- Byte-for-byte reference: [BoardWireCodec.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/protocol/BoardWireCodec.kt)
- Golden vectors (oracle): [BoardWireCodecTest.kt](SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/data/board/protocol/BoardWireCodecTest.kt)
- Behavioral spec: [EmulatedBoard.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoard.kt)
- Current firmware: [main.cpp](firmware/src/main.cpp), [pins.h](firmware/src/pins.h), [platformio.ini](firmware/platformio.ini), [firmware/AGENTS.md](firmware/AGENTS.md)
- Firmware PRD: `context/foundation/prd-firmware.md` (FR-FW-002…013)
- Roadmap: `context/foundation/roadmap.md` (F-03)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Protocol Core + Host Test Harness

#### Automated

- [x] 1.1 Host tests pass: `pio test -e native` — c3d3665
- [x] 1.2 Device build still compiles: `pio run -e esp32dev` — c3d3665
- [x] 1.3 `firmware/lib/` contains no ESP-IDF / Arduino / GPIO includes — c3d3665

#### Manual

- [x] 1.4 Spot-check asserted golden frames in `test_protocol.cpp` match `BoardWireCodecTest.kt` — confirmed by impl-review 2026-06-19 (vectors match Kotlin oracle verbatim; `pio test -e native` 15/15)

### Phase 2: NimBLE Peripheral — GATT, Advertising, Bonding, Lifecycle

#### Automated

- [x] 2.1 Device build links with NimBLE: `pio run -e esp32dev` — e3c594a
- [x] 2.2 Host tests still pass: `pio test -e native` — e3c594a
- [x] 2.3 `contract-surfaces.md` §1.2 contains all three UUIDs + updated date — e3c594a

#### Manual

- [ ] 2.4 Board advertises as `SmartChessboard-XXXX` (visible in a BLE scanner)
- [ ] 2.5 Central connects and bonds ("Just Works"); reconnect does not re-pair
- [ ] 2.6 On `board_event` subscribe: receive `BOARD_SNAPSHOT` then `DEVICE_STATUS` (battery `0x64`, fw `01 00 00`)
- [ ] 2.7 Disconnect leaves the board advertising again (reconnectable, no power cycle)

### Phase 3: Game Behavior — Events, Buttons, Commands, Diagnostic

#### Automated

- [x] 3.1 Device build compiles and links: `pio run -e esp32dev` — e5bfcb9
- [x] 3.2 Host tests still pass: `pio test -e native` — e5bfcb9
- [x] 3.3 `stable`-diff → square-event derivation has a native test (if extracted to `lib/`) — e5bfcb9

#### Manual

- [ ] 3.4 Magnet on a working square → `SQUARE_EVENT` lift then place, correct index/event bits
- [ ] 3.5 Temporary white/black button → `BUTTON_EVENT` `03 00` / `03 01`
- [ ] 3.6 `SET_MODE(diagnostic)` → ~10 Hz snapshots; `SET_MODE(game)` stops them
- [ ] 3.7 `REQUEST_SNAPSHOT` / `REQUEST_STATUS` → immediate frames
- [ ] 3.8 `DEVICE_STATUS` arrives roughly every ~30 s while subscribed
- [ ] 3.9 Disconnect → offline square change → reconnect snapshot reflects the change
- [ ] 3.10 Malformed/reserved command is ignored — no crash, no reset

### Phase 4: Docs + Contract Consolidation

#### Automated

- [x] 4.1 `firmware/AGENTS.md` no longer presents the firmware software as parked — d64e2d8
- [x] 4.2 `prd-firmware.md` OQ-2/OQ-4/OQ-5 carry dated resolutions; `prd.md` §1.2 UUID mirror note added — d64e2d8
- [x] 4.3 No build/test regression: `pio run -e esp32dev && pio test -e native` — d64e2d8

#### Manual

- [x] 4.4 Updated `firmware/AGENTS.md` reads coherently and matches what was built — confirmed by impl-review 2026-06-19 (PARKED framing gone; BLE/buttons/lib/native-tests documented)
