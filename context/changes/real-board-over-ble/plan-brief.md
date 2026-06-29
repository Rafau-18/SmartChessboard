# Real Board over BLE (S-09) — Plan Brief

> Full plan: `context/changes/real-board-over-ble/plan.md`
> Research: `context/changes/real-board-over-ble/research.md`

## What & Why

Let a user play the physical-mode flow against the *actual* reed board over BLE — connect, receive live
board events, confirm with the side buttons, and persist accepted moves on real hardware. Everything
above the wire was built for this across F-02/S-06/S-07/S-08 and the firmware (F-03) is on-hardware
proven; S-09 fills in the one missing piece, the real mobile BLE transport.

## Starting Point

The `BoardConnection` port, the firmware-equal `BoardWireCodec`, and the `SnapshotReceived`
reconnect-reconcile seam already exist; today the only `BoardConnection` is `EmulatedBoard` bound in DI.
The firmware advertises/bonds/streams correctly but its characteristics are plaintext (`NOTIFY`/`WRITE`,
no `_ENC`). Kotlin is 2.4.0 (Kable's floor); Kable is not yet in the catalog.

## Desired End State

The user opens physical mode, picks the board from a scan list, the OS pairing prompt appears, the link
bonds **encrypted**, and play begins against real reeds. A corner dot per square mirrors the live matrix
before each confirm. Range loss pauses acceptance with a "reconnecting" banner; on return the app
auto-reconnects, re-snapshots, and auto-resumes (match) or opens the reed grid (mismatch). The emulator
is gone from production DI. Proven by the full automated suite (Android + iOS + web) and a blocking
on-hardware acceptance pass on both platforms.

## Key Decisions Made

| Decision                         | Choice                                    | Why (1 sentence)                                                              | Source   |
| -------------------------------- | ----------------------------------------- | ---------------------------------------------------------------------------- | -------- |
| BLE library                      | Kable 0.43.1                              | Flow-native (1:1 with the port), exact Kotlin-2.4.0 match, `observe` auto-resubscribe. | Research |
| iOS bonding                      | Force encryption (firmware `_ENC`)        | iOS only bonds on first access to an encryption-required characteristic.      | Research |
| iOS background                   | Foreground-first                          | No state restoration / `bluetooth-central` in MVP.                            | Research |
| Firmware delta placement         | Inside S-09                               | The encrypted bond is only fully provable through the app.                    | Plan     |
| Adapter ↔ screen lifecycle       | One object (port + `BoardTransport`)      | Smallest, proven deviation — mirrors `EmulatedBoard`'s off-port driver.       | Plan     |
| Connection-screen scope          | Richer                                     | RSSI, forget/re-pair, manual retry, full error taxonomy, auto-connect.        | Plan     |
| On-hardware acceptance           | Hard blocking phase                       | Not complete/archived until physically passed on Android **and** iOS.         | Plan     |
| Live matrix overlay              | Plain dots, always-on (toggle)            | Honest live mirror with no mid-move false alarms; visible before confirm.     | Plan     |

## Scope

**In scope:** Kable adapter (Android + iOS) behind the port + a `BoardTransport` driver surface; firmware
encryption delta + contract update; DI swap (emulator → test-only) with teardown; richer MVI connection
screen + permissions; `reconnectReconciling` reducer flag (FR-012); live matrix overlay; blocking
on-hardware acceptance on both platforms.

**Out of scope:** iOS background/state-restoration; web BLE; new wire messages; multi-board/OTA/on-board
LEDs; authenticated (MITM-safe) pairing; emulator UI / no-hardware debug mode; transistor button buffer;
mismatch-coloured overlay (at-rest tinting already lives in `ReedDiagnosticsGrid`).

## Architecture / Approach

Below the stable `BoardConnection` port the transport is a leaf detail, so the bulk is a new
`data/board/ble/` Kable adapter that implements both the port (for the play screen) and a new
`BoardTransport` driver interface (scan/connect/disconnect, for the connection screen) — one object, two
faces, like the emulator. Above the port, only two small presentation additions (connection MVI screen,
matrix overlay) and exactly one new reducer flag land. The firmware/contract encryption delta rides
inside the slice. iOS real-radio behaviour is unprovable off-device, so the iOS simulator suite proves
only the mapping/state seam; real radio is the hardware gate.

## Phases at a Glance

| Phase                                  | What it delivers                                          | Key risk                                            |
| -------------------------------------- | -------------------------------------------------------- | --------------------------------------------------- |
| 1. Dependencies + UUIDs                | Kable in mobile source sets; §1.2 UUID constant          | Kable↔Kotlin/AGP version coupling                   |
| 2. Firmware encryption + contract      | `_ENC` chars; contract/PRD update; re-flash              | Wrong GATT flags break subscribe                    |
| 3. BLE adapter                         | Kable adapter (port + `BoardTransport`), codec mapping   | Kotlin/Native divergence; no off-device radio test  |
| 4. DI swap + teardown                  | Real adapter bound; `TODO(S-09)` leak fixed; emulator test-only | Regression in existing physical-flow tests   |
| 5. Connection screen (MVI) + perms     | Scan/list/pair/status, errors, auto-connect; perms       | Large phase; Android API 24 vs 31+ permission split |
| 6. Reconnect-reconcile flag (FR-012)   | `reconnectReconciling` gate + reducer/E2E tests          | ~10 Hz diag stream hangs `advanceUntilIdle`         |
| 7. Live matrix overlay                 | `sensedOccupancy` + plain always-on board dots           | Folding into `latestOccupancy` would break restore  |
| 8. On-hardware acceptance (BLOCKING)   | App-driven gate on real board, both platforms            | Hardware/session availability blocks completion     |

**Prerequisites:** repaired board powered (common ground + DGT clock on); an Android device and an iOS
device for the hardware gate; F-03 firmware flashable.
**Estimated effort:** ~5–7 sessions across 8 phases (firmware + adapter + a sizable connection screen are
the heavy ones; the reducer flag and overlay are small).

## Open Risks & Assumptions

- iOS reconnect/bonding correctness through Kable is the least-documented area; the port keeps a swap to
  `expect`/`actual` or Blue-Falcon cheap if it bites.
- The encryption flag is a one-way step-up — once flashed, plaintext-only centrals can't subscribe (the
  old app isn't deployed, so no field-compat concern).
- Real-radio behaviour can't be CI-tested on iOS; the blocking hardware gate is the only proof.

## Success Criteria (Summary)

- A full physical game plays on the real board over an encrypted BLE link, on Android and iOS.
- Disconnect → reconnect auto-resumes on a board match and routes a mismatch into the reed-grid restore,
  with no accepted move lost across the disconnect window.
- The live matrix overlay tracks the physical reeds in real time before each confirm.
