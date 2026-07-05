---
date: 2026-06-19T17:16:07+0200
researcher: Rafał Urbaniak
git_commit: c9442c7802b69d68ec2728ac1b976757d2aa30f1
branch: main
repository: smartchessboard (bitbucket: <user>/smartchessboard)
topic: "Firmware BLE GATT service (F-03) — implementing the §1 board contract on ESP32"
tags: [research, codebase, firmware, ble, gatt, esp32, nimble, contract-surfaces, byte-protocol]
status: complete
last_updated: 2026-06-19
last_updated_by: Rafał Urbaniak
---

# Research: Firmware BLE GATT service (F-03)

**Date**: 2026-06-19T17:16:07+0200
**Researcher**: Rafał Urbaniak
**Git Commit**: c9442c7802b69d68ec2728ac1b976757d2aa30f1
**Branch**: main
**Repository**: smartchessboard (Bitbucket `<user>/smartchessboard`; not pushed at this commit, so no public permalinks)

## Research Question

For the change `firmware-ble-gatt-service` (roadmap item **F-03**), research everything needed to implement the §1 BLE board contract on the ESP32: the current firmware and the gap to BLE, the byte-for-byte contract the firmware must match (including what the mobile side already implements), the frozen decisions from prior changes that constrain firmware behavior, and a grounded recommendation for the ESP-IDF BLE stack and host-side testing. Consolidate all open decisions as input to `/10x-plan`.

## Summary

**F-03 is well-bounded and unusually de-risked, because the mobile side already contains a complete, unit-tested, byte-level reference implementation of the wire protocol.** The headline findings:

1. **The wire format is not "spec-only" — it is implemented and tested in Kotlin.** `BoardWireCodec.kt` (in `commonMain`) encodes and decodes every §1.3/§1.4 message with hand-derived golden-frame tests, and the F-02 emulator round-trips every event through it so the emulated stream is *byte-identical* to what real firmware must send. Build the firmware's `board_event`/`mobile_command` byte format directly against this codec and you are byte-for-byte compatible. The codec's own header comment names the firmware as the future consumer.

2. **The firmware re-uses almost all of the existing diagnostic firmware.** Matrix scan (~50 Hz), 4-scan debounce (~80 ms), and the `index = file + 8*rank` square convention are implemented and hardware-verified (2026-05-28). F-03 adds: BLE (NimBLE) peripheral + one GATT service, the byte encoders/decoders, two physical buttons (not yet wired), diagnostic mode, and the connection lifecycle. **No reed-scan rewrite.**

3. **The board is a "dumb" sensor.** It reports raw square lift/place transitions and button presses only — no chess logic, no promotion/castling/en-passant awareness. The mobile `SequenceInterpreter` reconstructs moves from the *event stream*. This makes a behavioral mandate: **emit a discrete `SQUARE_EVENT` on every reed transition in real time, never coalesce, never send only periodic snapshots in game mode** — the lift event on a capture destination is the discriminator a bare snapshot diff cannot provide.

4. **Recommended BLE stack: NimBLE** (not Bluedroid) — BLE-only peripheral, smaller heap/flash, Espressif's recommended choice when Classic BT isn't needed, portable across classic/S3/C3/C6.

5. **Testing without hardware is achievable**: extract the hardware-free logic (encoders/decoders, square packing, debounce) into `firmware/lib/`, add a PlatformIO `[env:native]` + Unity, and assert the **same golden byte vectors** the Kotlin codec tests use — a cross-language oracle that directly attacks the one real risk (contract drift between emulator and firmware).

**Status note (important):** `firmware/AGENTS.md` still says **PARKED** — that banner predates the F-03 greenlight and refers to the *hardware bringup*. Per the roadmap, firmware **software** (F-03) is now `ready`/greenlit (2026-06-19) and runs fully parallel to all mobile work. Only the **hardware reed-matrix repair** stays parked, and it gates **S-09** (real-board play), not F-03. F-03 can be planned and implemented now; it is validated against the contract + emulator and only meets hardware at S-09.

## Detailed Findings

### A. Where F-03 sits (roadmap, scope, status)

