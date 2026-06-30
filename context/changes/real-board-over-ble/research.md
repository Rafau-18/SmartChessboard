---
date: 2026-06-29T21:19:48+0200
researcher: Rafał Urbaniak
git_commit: e01239abb1ab60ed1e6e35f9a77bcf24491a9802
branch: claude/nifty-napier-4e2cf4
repository: smartchessboard (bitbucket: <user>/smartchessboard)
topic: "Real board over BLE (S-09) — driving the physical-mode flow from the real reed board over the §1 BLE contract"
tags: [research, codebase, mobile, ble, kable, kmp, physical-board, contract-surfaces, reconnect, fr-012, s-09]
status: complete
last_updated: 2026-06-29
last_updated_by: Rafał Urbaniak
last_updated_note: "Follow-ups: Blue-Falcon evaluated (does not change Kable-first) + Kotlin-version gate resolved (2.4.0) + remaining Open Questions locked (1B force-encryption, 3 full MVI connect screen, 4A foreground-first, 6 hardware ready/ADC=MVP, emulator test-only)."
---

# Research: Real board over BLE (S-09)

**Date**: 2026-06-29T21:19:48+0200
**Researcher**: Rafał Urbaniak
**Git Commit**: e01239abb1ab60ed1e6e35f9a77bcf24491a9802
**Branch**: claude/nifty-napier-4e2cf4
**Repository**: smartchessboard (Bitbucket `<user>/smartchessboard`; HEAD not pushed at this commit, so no public permalinks — paths below are repo-relative)

## Research Question

How do we implement roadmap slice **S-09 (`real-board-over-ble`)** — let a user play the physical-mode flow against the *actual* reed board over BLE (connect, receive live board events, confirm with the side buttons, save accepted moves into the canonical record on real hardware)? Specifically: what seam does the real BLE transport plug into, what already exists vs. what must be built, which BLE library to commit to (Kable vs. `expect`/`actual` platform-native), how the FR-012 disconnect/reconnect-reconcile (§1.7) is wired, and how the on-hardware acceptance is verified.

**Scope decisions taken at kickoff (2026-06-29):** (1) the BLE-library question is researched to a *decision-grade* bake-off including current external evidence; (2) **FR-012 reconnect/reconcile is in scope** for this slice (not deferred), because the seam was purpose-built for it.

## Summary

**S-09 is a transport-fill, not a protocol build.** The codebase was deliberately shaped for this slice across F-02/S-06/S-07/S-08, and the firmware half (F-03) is already on-hardware-verified. Concretely:

- **The seam exists.** Everything above the wire depends on one transport-agnostic port — `domain/board/BoardConnection` (`connectionState`, `events: SharedFlow<BoardEvent>`, `send(command)`). `EmulatedBoard` implements it today in `commonMain`; S-09 adds a *second* implementation (a real BLE adapter in `androidMain`/`iosMain`) and swaps the Koin binding. **Nothing above the port changes** — ViewModel, reducer, interpreter, and every existing test are transport-blind.
- **The byte codec exists and is firmware-proven.** `data/board/protocol/BoardWireCodec` is a complete, total (`Decoded | Malformed`) encoder/decoder for all §1.3/§1.4 messages, byte-for-byte equal to the firmware's `board_protocol.cpp` (same golden literals in both test suites). **S-09 does not write a new codec** — the adapter calls `decodeEvent(bytes)` on each notification and `encodeCommand(cmd)` before each write.
- **The reconnect-reconcile seam exists.** S-08 built the `SnapshotReceived` board-match arm to clear both the S-07 recovery gate and the S-08 resume gate, and left an explicit `TODO` that **S-09/FR-012 sets its own gate flag in the `BoardConnected` arm and clears it through this same check**. Disconnect-pause (§1.7 "no move saved during the disconnect window") is already enforced via the derived `paused` flag.
- **The firmware matches the contract byte-for-byte** (no deviations) and gates 2.4–3.10 are confirmed on real hardware (2026-06-29, board `SmartChessboard-DA3A`, nRF Connect). S-09 inherits a proven peripheral; what's unproven is the *mobile central* (scan/connect/bond/subscribe + reconnect wired to the seam).
- **Recommended library: Kable-first** (`com.juul.kable:kable-core` 0.43.1) — iOS is a first-class target and `observe()` auto-resubscribes across reconnect, which is exactly the fiddly part of §1.7. The `BoardConnection` port isolates the choice, so a later swap to `expect`/`actual` is cheap. (Note: the Jetpack `androidx.bluetooth` "`BluetoothLe`" named loosely in `tech-stack.md` is only `1.0.0-alpha02`; the native fallback on Android means classic `BluetoothGatt`, not that wrapper.)

**The real work of S-09:** a new `data/board/ble/` adapter (scan by service UUID + name, connect, subscribe, map notify↔codec, surface `CONNECTED`/`DISCONNECTED`, auto-reconnect, teardown), one new reducer gate flag (`reconnectReconciling`) mirroring `awaitingResumeConfirm`, a connection/permissions UX, the platform permission/Info.plist setup, and an on-hardware acceptance pass that reuses the nRF-Connect gate structure *through the app*.

**One genuine cross-cutting risk surfaced:** iOS has no bonding API — it pairs only when the app touches an *encryption-required* characteristic. The firmware's characteristics are declared `NOTIFY`/`WRITE` (not `_ENC`), so an iOS app may connect/subscribe in plaintext and never bond. §1.8 already accepts trust-on-first-pair for MVP, so this is likely fine, but the contract's "bonded after first connection" (§1.1) wording and the iOS reconnect path need a decision in `/10x-plan` (see Open Questions).

## Detailed Findings

### A. The board seam — `BoardConnection` (the one port S-09 implements)

The whole physical stack depends on a single transport-agnostic interface:

`SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/BoardConnection.kt:18-33`

```kotlin
interface BoardConnection {
    val connectionState: StateFlow<BoardConnectionState>   // CONNECTED / DISCONNECTED
    val events: SharedFlow<BoardEvent>                     // board → mobile, hot, replay = 0
    suspend fun send(command: BoardCommand)               // mobile → board; throws if disconnected
}
enum class BoardConnectionState { CONNECTED, DISCONNECTED }
```

