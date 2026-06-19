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

Status: **PENDING human spot-check** (recorded 2026-06-19).

> On-hardware manual checks for Phases 2–3 (advertising, bonding, on-subscribe burst,
> live square/button events, diagnostic stream, reconnect snapshot, malformed-write
> ignore) will be appended here when those phases land, and run against the
> partially-working board + temporary buttons at end-of-slice.