- **Roadmap**: F-03 = `firmware-ble-gatt-service`, status `ready`, Stream D ("Firmware"), runs parallel to F-01/F-02/S-01…S-08; only **S-09** consumes it ([roadmap.md:100-112](context/foundation/roadmap.md:100)). Outcome: "ESP32 firmware implements the §1 BLE contract end-to-end … verified by firmware unit tests plus the F-02 emulator's contract, so a real board is ready for S-09." No user-visible outcome on its own.
- **PRD scope**: `prd-firmware.md` FR-FW-002 … FR-FW-013 (debounce, BLE peripheral, GATT, snapshot/event/status encoding, mobile commands, connection lifecycle, no-persistence). FR-FW-001 (raw reed sampling) is **already done** and consumed, not rebuilt ([prd-firmware.md:56-122](context/foundation/prd-firmware.md:56)).
- **Contract**: `contract-surfaces.md` §1 is the frozen interface (updated 2026-06-16 and 2026-06-19) ([contract-surfaces.md:58-180](docs/reference/contract-surfaces.md:58)).
- **The named risk** (roadmap.md:112): *contract drift between the emulator's assumptions and real firmware behaviour* — "Keep the firmware byte-for-byte identical to `contract-surfaces.md` §1 and the emulator's event stream so the S-06–S-08 verification transfers to hardware." The 2026-06-16 `BOARD_SNAPSHOT` byte-layout clarification was exactly this class of bug, caught early.

### B. Current firmware state and the gap to BLE

**Implemented today** (diagnostic-only, hardware-verified 2026-05-28; reuse as-is):
- Matrix scan → `uint64_t` occupancy bitmap, active-row multiplexing, ~50 Hz ([main.cpp:65-76](firmware/src/main.cpp:65); cadence `kScanIntervalMs=20` at [main.cpp:26](firmware/src/main.cpp:26)).
- Debounce: per-square agreement counters, `kStableScans=4` (~80 ms) committed to a `stable` bitmap; any disagreement resets ([main.cpp:114-145](firmware/src/main.cpp:114), constants [main.cpp:25-27](firmware/src/main.cpp:25)).
- Square index: `index = col + 8*row = file + 8*rank`, a1=0…h8=63, **calibrated in software** via `square_index()` (do not rewire) ([main.cpp:31-33](firmware/src/main.cpp:31)).
- Serial render (debug) ([main.cpp:79-112](firmware/src/main.cpp:79)).

**Missing for F-03 (the gap)** — none of this exists yet ([main.cpp:10](firmware/src/main.cpp:10) "BLE GATT is deferred"; `sdkconfig.defaults` is BLE-free):
- BLE peripheral init + advertising; one GATT service with `board_event` (notify) + `mobile_command` (write).
- Byte encoders for `BOARD_SNAPSHOT` / `SQUARE_EVENT` / `BUTTON_EVENT` / `DEVICE_STATUS`.
- Write handler dispatching on `SET_MODE` / `REQUEST_SNAPSHOT` / `REQUEST_STATUS`.
- **Two physical confirmation buttons** (FR-FW-007) — *no button GPIO is wired in `pins.h` at all*; buttons must be added (debounced) and mapped to white/black.
- Diagnostic mode (≈10 Hz snapshot push).
- Connection lifecycle: bond on first connect, single central, re-advertise after disconnect (FR-FW-012).

**Pin map facts** ([pins.h:37-58](firmware/src/pins.h:37)): rows `{32,33,25,26,27,14,12,13}`, cols `{19,18,5,17,16,4,21,15}` — 16 pins. `pins.h` is the **single source of truth for what is flashed** and intentionally differs from README/PINOUT/WIRING (the hazard-free target). **Do not "fix" `pins.h` to match the docs** — that breaks the verified prototype ([firmware/AGENTS.md](firmware/AGENTS.md), [pins.h:16-31](firmware/src/pins.h:16)). Adding two buttons is *additive* (new pins), not a rewire.