- The port models **only the connected-consumer view** — state + event stream + command send. It deliberately **excludes transport lifecycle** (scanning, pairing, MTU); those belong to the concrete adapter. The class doc says verbatim: *"The emulator implements it now (F-02); the BLE adapter re-implements it over Kable in S-09."*
- **No `connect()`/`disconnect()` on the port.** The S-09 adapter owns its own scan/connect/bond lifecycle internally and surfaces only the `CONNECTED`/`DISCONNECTED` transitions.

**The event/command vocabulary is final** (no changes needed):
- `domain/board/BoardEvents.kt:11-80` — `BoardEvent` sealed: `SquareEvent(square, type)` (tag 0x02; `require(square in 0..63)` at `:25`), `ButtonEvent(button)` (0x03), `BoardSnapshot(occupancy: Long)` (0x01; `isOccupied(sq)` at `:46-49`), `DeviceStatus(batteryPct, firmwareVersion, uptimeSeconds)` (0x04). Enums `SquareEventType { LIFT, PLACE }`, `BoardButton { WHITE, BLACK }`, `FirmwareVersion(major, minor, patch)`.
- `domain/board/BoardCommand.kt:8-25` — `BoardCommand` sealed: `SetMode(mode)` (0x81), `RequestSnapshot` (0x82), `RequestStatus` (0x83); `BoardMode { GAME, DIAGNOSTIC }`.

**`EmulatedBoard` is the reference implementation** (`data/board/emulator/EmulatedBoard.kt:56-253`, in `commonMain` since S-06): single `var occupancy: Long` bitmap (bit N = square N, a1 = bit 0), a hot `MutableSharedFlow<BoardEvent>(replay = 0, extraBufferCapacity = 64)`, a driver-surface `connect()`/`disconnect()` (not on the port), and `send()` dispatching commands. Crucially, **every emitted event round-trips through the codec** (`emitEvent`/`send` at `:150-201`: `encode → §1.3 bytes → decode → typed`), so the emulated stream is byte-identical to the firmware's — the real adapter inherits that fidelity for free.

**Web is excluded structurally (belt-and-suspenders):**
- `platform/PlatformCapabilities.kt:12` — `expect val supportsPhysicalBoard: Boolean`; actuals: android `:4 = true`, ios `:4 = true`, **wasmJs `:4 = false`** ("Web is digital-only: Web Bluetooth is too inconsistent cross-browser"). Gates the New-game mode picker and History routing.
- DI reinforces it: `BoardConnection` and `PhysicalPlayViewModel` are bound only in the Android/iOS Koin modules; `wasmJs` has no board binding and **must stay that way** (consistent with `lessons.md` "Web target is digital-only — no BLE").

**The DI swap point** (`di/PlatformModule.kt:9` `expect val platformModule`, aggregated in `di/AppModules.kt:60`):
- Android `di/PlatformModule.android.kt:38-49` and iOS `di/PlatformModule.ios.kt:27-38` both bind:
  ```kotlin
  single<BoardConnection> { EmulatedBoard(scope = ...).also { b -> scope.launch { b.connect() } } }
  viewModel { (gameId: String) -> PhysicalPlayViewModel(gameId, get(), get(), boardConnection = get()) }
  ```
- **S-09 replaces the `single<BoardConnection>` body with the real BLE adapter in both modules** (the adapter classes live in `androidMain`/`iosMain`). The `viewModel` binding is untouched (it resolves `BoardConnection` via `get()`).
- **Both modules carry an explicit `TODO(S-09)`** (android `:34-37`, ios `:23-26`): the emulator's connect-on-bind scope is never cancelled / `disconnect()` never called — harmless for a process-lifetime emulator, but a real BLE adapter on this exact shape **leaks the connection**. Prescribed fix: cancel on teardown, e.g. Koin `onClose { (it as? ...)?.disconnect() }`. **Lifecycle/teardown is a required S-09 deliverable, not an afterthought.**

### B. The wire codec already exists — no new codec for S-09

`data/board/protocol/BoardWireCodec.kt:52-283` — a pure, stateless `object`; file doc: *"the decoder is the mobile side (reused verbatim by the S-09 BLE adapter)."* Four entry points:
- `decodeEvent(bytes): EventDecodeResult` (`:126`) ← **the BLE RX path** (each `board_event` notification)
- `encodeCommand(command): ByteArray` (`:211`) ← **the BLE TX path** (each `mobile_command` write)
- `encodeEvent` (`:82`) / `decodeCommand` (`:231`) — the board side, used by the emulator and as the firmware reference.

Decoding is **total**: results are sealed `Decoded | Malformed(bytes, reason)` (`:20-44`) — never throws on hostile input (truncated notifications, reserved tags `0x84–0x9F`). The doc ties this directly to S-09 robustness against truncated BLE notifications. Layout details all present and tested:
- BOARD_SNAPSHOT 8-byte little-endian-by-square-index, a1 = byte0 bit0 (encode `:87-89`, decode `:144-147`, `SNAPSHOT_FRAME_SIZE = 9` at `:76`).
- SQUARE_EVENT square low-6 + event high-2 (`SQUARE_MASK 0x3F`, `EVENT_SHIFT 6`, lift `0b00`/place `0b01`; `:65-68`, `:158-171`).
- BUTTON_EVENT, DEVICE_STATUS (`:102-123` / `:174-209`); all tags `:53-62`.

Supporting: `domain/board/Occupancy.kt:21-27` `fun Position.toOccupancy(): Long` (builds the expected-position bitmap for the reconnect diff). **Sign-bit warning** (`:16-19`): h8 = bit 63 sets `Long.MIN_VALUE`; always test `(bits and (1L shl n)) != 0L`, never `bits > 0L`.

Tests already in place: `commonTest/.../data/board/protocol/BoardWireCodecTest.kt` (round-trips + malformed) and `EmulatedBoardEndToEndTest.kt:28` (comment: *"verification here transfers to the real board (S-09)"*).

### C. The physical MVI core & the reconnect-reconcile seam

The headless MVI core lives in `presentation/physical/` (three files: `PhysicalPlayContract.kt`, `PhysicalPlayReducer.kt`, `PhysicalPlayViewModel.kt`). Board events enter as intents via `BoardEvent.toMsg()` (`PhysicalPlayViewModel.kt:216-236`): `SquareEvent → SquareLifted/SquarePlaced`, `ButtonEvent → ConfirmPressed`, `BoardSnapshot → SnapshotReceived`, `DeviceStatus → null` (ignored); `connectionState` maps to `BoardConnected`/`BoardDisconnected` (`:64-74`).

**Move-acceptance gating** (`PhysicalPlayContract.kt`):
```kotlin
val paused get() = connectionState == DISCONNECTED                       // :98
val acceptanceBlocked get() = paused || recovering || awaitingResumeConfirm  // :104
```
| Flag | Meaning | Set | Cleared |
|---|---|---|---|
| `paused` (`:98`) | board unreachable | `BoardDisconnected` arm (`Reducer:120-122`) | `BoardConnected` arm (`Reducer:103-118`) |
| `recovering` (`:80`) | S-07 reject awaits restore | `confirm()` on Ambiguous/Illegal (`Reducer:347-374`) | `SnapshotReceived` `restoreVerified` (`Reducer:142`) |
| `awaitingResumeConfirm` (`:91`) | S-08 resume awaits board confirm | `Loaded` arm when in-progress (`Reducer:65`) | `SnapshotReceived` `resumeVerified` (`Reducer:143`) |

(`pendingPromotion` is **not** in `acceptanceBlocked`; it blocks `confirm()` specifically via `PROMOTION_REQUIRED` at `Reducer:329`.)

**The shared `SnapshotReceived` arm** (`PhysicalPlayReducer.kt:124-152`) — the seam S-09 reuses:
```kotlin
is PhysicalMsg.SnapshotReceived -> {
    val atRest = state.eventsSinceConfirm.isEmpty()
    val matchesExpected = msg.occupancy == state.position.toOccupancy()   // :129
    val restoreVerified = state.recovering && matchesExpected
    val resumeVerified  = state.awaitingResumeConfirm && matchesExpected
    val next = state.copy(
        latestOccupancy = msg.occupancy,
        setupMismatch = if (atRest) !matchesExpected else state.setupMismatch,
        recovering = if (restoreVerified) false else state.recovering,
        awaitingResumeConfirm = if (resumeVerified) false else state.awaitingResumeConfirm,
        ... )
    ...
}
```
- `BoardConnected` arm (`PhysicalPlayReducer.kt:103-118`) sets `connectionState = CONNECTED` and emits `Send(RequestSnapshot)` (+ re-arms `SetMode(DIAGNOSTIC)` if the grid is open). Inline `TODO` at `:107-108`: *"S-09/FR-012 routes BLE reconnect-reconcile through the shared SnapshotReceived board-match seam below — set its gate flag here, clear it there."*
- Contract text (`docs/reference/contract-surfaces.md:615-629`) confirms: *"S-09 sets its own gate flag in the `BoardConnected` arm and clears it through this same check."*

**Exact S-09 edits to add reconnect-reconcile (mirrors `awaitingResumeConfirm`):**
1. New flag `reconnectReconciling: Boolean` on `PhysicalPlayState.Playing` (beside `awaitingResumeConfirm`, `Contract:91`); OR into `acceptanceBlocked` (`Contract:104`).
2. **Set** it in the `BoardConnected` arm (`Reducer:103`) — alongside the `RequestSnapshot` it already emits, so the requested snapshot reconciles it.
3. **Clear** it in `SnapshotReceived` with `val reconnectVerified = state.reconnectReconciling && matchesExpected` → `reconnectReconciling = if (reconnectVerified) false else state.reconnectReconciling` (in the `state.copy` block, parallel to `resumeVerified`).
4. Mismatch auto-grid + restore loop come for free: `setupMismatch = !matchesExpected` → `diagnosticsVisible` → `effectsForModeChange` fires `SetMode(DIAGNOSTIC)` (`Reducer:424-440`).
5. Reset on accepted move: add the flag to the `commit()` reset block (`Reducer:404-407`).
6. Suppress sequence-building while gated: add to the `accumulate()` guard (`Reducer:290`) and the `confirm()` gate (`Reducer:325`).

**Disconnect-pause needs no reducer change** — `paused` (`Contract:98`), set by `BoardDisconnected` (`Reducer:120-122`), enforced by the `confirm()` guard `if (state.result != null || state.paused) return` (`Reducer:321`). It already satisfies §1.7's "no move saved during the disconnect window" for the emulator. The two halves: **`paused` covers the down-window; the new `reconnectReconciling` covers the verify-window after `CONNECTED` but before the post-reconnect snapshot confirms.**