**Free GPIO for the two buttons** (correcting the raw sub-agent sweep): on the classic **ESP32-WROOM-32** (`esp32dev`), the clean, output/pull-up-capable, **bonded-out** pins not used by the matrix are **GPIO22 and GPIO23** — recommended pair (white = 22, black = 23). Caveats: GPIO20/24/28–31 exist on the die but are **not bonded out** on WROOM-32 (a sub-agent suggested GPIO20 — not usable here); GPIO0/2 are strapping/boot pins (avoid for buttons); GPIO1/3 are the UART0 console (in use by the monitor); GPIO34–39 are input-only with **no internal pull-up** (would need external pull-ups). The exact free set depends on the final variant (OQ-1) — re-confirm if the board moves to S3/C3/C6.

**Build / test infra**: `pio run` / `pio run -t upload` (PlatformIO + ESP-IDF; first run downloads the toolchain) ([platformio.ini:7-10](firmware/platformio.ini:7)). **No `test/` dir, no Unity, no host tests today.** `sdkconfig.defaults` is the committed seed (BLE-free); the generated `sdkconfig*` are gitignored — edit the seed.

### C. The mobile-side wire codec — the byte-for-byte reference (HEADLINE)

The firmware↔mobile seam is exposed to the app as **typed objects** but is **bytes on the wire**, and the bytes are already implemented in `commonMain`:

- **Port**: `interface BoardConnection` — `connectionState: StateFlow`, `events: SharedFlow<BoardEvent>` (board→mobile), `suspend fun send(command: BoardCommand)` (mobile→board) ([BoardConnection.kt:18-27](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/BoardConnection.kt:18)). The emulator is the *only* binding today; the S-09 BLE adapter will plug `BoardWireCodec` straight onto the GATT characteristics.
- **Codec**: [BoardWireCodec.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/protocol/BoardWireCodec.kt) — bidirectional `ByteArray ↔ typed object` for all 7 message types, with golden-frame tests (`BoardWireCodecTest.kt`). Header comment: *"The encoder is the board side (used by the F-02 emulator, later a reference for the firmware); the decoder is the mobile side (reused verbatim by the S-09 BLE adapter)."*
- **Round-trip guarantee**: the emulator forces every event through `typed → §1.3 bytes → typed` so the observed stream is byte-identical to firmware ([EmulatedBoard.kt:187-201](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoard.kt:187)).
- **Square math**: single authority `Square.kt`, `index = file + 8*rank`, a1=0…h8=63 ([Square.kt:1-19](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/Square.kt:1)). Matches firmware `main.cpp` exactly.

**Wire format reference (frame = 1 tag byte + payload). The contract's "N bytes" counts *payload*; add 1 for the tag.**

| Message | Tag | Frame size | Layout the firmware must emit/accept |
| --- | --- | --- | --- |
| `BOARD_SNAPSHOT` | `0x01` | 9 B | `[0x01]` + 8 occupancy bytes. **Byte `i` bit `j` (LSB-first) = square `i*8+j`** → byte0 = squares 0–7 (a1 = byte0 bit0), byte7 = 56–63 (h8 = byte7 bit7). Start position = `01 FF FF 00 00 00 00 FF FF`. |
| `SQUARE_EVENT` | `0x02` | 2 B | `[0x02][(eventBits<<6) | square]`. square = low 6 bits (mask `0x3F`); event = high 2 bits: **`00`=lift, `01`=place**; `10`/`11` reserved (decoder rejects). a1 lift=`02 00`; a1 place=`02 40`; h8 lift=`02 3F`; h8 place=`02 7F`. |
| `BUTTON_EVENT` | `0x03` | 2 B | `[0x03][0x00 = white | 0x01 = black]`. |
| `DEVICE_STATUS` | `0x04` | 9 B | `[0x04][battery_pct][fw_major][fw_minor][fw_patch][uptime u32 LE × 4]`. **uptime is unsigned 32-bit little-endian** — `0xFFFFFFFF` decodes to `4294967295`, not `-1`. uptime `0x04030201` → bytes `01 02 03 04`. |
| `SET_MODE` | `0x81` | 2 B | `[0x81][0x00 = game | 0x01 = diagnostic]`. |
| `REQUEST_SNAPSHOT` | `0x82` | 1 B | `[0x82]` (tag only). |
| `REQUEST_STATUS` | `0x83` | 1 B | `[0x83]` (tag only). |

Additional codec rules the firmware should mirror:
- **Exact frame sizes** (not minimums): snapshot/status = 9, single-payload = 2, tag-only = 1.
- **Total, non-throwing decode**: empty frame, unknown tag, wrong length, reserved bits, out-of-range enum, or stray payload on a tag-only command all map to a described "Malformed" — never a crash. **Firmware should likewise ignore malformed/unknown `mobile_command` writes rather than fault.**
- **Reserved command tags `0x84–0x9F`** are explicitly rejected (contract reserves them for post-MVP).
- **Occupancy is a `uint64_t`**: square 63 is the top bit; the Kotlin side warns against signed comparison ([Occupancy.kt:17-20](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/Occupancy.kt:17)). The firmware's existing `uint64_t occ` is already correct.

### D. Frozen behavioral decisions that constrain firmware

From `contract-surfaces.md` §1 and prior changes (F-02 emulator, S-06 capture):

1. **Per-transition `SQUARE_EVENT` streaming is mandatory; no coalescing/filtering.** `SequenceInterpreter` matches the full occupancy signature (vacated/arrived/captured-in-place) against legal-move footprints; a dropped, reordered, or coalesced event breaks resolution. Transient j'adoube lifts (lift e4 / place e4) are tolerated as noise by the interpreter — the firmware should still report them, not suppress them ([SequenceInterpreter.kt:13-23,79-113](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/SequenceInterpreter.kt:13)).
2. **On (re)connect: emit `BOARD_SNAPSHOT` then `DEVICE_STATUS`, and reset mode to GAME** ([EmulatedBoard.kt:84-93](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoard.kt:84)). Load-bearing for S-08 resume: the reconnect snapshot is how the app detects board changes that happened while disconnected.
3. **Dumb board — squares only.** Promotion = lift on rank 7/2 + place on rank 8/1 (no piece type); castling = the king and rook square transitions; en passant = two square events (the captured pawn's square goes empty), nothing more. Mobile re-derives geometry; the firmware must **not** try to be clever. (Cross-reference the SYNC-comment lesson: move geometry is mirrored in `SequenceInterpreter.footprintOf` ↔ `ChessRules.applyMove`; firmware adding a third interpretation would silently diverge.)
4. **Buttons are bare events, no turn validation.** Emit `BUTTON_EVENT(white|black)` on press; mobile ignores a wrong-side press. The firmware does not know whose turn it is.
5. **Diagnostic mode ≈10 Hz snapshots** in addition to `SQUARE_EVENT`s (emulator uses 100 ms; rate is a target, modest variance acceptable) ([EmulatedBoard.kt:223-248](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoard.kt:223)).
6. **Disconnect**: stop emitting events, ignore buttons, keep sensing internally; the next reconnect snapshot reflects any offline change (contract §1.7). No persistence across power cycles (FR-FW-013).
7. **Single central**; bond on first connection (contract §1.1).

### E. ESP-IDF BLE stack recommendation & architecture (external research, checked 2026-06-19)

**Recommendation: NimBLE** (`CONFIG_BT_NIMBLE_ENABLED=y`), not Bluedroid. Espressif's BLE overview states NimBLE "requires less heap and flash size"; Bluedroid is only for Classic BT, which this BLE-only device never uses. NimBLE is portable across classic/S3/C3/C6, so a later variant swap (OQ-1) doesn't invalidate it. **`sdkconfig.defaults` must gain** `CONFIG_BT_ENABLED=y` + `CONFIG_BT_NIMBLE_ENABLED=y` (the seed is BLE-free today; edit the committed seed per `firmware/AGENTS.md`, never the generated files).

- **GATT shape**: one primary service (128-bit UUID), `board_event` = `BLE_GATT_CHR_F_NOTIFY` (NimBLE auto-adds the 0x2902 CCCD), `mobile_command` = `BLE_GATT_CHR_F_WRITE`. Canonical examples: **`bleprph`** (peripheral reference: advertising, GATT DB, writes, bonding), **`blehr`** (timer-driven notify-on-interval), **`NimBLE_GATT_Server`** (annotated get-started). Notify with `ble_gatts_notify_custom(conn, handle, om)`; receive writes in the access callback (`BLE_GATT_ACCESS_OP_WRITE_CHR`), flatten the mbuf, switch on byte 0.
- **Advertising**: derive the name from the base MAC (`esp_read_mac(..., ESP_MAC_BT)` or `ble_hs_id_copy_addr`), format `SmartChessboard-XXXX` (last 4 hex). **A 128-bit UUID (16 B) + a ~16-char name will not both fit the 31-byte legacy advertisement** — put the service UUID in the advertisement and the name in the **scan response** (or shorten the name). Re-arm advertising in the GAP `DISCONNECT` event (FR-FW-012).
- **Single central + bonding**: undirected connectable advertising auto-stops on connect — simply don't restart until `DISCONNECT`; `BLE_MAX_CONNECTIONS=1` enforces one. "Just works" pairing: `sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT`, `sm_bonding=1`, `sm_sc=1`; **bond storage needs `nvs_flash_init()`**.
- **MTU**: a ≤20-byte payload fits the default 23-byte ATT MTU (3-byte header) — the largest frame here is 9 bytes, so **no MTU negotiation is needed**. Notification length is set by the mbuf, so one characteristic carries variable-length frames.
- **FreeRTOS coexistence**: NimBLE runs its own controller + host tasks. Keep the existing ~50 Hz scan/debounce loop as a **producer** that posts a small `{tag, payload}` struct to a FreeRTOS queue when `stable` changes (never call BLE APIs from the scan context). The host context **drains the queue and notifies**, gated on the CCCD-subscribe flag. Drive periodic `DEVICE_STATUS` with a ~30 s auto-reload `xTimer`; drive the ~10 Hz diagnostic snapshot with a ~100 ms `xTimer` started on `SET_MODE→diagnostic` and stopped on `SET_MODE→game`. The ≤100 ms latency NFR is comfortable (≈80 ms debounce + sub-ms queue→notify hop).
- **UUIDs**: there is no SIG 16-bit UUID for this — mint v4 128-bit UUIDs (`uuidgen`), one base + a 16-bit offset slot for service/`board_event`/`mobile_command`, declared with `BLE_UUID128_INIT(...)`. **Record all three in `contract-surfaces.md` §1.2** (the load-bearing-names registry) so mobile and firmware share identical bytes. This resolves OQ-5 — the one firmware unknown that touches a shared contract surface.
- **Symbol caveat**: confirm `ble_gatts_notify_custom` against the actually-installed ESP-IDF headers (very old NimBLE used `ble_gattc_notify_custom`).

### F. Testing strategy (no hardware)

PlatformIO supports host-side unit tests via a **`native`** environment + Unity (`pio test -e native`), separate from the device build. Plan:
1. Extract hardware-free logic — message encode/decode, `index=file+8*rank` packing, debounce state machine — into **`firmware/lib/`** (e.g. `lib/board_protocol/`, `lib/debounce/`), with **no ESP-IDF/NimBLE/GPIO includes**. `src/main.cpp` keeps only hardware glue.
2. Add `[env:native]` (`platform = native`, `test_framework = unity`) alongside `[env:esp32dev]`. Default `test_build_src=false` keeps `main.cpp` out of the host binary.
3. Layout `test/test_protocol/`, `test/test_debounce/`. **Assert the same golden byte vectors** that `BoardWireCodecTest.kt` uses — a cross-language oracle directly attacking the contract-drift risk.

This delivers F-03's "verified by firmware unit tests" without a board; BLE/GPIO integration stays in the device build and is exercised end-to-end at S-09.

## Code References

- [firmware/src/main.cpp:25-27](firmware/src/main.cpp:25) — scan/debounce constants (`kScanIntervalMs=20`, `kStableScans=4`).
- [firmware/src/main.cpp:31-33](firmware/src/main.cpp:31) — `square_index()` = `file + 8*rank` (software-calibratable orientation).
- [firmware/src/main.cpp:65-76](firmware/src/main.cpp:65) — `scan_matrix()` → `uint64_t` occupancy.
- [firmware/src/main.cpp:114-145](firmware/src/main.cpp:114) — debounce superloop (reuse; becomes the queue producer).
- [firmware/src/pins.h:37-58](firmware/src/pins.h:37) — flashed pin map (rows/cols); buttons to be added on GPIO22/23.
- [firmware/platformio.ini:7-10](firmware/platformio.ini:7) — `[env:esp32dev]` (espressif32 / esp32dev / espidf).
- [firmware/sdkconfig.defaults](firmware/sdkconfig.defaults) — BLE-free seed; add `CONFIG_BT_ENABLED`/`CONFIG_BT_NIMBLE_ENABLED`.
- [BoardWireCodec.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/protocol/BoardWireCodec.kt) — **the byte-for-byte reference** for all 7 messages.
- `BoardWireCodecTest.kt` (commonTest, same package) — golden byte vectors; the firmware host-test oracle. Carries a `TODO(F-02, manual gate 2.3/2.4)` — see Open Questions.
- [BoardConnection.kt:18-27](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/BoardConnection.kt:18) — the transport-agnostic port (objects at the seam).
- [BoardEvents.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/BoardEvents.kt) / [BoardCommand.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/BoardCommand.kt) — typed event/command model.
- [EmulatedBoard.kt:84-93,146-166,223-248](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoard.kt:84) — connect burst, command semantics, diagnostic 10 Hz, round-trip-through-bytes — the behavioral spec.
- [SequenceInterpreter.kt:37-113](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/SequenceInterpreter.kt:37) — consumes ordered `SquareEvent`s, produces one legal move (why per-transition streaming is mandatory).
- [Square.kt:1-19](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/Square.kt:1) — square index authority.
- [Occupancy.kt:17-20](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/Occupancy.kt:17) — bit-63 sign caveat (firmware: `uint64_t`).
- [PlatformCapabilities.kt:12](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/platform/PlatformCapabilities.kt:12) + `.android/.ios/.wasmJs` actuals — `supportsPhysicalBoard` (true on Android/iOS, false on web).
- [docs/reference/contract-surfaces.md:58-180](docs/reference/contract-surfaces.md:58) — §1 BLE protocol (the frozen interface; §1.2 UUIDs to be filled by F-03).

## Architecture Insights

- **Objects at the seam, bytes on the wire.** `BoardConnection` deliberately exposes typed `BoardEvent`/`BoardCommand`; the bytes live inside each implementation. The emulator and the future BLE adapter are interchangeable behind this port — which is exactly why the firmware can be validated against the emulator's stream.
- **The codec is the contract.** `BoardWireCodec.kt` equals `contract-surfaces.md` §1.3/§1.4 "to the bit" and is more precise about the corners (signedness, bit order, exact frame lengths, reserved-bit rejection). The prose doc remains the ultimate spec, but the codec is the executable oracle.
- **Event-stream, not snapshot-diff, is the move primitive.** The design depends on real-time per-transition `SQUARE_EVENT`s; snapshots exist for connect/reconnect verification and diagnostics only. This is the single most important firmware behavior to get right.
- **Reuse over rewrite.** Scan + debounce + index already exist and are hardware-verified; F-03 is overwhelmingly *additive* (BLE, encoders, buttons, mode, lifecycle). The cleanest seam is: keep the scan loop, refactor its pure pieces into `lib/`, and add a BLE layer that consumes a queue.
- **Producer/consumer decoupling** (scan task → queue → BLE host notify) keeps scan timing clean and satisfies the latency NFR with margin.

## Historical Context (from prior changes)

- `context/changes/reed-board-emulator/` (F-02) — built the mobile-side codec + emulator. The **2026-06-16 `BOARD_SNAPSHOT` byte-layout clarification** (byte `i` bit `j` LSB-first = square `i*8+j`) originated here; golden start-position vector `01 FF FF 00 00 00 00 FF FF` pins byte order. `manual-verification.md` holds the golden-frame manual gate.
- `context/changes/physical-capture-emulated/` (S-06) — built `SequenceInterpreter` and proved physical-mode capture end-to-end on three targets (2026-06-19) against the emulator. Establishes the "dumb board / event-stream" mandate and the `footprintOf` ↔ `applyMove` SYNC-comment coupling. Impl-review finding **F1** (pending): the `BoardConnection` singleton is never `disconnect()`-ed on teardown — a *mobile-side* concern for the S-09 adapter, not firmware, but worth tracking.
- `context/foundation/lessons.md` — "Engine move-geometry mirrored outside `domain/chess` must be SYNC-commented" (firmware must not add a third move interpretation); "Web target is digital-only" (firmware/BLE never touches web).

## Related Research

- No prior `research.md` exists for this change (first one). Related artifacts: `context/changes/reed-board-emulator/research.md` (mobile-side protocol research) and `context/changes/physical-capture-emulated/plan.md` (interpreter design). This document is the firmware-side counterpart.

## Open Decisions (consolidated for `/10x-plan`)

| # | Decision | Recommendation / status | Blocks F-03? |
| --- | --- | --- | --- |
| D1 | BLE host stack | **NimBLE** (`CONFIG_BT_NIMBLE_ENABLED`); add to `sdkconfig.defaults` | Decide in plan |
| D2 | GATT UUIDs (service + 2 chars) | Mint v4 128-bit (base+offset); **write back to `contract-surfaces.md` §1.2** (resolves OQ-5) | Decide in plan; cross-component |
| D3 | Button GPIOs (FR-FW-007) | **GPIO22 (white) + GPIO23 (black)** on WROOM-32; add to `pins.h` (additive) + debounce; revisit if variant changes | Decide in plan |
| D4 | ESP32 variant (OQ-1) | `esp32dev` placeholder OK; NimBLE portable to S3/C3/C6 | No (non-blocking) |
| D5 | Power source (OQ-2) | USB-only for MVP; `DEVICE_STATUS.battery_pct` is nice-to-have — pick a sentinel/constant if USB | No |
| D6 | Toolchain (OQ-4) | **Effectively resolved**: ESP-IDF (already in `platformio.ini`) | No |
| D7 | BLE↔scan architecture | Scan task → FreeRTOS queue → BLE host notify; `xTimer` for status (~30 s) + diagnostic snapshot (~100 ms) | Design in plan |
| D8 | Firmware version reported in `DEVICE_STATUS` | Pick a starting `major.minor.patch` (e.g. `0.1.0`) | Decide in plan |
| D9 | Host test harness | `firmware/lib/` extraction + `[env:native]` + Unity; assert the Kotlin golden vectors | Design in plan |

## Open Questions / Risks

1. **Contract drift (the named F-03 risk).** Mitigation is concrete: build the byte format directly against `BoardWireCodec.kt` and assert the **same golden vectors** in `firmware/lib/` host tests.
2. **Golden vectors carry an unresolved manual cross-check** (`BoardWireCodecTest.kt` `TODO(F-02, manual gate 2.3/2.4)`; worksheet `context/changes/reed-board-emulator/manual-verification.md`). They look self-consistent against the contract; if any single-byte discrepancy appears during firmware work, that gate is where it would have slipped — reconcile against `contract-surfaces.md` §1.3/§1.4 prose (the ultimate spec) and close the gate.
3. **`firmware/AGENTS.md` still says PARKED** — predates the F-03 greenlight; refers to hardware bringup. The doc should be updated (firmware *software* greenlit; only the hardware reed-matrix repair stays parked and gates S-09). Worth a small docs edit when F-03 work starts.
4. **Buttons are unbuilt hardware** — FR-FW-007 needs two physical buttons added to the prototype and debounced in firmware; the emulator models "confirm" but there is no button wiring yet. This is the one place F-03 introduces new hardware on the existing board.
5. **Advertising packet budget** — 128-bit UUID + full name overflow 31 bytes; split name into the scan response (not a blocker, but the plan must specify it).
6. **`ble_gatts_notify_custom` symbol** — verify against the installed ESP-IDF version (rename history from `ble_gattc_notify_custom`).