**ViewModel ↔ board wiring** (`PhysicalPlayViewModel.kt`): three collectors started in `init` *before* `load()` (so the on-connect burst isn't missed) — `boardConnection.connectionState` (`:64-74`), `boardConnection.events` (`:216-236`), `autoSaver.syncPending`. Commands go out via `PhysicalEffect.Send → boardConnection.send(cmd)` (`:191-201`), swallowing the disconnect throw. **Nothing here changes for a real board** — the adapter implements the same port. The §6.2 journal write is gated in the `commitMove()` effect (`:158-177`: `validate → san → autoSaver.acceptMove`), and the state only advances in the reducer's `commit()` on `MoveCommitted` feedback — so a failed save can never display an accepted move.

### D. Firmware F-03 wire reality (what the real board actually does)

**Verdict: byte-for-byte match with contract §1; zero deviations.** Source-confirmed and cross-checked against the on-hardware log.

- **Advertising / GATT** (`firmware/src/ble_service.cpp`): local name `SmartChessboard-%02X%02X` from the last 2 BT-MAC bytes (`:405-410`) → live unit `SmartChessboard-DA3A`; **name in the scan response, 128-bit service UUID in the advertisement** (`:230-257`). UUIDs declared LE via `BLE_UUID128_INIT` (`:71-82`) equal contract §1.2 exactly (service `787e0001…`, `board_event` notify `787e0002…`, `mobile_command` write `787e0003…`). CCCD `0x2902` auto-added by NimBLE (`:112,119`).
- **Bonding** (`:452-456`): `BLE_HS_IO_NO_INPUT_OUTPUT` (Just-Works, no PIN), `sm_bonding=1`, `sm_sc=1`, NVS-persisted (`ble_store_config_init()` `:470`, `CONFIG_BT_NIMBLE_NVS_PERSIST=y`). Single connection (`CONFIG_BT_NIMBLE_MAX_CONNECTIONS=1`); `REPEAT_PAIRING` drops the old bond and retries (`:324-332`). **The characteristics are declared `NOTIFY`/`WRITE`, not `_ENC`** — see the iOS-bonding Open Question.
- **Message encoding** (`firmware/lib/board_protocol/board_protocol.cpp`, a declared twin of `BoardWireCodec.kt`): snapshot 8-byte LE-by-square (`:54-62`), SQUARE_EVENT square-low-6+event-high-2 (`:64-71`), BUTTON_EVENT 0x00/0x01 (`:73-79`), DEVICE_STATUS battery+fw+uptime-u32-LE (`:81-94`); `decodeCommand` with strict length checks, unknown/reserved → Malformed (`:118-148`). Host test `firmware/test/test_protocol/test_protocol.cpp` asserts the same golden literals as the mobile `BoardWireCodecTest.kt` (`pio test -e native` 15/15).
- **On-connect burst fires on CCCD subscribe, NOT GAP connect** (`:299-318` → producer `main.cpp:281-284`): on subscribe, emit BOARD_SNAPSHOT then DEVICE_STATUS, start the ~30 s status timer. On GAP connect, mode resets to GAME and `s_subscribed = false` — **the central must (re)enable the CCCD on every connection.**
- **Periodic DEVICE_STATUS ~30 s** (`:64,311`); **diagnostic mode ~10 Hz** snapshots, additive to per-transition SQUARE_EVENTs (`:65,216-226`).
- **Offline-change → reconnect snapshot** (`main.cpp:237-246`): while disconnected the board keeps sensing but emits nothing; the next reconnect's burst snapshot reflects the new occupancy. **No individual SQUARE_EVENTs are buffered across a disconnect — reconcile is snapshot-based**, exactly what §1.7/§6.3 prescribe.
- **Fixed live values:** battery always `0x64` (100, USB-powered constant), fw `1.0.0` (patch byte `00`), uptime unsigned seconds. **Mobile must not model battery drain or assume a nonzero patch.**
- **No MTU negotiation needed** — largest frame is 9 bytes, inside the default 23-byte ATT MTU.
- **Bring-up specifics for the on-hardware test rig** (no wire-contract impact): matrix-scan inversion (columns driven LOW, rows read; `square_index = file + 8*rank` unchanged, `main.cpp:60-62,160-171`); **DGT-clock confirmation buttons on ADC1 GPIO34/35** with hysteresis + 4-scan debounce (`pins.h:72-73`, `main.cpp:54-55,138-151`) — *ADC-direct is the MVP-target read; the transistor buffer was dropped from MVP scope* (`HARDWARE.md:173-177`); requires **common ground + the DGT clock powered on** (`HARDWARE.md:143-181`). ⚠️ **Historical doc drift:** the F-03 `plan.md` cites GPIO22/23 for the buttons — the authoritative wiring is GPIO34/35 (`pins.h` + `HARDWARE.md`); `firmware/AGENTS.md` was corrected 2026-06-30. Strapping-pin watch: ROW6 on GPIO12 (`pins.h:24-26`) — first suspect if the board ever fails to boot/advertise.

### E. BLE library bake-off — recommendation: **Kable-first**

The contract maps almost 1:1 onto Kable primitives, and the `BoardConnection` port means the choice lives entirely *below* the seam — invisible to every existing test, cheap to swap later.

| Requirement (contract §) | **Kable 0.43.1** | **expect/actual platform-native** |
|---|---|---|
| Scan by service-UUID + name (§1.1–1.2) | **Pass** — `Scanner { filters }`, service+name on all platforms | **Pass** — Android `BluetoothLeScanner`+`ScanFilter`; iOS `scanForPeripherals` + name match. More boilerplate |
| Connect (§1.1) | **Pass** — `peripheral.connect()` suspends until ready | **Pass** — Android `connectGatt`; iOS `connect()`. Two callback→coroutine bridges |
| Bond / persist, no re-pair (§1.1) | **Partial** — relies on platform bonding (no cross-platform bond API exists); you write persistence/re-discover above the port | **Partial** — same mechanics, hand-written per platform (Android MAC-stable; iOS UUID via `retrievePeripherals`) |
| Notify-subscribe, CCCD 0x2902 (§1.2–1.3) | **Pass** — `observe(char): Flow`, **auto-resubscribes after reconnect** | **Pass** — manual `setNotifyValue`/CCCD write; you re-subscribe each reconnect yourself |
| Write `mobile_command` (§1.4) | **Pass** — `write(..., WriteType.*)` | **Pass** — `writeCharacteristic` / `writeValue` |
| Disconnect + auto-reconnect (§1.7) | **Pass(Android)/Partial** — Android `autoConnectIf{}`; iOS reconnect driven from state flow; `observe` resuming is the big win | **Partial** — fully hand-rolled both sides; most error-prone to write twice |
| iOS background / state restoration (§1.7) | **Partial** — `CentralManager.configure { stateRestoration = true }` exposed; you still set Info.plist + restore path | **Partial** — full control via `CBCentralManagerOptionRestoreIdentifierKey` + `willRestoreState:`; all hand-written |
| MTU (≤20B MVP) (§1.3) | **Pass** — default fine; `requestMtu()` (Android) if needed | **Pass** — identical outcome |
| KMP-native iOS maturity | **Pass** — iOS (iosArm64/X64) first-class, shipped 2026 | **Partial** — you are the maturity; Android Jetpack `BluetoothLe` is **alpha02** → use classic `BluetoothGatt` |
| Testing / fakeability | **Pass** — sits below `BoardConnection`; `EmulatedBoard` already fakes the port | **Pass** — identical; the seam already exists (the equalizer) |
| Maintenance / version | **Partial** — single-maintainer (JuulLabs), monthly cadence, **0.43.1 requires Kotlin 2.4.0+** | **Partial** — no third-party risk, but you own all BLE bugs + OEM quirks forever |

- **Version provenance:** `com.juul.kable:kable-core` **0.43.1**, published **2026-06-07** per Maven Central `maven-metadata.xml` (`lastUpdated 20260607065517`). 0.43.1 requires **Kotlin 2.4.0+** (ABI change). (Automated GitHub fetches emitted spurious "June 2024" dates — flagged as summarizer artifacts, not trusted.)
- **Recommendation:** adopt Kable behind a new `data/board/ble/` adapter implementing `BoardConnection`, bound via Koin in the Android+iOS platform modules. The single fiddly area — `observe` resuming across reconnect — is exactly what Kable handles, directly serving §1.7. The parts Kable *can't* abstract (iOS implicit bonding, background modes, identifier persistence) are **identical work under either path**, so going native buys no relief on the hardest items.
- **Biggest risk (Kable):** maintenance coupling — single maintainer, releases pinned to Kotlin ABI (0.43.1 ⇒ Kotlin 2.4.0). **Gate item: verify the project's current Kotlin version before committing.**
- **Biggest risk (native):** owning two BLE state machines forever; Android bonding/reconnect has documented OEM landmines (this project already hit OEM BLE-adjacent bugs — `lessons.md` OnePlus/OPPO Credential-Manager entry).
- **What would force a switch to expect/actual:** (a) iOS state-restoration / background-reconnect can't be driven correctly through Kable's abstraction (most likely trigger; least-documented Kable area); (b) a Kable↔Kotlin/AGP version deadlock; (c) `iosSimulatorArm64Test` exposes a Kotlin/Native behavior divergence upstream won't fix on MVP timeline. The migration is cheap because the port isolates it.

**Permissions & platform setup checklist:**
- **Android** (`androidApp` manifest + runtime): `BLUETOOTH_SCAN` (add `android:usesPermissionFlags="neverForLocation"`), `BLUETOOTH_CONNECT`; no `ACCESS_FINE_LOCATION` on Android 12+. For minSdk < 31 also legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION` with `maxSdkVersion`. Both BLE perms are **runtime** — request before scanning. `neverForLocation` is safe here (we filter by our custom service UUID).
- **iOS** (`iosMain` + Info.plist): **`NSBluetoothAlwaysUsageDescription`** (mandatory string, or first CB use crashes/rejects); **`UIBackgroundModes → bluetooth-central`** if reconnect must survive backgrounding (§1.7); state restoration via `CBCentralManagerOptionRestoreIdentifierKey` + `willRestoreState:` (native) or `CentralManager.configure { stateRestoration = true }` (Kable). Prove the adapter on `:shared:iosSimulatorArm64Test` (per `lessons.md` — JVM-pass proves nothing about Kotlin/Native).

### F. On-hardware verification handoff

**Proven on the real board 2026-06-29 (no need to re-prove the firmware side)** — nRF Connect gates against `SmartChessboard-DA3A`:
2.4 advertising (service in adv, name in scan-rsp) · 2.5 Just-Works bond surviving reconnect without re-pair · 2.6 on-subscribe SNAPSHOT→STATUS burst · 2.7 re-advertise after disconnect · 3.4 SQUARE_EVENT lift/place, correct index, never coalesced · 3.5 BUTTON_EVENT `03 00`/`03 01` on the DGT buttons · 3.6 SET_MODE toggling the ~10 Hz stream · 3.7 REQUEST_SNAPSHOT/REQUEST_STATUS single-frame responses · 3.8 ~30 s periodic DEVICE_STATUS, monotonic uptime · 3.9 reconnect snapshot reflecting an offline change · 3.10 malformed/reserved writes ignored without dropping the link.

**What S-09 must prove (mobile↔board — untested by nRF Connect, which is a generic GATT client, not the app):**
1. The mobile BLE adapter decodes the real firmware's notifications into the same typed events the emulator produces (transport + subscription lifecycle on a phone; the *codec* is already proven equal).
2. Scan/subscribe/pair/bond from the app's BLE central against this exact GATT layout and Just-Works bonding.
3. Reconnect-reconcile wired to the `SnapshotReceived` seam: on reconnect, `REQUEST_SNAPSHOT` → compare to PGN-derived expected → auto-resume on match / open reed grid on mismatch.
4. Promotion / button-gating (§1.5) over real BUTTON_EVENTs (ignore confirm until the picker resolves).
5. A full game on the fully-repaired board (every square + both buttons) — the F-03 gates ran against a partially-working board + temporary ADC buttons.

**Reusable test assets:** `pio test -e native` + the mobile `BoardWireCodecTest.kt` remain the off-hardware codec-drift guard. The emulator-driven E2E harness transfers directly: `RecordingBoardConnection` (wraps a real board, records every `send()` so tests assert which `SetMode`/`RequestSnapshot` fired), the `runCurrent` vs `advanceUntilIdle` discipline (`lessons.md`), `tick()` for a single ~10 Hz diagnostic snapshot, and the offline-change pattern `disconnect() → setOccupancy() → connect()` (`EmulatedBoard.kt:136-142`) — **the exact divergence pattern S-09 reconnect-reconcile tests reuse**. The on-hardware acceptance should reuse the nRF-Connect gate *structure* but drive it through the app (pair+bond, subscribe, play moves+capture+promotion, press confirm buttons, force disconnect/offline-change/reconnect, toggle diagnostic), asserting the app's move log and reconnect-reconcile rather than raw bytes.

### G. FR-012 (disconnect/reconnect) — in scope, and mostly already seated

FR-012 is the §1.7/§6.1 reconnect-reconcile. Per the kickoff decision it is **in scope** for S-09, and the design has anticipated it:
- **Disconnect:** pause acceptance (done via `paused`), show a non-blocking "Board disconnected — reconnecting" status (a small UI addition), invariant "no move saved during the disconnect window" (already enforced by the `confirm()` guard).
- **Auto-reconnect:** Kable `observe` resumes the notify stream on reconnect; the adapter surfaces `CONNECTED` again.
- **Reconcile:** the `BoardConnected` arm already sends `RequestSnapshot`; S-09's new `reconnectReconciling` flag holds acceptance until the snapshot matches (auto-resume) or routes a mismatch into the existing diagnostics-restore loop. **This is the only net-new reducer logic in the whole slice**, and it's a near-verbatim copy of S-08's `resumeVerified`.

## Code References

- `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/BoardConnection.kt:18-33` — the port S-09 implements
- `.../domain/board/BoardEvents.kt:11-80` — `BoardEvent` vocabulary (final)
- `.../domain/board/BoardCommand.kt:8-25` — `BoardCommand` vocabulary (final)
- `.../domain/board/Occupancy.kt:21-27` — `Position.toOccupancy()` (reconnect diff; sign-bit warning `:16-19`)
- `.../data/board/protocol/BoardWireCodec.kt:52-283` — complete, total byte codec (RX `decodeEvent:126`, TX `encodeCommand:211`); **reused, not rebuilt**
- `.../data/board/emulator/EmulatedBoard.kt:56-253` — reference `BoardConnection` impl; offline-change driver `:136-142`
- `.../platform/PlatformCapabilities.{kt:12,android.kt:4,ios.kt:4,wasmJs.kt:4}` — `supportsPhysicalBoard` (web = false)
- `.../di/PlatformModule.android.kt:38-49` (TODO(S-09) `:34-37`) and `.../di/PlatformModule.ios.kt:27-38` (TODO `:23-26`) — **the DI swap point + teardown obligation**
- `.../presentation/physical/PhysicalPlayContract.kt` — state/gates (`paused:98`, `acceptanceBlocked:104`, `awaitingResumeConfirm:91`), msgs `:135-211`, effects `:213-235`, seam doc `:83-91,:117-122`
- `.../presentation/physical/PhysicalPlayReducer.kt` — `SnapshotReceived:124-152` (`matchesExpected:129`), `BoardConnected:103-118` (TODO `:107-108`), `BoardDisconnected:120-122`, `confirm():317-381`, `commit():388-416`, `effectsForModeChange:424-440`
- `.../presentation/physical/PhysicalPlayViewModel.kt:60-79,158-201,216-236` — collectors, `commitMove()`, `send()`, `toMsg()`
- `firmware/src/ble_service.cpp:71-82,230-257,299-318,405-410,452-456` — UUIDs, advertising, subscribe/burst, device name, bonding
- `firmware/lib/board_protocol/board_protocol.cpp:54-148` — firmware codec (twin of `BoardWireCodec.kt`)
- `firmware/src/main.cpp:60-62,138-171,237-246,281-284` — square index, button debounce, offline tracking, burst producer
- `firmware/src/pins.h:24-26,49-58,72-73` · `firmware/HARDWARE.md:143-181` — wiring, buttons, test-rig preconditions
- `docs/reference/contract-surfaces.md:58-185` (§1 BLE), `:580-629` (§6.1/§6.3 reconnect) — the frozen contract
- Tests: `commonTest/.../BoardWireCodecTest.kt`, `EmulatedBoardEndToEndTest.kt:28`, `PhysicalPlayReducerTest.kt:262-277`, `PhysicalResumeEndToEndTest.kt` / `PhysicalRecoverEndToEndTest.kt` (RecordingBoardConnection harness), `firmware/test/test_protocol/test_protocol.cpp`

## Architecture Insights

- **The port is the firewall.** `BoardConnection` was designed (F-02) so the transport (emulator → BLE) is a leaf detail. S-09 is a leaf swap: a new adapter + a Koin binding change + one reducer flag. The reason the slice is small is that the seam was paid for upfront across four prior slices.
- **The codec is a cross-language contract twin.** `BoardWireCodec.kt` (Kotlin) and `board_protocol.cpp` (C++) assert the *same golden byte literals* in their respective test suites — drift between firmware and mobile fails a test, not a field demo. S-09 inherits this guard.
- **Reconnect-reconcile = resume-after-restart, electrically.** S-08's `SnapshotReceived` arm already does "expected occupancy vs. fresh snapshot → match resumes / mismatch routes to diagnostics." FR-012 is the same reconcile triggered by a BLE reconnect instead of an app relaunch — hence one flag, not a new subsystem.
- **Web exclusion is enforced twice** — `supportsPhysicalBoard = false` *and* no `BoardConnection` binding in the wasm module. S-09 must preserve both; never add Kable/BLE to `wasmJsMain` (`lessons.md`).
- **MVI is permitted here, with justification.** `tech-stack.md`/`lessons.md` allow MVI for "genuinely event-heavy screens (live game board, BLE flows)." The physical core is already MVI; a *connection/scan/pairing* screen (if added) is the canonical case to justify MVI in the plan.

## Historical Context (from prior changes)

- `context/changes/physical-capture-emulated/plan.md:100,348-349` (S-06) — "No real BLE / hardware (S-09). The only `BoardConnection` impl bound is the promoted emulator"; the event-merge state machine "grows monotonically across S-07 … and S-09 (real BLE pairing / lifecycle)." Phase 2 promoted `EmulatedBoard` to `commonMain` (`:297-304`).
- `context/changes/physical-capture-emulated/change.md:84-90` (S-06 impl-review) — finding **F1**: the `single<BoardConnection>` scope is never cancelled — "a forward-looking leak for the S-09 BLE swap"; triaged to the `TODO(S-09)` comments now in both platform modules.
- `context/changes/reject-recover-diagnostics/plan.md:61` (S-07) — "No S-08 resume logic and no FR-012 BLE reconnect loop … BLE disconnect/reconnect (FR-012) are later slices"; `research.md:162` — "FR-012 … routes a reconnect mismatch into this exact diagnostic-restore path … S-07's recovery loop is the thing it would later reuse."
- `context/changes/physical-resume-after-restart/plan.md:7,36,42,72-82` (S-08) — builds `awaitingResumeConfirm` and extends `SnapshotReceived` "so FR-012/S-09 can fire the identical reconcile on BLE reconnect … S-09/FR-012 reuses it by adding its own gate flag set in `BoardConnected`."
- `context/changes/firmware-ble-gatt-service/` (F-03) — NimBLE peripheral, Just-Works bonding, the three §1.2 UUIDs, host golden-vector tests; `manual-verification.md` is the gate template (on-hardware checks 2.4–3.10). See its `research.md` for the firmware-side BLE design rationale.

## Related Research

- `context/changes/firmware-ble-gatt-service/research.md` — firmware BLE GATT design (the peripheral half of this contract)
- `context/changes/physical-resume-after-restart/research.md` — the resume/reconcile seam S-09 reuses for reconnect
- `context/changes/reject-recover-diagnostics/research.md` — the diagnostics-restore loop a reconnect mismatch falls into
- `context/changes/physical-capture-emulated/research.md` + `reed-board-emulator/` — the `BoardConnection` port and emulator origins

## Open Questions

1. **iOS bonding — RESOLVED 2026-06-29: option B (force encryption).** Firmware marks the relevant characteristic(s) encryption-required so iOS/Android pair (Just-Works) on first access → bonded, **encrypted (unauthenticated)** link. Adds a firmware + contract + on-hardware re-verify delta — **S-09 is no longer mobile-only**. Detail in the "decisions locked" Follow-up.
2. **Kotlin version gate. — RESOLVED 2026-06-29.** Kable 0.43.1 requires Kotlin 2.4.0+; the project is on **Kotlin 2.4.0** exactly (`SmartChessboard/gradle/libs.versions.toml`: `kotlin = "2.4.0"`, AGP 9.0.1, Compose MP 1.11.1) → satisfied out of the box, no upgrade. (See Follow-up.) Block: none.
3. **Connection/scan UX — RESOLVED 2026-06-29: full connection screen (MVI).** Scan → device list → select → pair → live status, as a justified MVI flow (replaces the emulator's DI auto-connect; must surface the OS pairing prompt from #1B). Detail in the "decisions locked" Follow-up.
4. **iOS background/state-restoration — RESOLVED 2026-06-29: option A (foreground-first).** No `bluetooth-central` / state restoration in MVP. This removes the decisive Kable-over-Blue-Falcon differentiator; Kable-first still stands on the remaining factors (see the Library note in the Follow-up).
5. **Duplicate snapshot on reconnect (benign, confirm).** On reconnect the adapter receives both the on-subscribe burst snapshot *and* the `RequestSnapshot` response the `BoardConnected` arm sends. The reconcile is idempotent, so this is harmless — confirm the reducer handles back-to-back `SnapshotReceived` cleanly (it does, by inspection) and add a test.
6. **Hardware readiness — RESOLVED 2026-06-29: board repaired & functioning; ADC-direct buttons are the MVP target solution** (transistor buffer dropped from MVP scope). On-hardware acceptance can proceed; common ground + DGT clock powered remain operational preconditions.

## Suggested phasing for `/10x-plan` (input, not a commitment)

> **Note:** superseded by the *Revised suggested phasing* in the 2026-06-29 "decisions locked" Follow-up below (adds a firmware-encryption phase, confirms the MVI connection screen, foreground-first).

1. **Dependency + UUIDs** — add Kable (after the Kotlin-version gate), introduce the §1.2 service/characteristic UUIDs as a constant (currently doc-only).
2. **BLE adapter** — `data/board/ble/` implementing `BoardConnection` (androidMain + iosMain): scan by service UUID + name, connect, subscribe `board_event` (burst), map notify→`decodeEvent`→`events`, `encodeCommand`→write, surface `CONNECTED`/`DISCONNECTED`, auto-reconnect, teardown. Reuse `BoardWireCodec`. Prove on `iosSimulatorArm64Test`.
3. **DI swap + teardown** — replace `single<BoardConnection>` in both platform modules; resolve the `TODO(S-09)` leak via Koin `onClose`.
4. **Reconnect-reconcile reducer flag** — add `reconnectReconciling` (set in `BoardConnected`, clear in `SnapshotReceived`), with reducer-unit tests mirroring S-08.
5. **Connection UX + permissions** — scan/connect/status surface (MVI-justified if a screen), Android runtime perms, iOS Info.plist/background modes.
6. **On-hardware acceptance** — `manual-verification.md` reusing the nRF-Connect gate structure through the app on the repaired board.

## Decision log

- **2026-06-29** — Research scope: BLE-library question taken to a decision-grade bake-off *with external evidence* (not deferred); **FR-012 reconnect/reconcile included in S-09 scope** (the `SnapshotReceived`/`BoardConnected` seam was purpose-built for it).
- **2026-06-29** — Library recommendation: **Kable-first** (`com.juul.kable:kable-core` 0.43.1), behind the `BoardConnection` port so a later `expect`/`actual` swap is cheap. Final commit belongs to `/10x-plan` after the Kotlin-version gate.
- **2026-06-29 (follow-up)** — Kotlin-version gate **resolved** (project on 2.4.0). **Blue-Falcon evaluated as a 3rd candidate at the user's request → does NOT change the Kable-first call** (Blue-Falcon lacks iOS state-restoration wiring; see Follow-up Research).

## Follow-up Research 2026-06-29T21:52:18+0200 — Blue-Falcon evaluation + Kotlin-version gate resolved

**Trigger:** the user asked to evaluate `Reedyuk/blue-falcon` as an additional BLE-library candidate, and to confirm the Kotlin-version gate (OQ#2).

### Kotlin-version gate (OQ#2) — RESOLVED

The project is on **Kotlin 2.4.0** (`SmartChessboard/gradle/libs.versions.toml`: `kotlin = "2.4.0"`, AGP `9.0.1`, Compose MP `1.11.1`, coroutines `1.11.0`). Kable 0.43.1's Kotlin-2.4.0+ floor is satisfied with no upgrade. This also means the project does **not** sit below 2.4.0 — which removes the one condition that would have favoured Blue-Falcon (see below).

### Blue-Falcon (`dev.bluefalcon:blue-falcon` 3.4.5) vs Kable 0.43.1 — does it change the recommendation?

**No. Kable-first stands, and the Kotlin finding slightly reinforces it.** Blue-Falcon is a real, active, Apache-2.0 KMP BLE library with published iOS klibs (3.4.5, 2026-06-15; Kotlin 2.3.0 badge; effectively single-maintainer). Decisive comparison rows for *this* contract:

| Requirement (contract §) | Blue-Falcon 3.4.5 | Kable 0.43.1 |
|---|---|---|
| Scan (service UUID) / name filter | Pass / Partial (name filtered client-side) | Pass / Partial (same on iOS) |
| Connect / disconnect / notify (CCCD) / write | Pass | Pass (write-type is a typed enum vs Blue-Falcon's raw `Int?`) |
| Bond + persist (no re-pair) | Pass *by OS behavior* — iOS `createBond()` is a **no-op** (CoreBluetooth has no bond API), reconnect by stored identifier | Pass *by OS behavior* — identical; **not a differentiator** |
| Auto-reconnect | Partial — `autoConnect` is **Android-only**; iOS reconnect is app-driven | Partial — app-driven both platforms; `observe` resumes after reconnect |
| **iOS background / state restoration (§1.7)** | **Fail (not wired)** — `CBCentralManager(this, null)`, no restore identifier, no `willRestoreState`; needs a fork | **Pass (opt-in)** — `CentralManager.configure { stateRestoration = true }` |
| MTU (≤20B payloads) | Pass (moot) | Pass (moot) |
| API style → maps onto `SharedFlow<BoardEvent>` + `suspend send` | Mixed (delegate underneath + a shared `CharacteristicNotification` flow) — adapts cleanly but coarser | **Flow-native end-to-end** (`observe(): Flow<ByteArray>`) — 1:1, least glue |
| iOS maturity / maintenance | Young 3.x iOS re-architecture; bus-factor-1 | More production mileage; org-backed (JuulLabs) |
| Kotlin coupling | Built on 2.3.0 — does **not** force 2.4.0, but may **lag** a 2.4.0 project (Unknown until compiled) | Requires 2.4.0+ — **exact match** to this project |

**Decisive reason to stay on Kable:** the contract explicitly requires iOS background / `CBCentralManager` state restoration (§1.7, §6.1, and the reconnect-reconcile seam). Kable ships it as a one-line opt-in; **Blue-Falcon constructs its central manager with `null` options and has no `willRestoreState` path — it cannot be system-restored on iOS without forking the library** (verified against `AppleEngine.kt` / `BluetoothPeripheralManager.kt` on `master`). Secondary: Kable's Flow-native surface fits the `BoardConnection` port with the least glue, and it's org-backed vs single-maintainer.

**The one condition that would have flipped the call to Blue-Falcon:** a hard toolchain constraint forcing the app to stay **below** Kotlin 2.4.0 (making Kable 0.43.1 unusable). That condition does **not** hold — the project is on 2.4.0 — so it doesn't apply; if anything, Blue-Falcon's 2.3.0 build risks lagging this project.

Because the library lives **below the stable `BoardConnection` port**, this remains reversible: if Kable's 2.4.0 floor ever collides with a future toolchain need, swapping the adapter to Blue-Falcon (and either dropping iOS background-restoration from scope or forking it in) is contained and invisible to tests.

**Sources:** [blue-falcon maven-metadata](https://repo1.maven.org/maven2/dev/bluefalcon/blue-falcon/maven-metadata.xml) · [Blue-Falcon repo](https://github.com/Reedyuk/blue-falcon) · [AppleEngine.kt](https://github.com/Reedyuk/blue-falcon/blob/master/library/engines/ios/src/nativeMain/kotlin/dev/bluefalcon/engine/apple/AppleEngine.kt) · [kable-core maven-metadata](https://repo1.maven.org/maven2/com/juul/kable/kable-core/maven-metadata.xml). Flagged Unknown (not guessed): whether Blue-Falcon 3.4.5 (built on Kotlin 2.3.0) resolves cleanly inside this Kotlin-2.4.0 project — needs a compile to confirm.

## Follow-up Research 2026-06-29 (decisions locked)

The user locked the remaining Open Questions (2026-06-29):

| OQ | Decision | Consequence |
|---|---|---|
| #1 iOS bonding | **B — force encryption** | Firmware + contract + re-verify delta (below); link becomes encrypted, **Just-Works → unauthenticated** (board has no display/keypad). |
| #3 Connection UX | **Full connection screen (MVI)** | Scan → device list → select → pair → live status as an MVI state machine (justify in the plan per `lessons.md` — the sanctioned "BLE flow" case). Replaces the emulator's DI auto-connect; surfaces the OS pairing prompt. |
| #4 iOS background | **A — foreground-first** | No `bluetooth-central` / state restoration in MVP. Removes the decisive Kable-over-Blue-Falcon differentiator (see Library note). |
| #6 Hardware | **Resolved — board repaired & functioning; ADC-direct buttons = MVP target solution** (transistor buffer dropped from MVP scope). On-hardware acceptance can proceed; common ground + DGT clock powered remain operational preconditions. |
| Emulator scope | **Test-only, no UI** | `EmulatedBoard` stays a programmatic fake for automated tests; **no longer bound in production DI** after S-09. Device builds bind the real BLE adapter, so interactive physical play uses the real board. No emulator UI is built. (A no-hardware on-device debug mode is a reversible post-MVP affordance, out of MVP scope.) |

### Decision #1B — firmware + contract delta (S-09 is no longer mobile-only)

Forcing encryption makes iOS (and Android) trigger pairing/bonding on first access to an encryption-required characteristic. Concretely:
- **Firmware (F-03 delta):** mark the relevant characteristic(s) encryption-required (NimBLE `BLE_GATT_CHR_F_*_ENC`; for `board_event`, the CCCD subscribe must require encryption). With the existing `NO_INPUT_OUTPUT` IO-cap, pairing stays **Just-Works** → the link is **encrypted but unauthenticated** (no MITM protection; the board hardware can't do better). A clear step up from plaintext, consistent with §1.8's "can be hardened post-MVP".
- **Contract update:** `contract-surfaces.md` §1.2 (characteristic now requires encryption) + a note in §1.1/§1.8; under §Change-control this also touches `prd-firmware.md`.
- **Re-verify on hardware:** re-run the bonding/encrypted-subscribe gate **driven by the app** (not just nRF Connect), on Android and iOS.
- **Plan decision:** F-03 is currently "ready for `/10x-archive`". Decide whether this firmware delta rides **inside S-09** (recommended) or as a small **F-03 amendment** before archiving. Either way, S-09's plan must include a firmware phase.

### Library note (consequence of #4A)

The Kable-over-Blue-Falcon call was decided primarily on iOS state restoration (§1.7). With **foreground-first**, that differentiator leaves MVP scope, so the margin narrows. **Kable-first still stands** on: Flow-native API mapping 1:1 onto the `BoardConnection` port; **exact Kotlin 2.4.0 match** (Blue-Falcon is on 2.3.0 and risks lagging this project); org-backed maintenance. The port keeps the choice reversible, so it is not load-bearing — not re-opened.

### Revised suggested phasing for `/10x-plan` (input)

1. **Dependency + UUIDs** — add Kable (Kotlin 2.4.0 ✓); introduce the §1.2 UUIDs constant (currently doc-only).
2. **Firmware encryption (F-03 delta) + contract update** — mark characteristic(s) `_ENC`, update §1.2/§1.8 (+ `prd-firmware.md`), re-verify bonding on hardware. *(New, from #1B.)*
3. **BLE adapter** — `data/board/ble/` implementing `BoardConnection` (androidMain + iosMain): scan by service UUID + name, connect, **pair/bond on the encrypted characteristic**, subscribe `board_event` (burst), map notify↔`BoardWireCodec`, surface CONNECTED/DISCONNECTED, foreground reconnect, teardown. Prove on `iosSimulatorArm64Test`.
4. **DI swap + teardown** — replace `single<BoardConnection>` with the BLE adapter in both platform modules; resolve the `TODO(S-09)` leak; emulator drops out of production DI (test-only).
5. **Connection screen (MVI)** — scan/list/select/pair/status as a justified MVI flow; surface the OS pairing prompt; Android runtime perms; iOS `NSBluetoothAlwaysUsageDescription` (no background mode — foreground-first).
6. **Reconnect-reconcile reducer flag** — `reconnectReconciling` (set in `BoardConnected`, clear in `SnapshotReceived`); reducer-unit tests.
7. **On-hardware acceptance** — reuse the nRF-Connect gate structure through the app on the repaired board (incl. the app-driven encrypted-bond gate from #1B).
