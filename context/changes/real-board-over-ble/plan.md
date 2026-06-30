# Real Board over BLE (S-09) Implementation Plan

## Overview

S-09 lets a user play the physical-mode flow against the *actual* reed board over BLE: connect,
receive live board events, confirm moves with the side buttons, and persist accepted moves on real
hardware. It is a **transport-fill, not a protocol build** — the `BoardConnection` port, the
`BoardWireCodec` byte codec, and the `SnapshotReceived` reconnect-reconcile seam already exist and
are firmware-proven. The slice adds a real Kable BLE adapter (Android + iOS), forces link encryption
in firmware (so iOS bonds), wires FR-012 reconnect-reconcile through one new reducer flag, builds a
connection/pairing screen (MVI), and adds a live reed-matrix overlay on the play board. It closes with
a blocking on-hardware acceptance pass on both platforms.

## Current State Analysis

Everything above the wire already depends on one transport-agnostic port; the real work lives below
it plus a thin firmware/contract delta and two presentation additions.

- **The seam exists.** `domain/board/BoardConnection.kt:18-33` (`connectionState`, `events`, `send`) is
  implemented today by `EmulatedBoard` in `commonMain`; the port deliberately excludes scan/pair/MTU
  lifecycle. S-09 adds a second implementation in `androidMain`/`iosMain` and swaps the Koin binding.
  Nothing above the port (ViewModel, reducer, every existing test) changes.
- **The byte codec exists and is firmware-proven.** `data/board/protocol/BoardWireCodec.kt:52-283` is a
  total `Decoded | Malformed` encoder/decoder, byte-for-byte equal to `firmware/.../board_protocol.cpp`
  (same golden literals in both test suites). The adapter calls `decodeEvent(bytes)` on each notify and
  `encodeCommand(cmd)` before each write — no new codec.
- **The reconnect-reconcile seam exists.** `PhysicalPlayReducer.kt:103-152` carries the explicit
  S-09 breadcrumb comment at `:107-108`: the `BoardConnected` arm already emits `RequestSnapshot`; the
  `SnapshotReceived` arm already clears the S-07 `recovering` and S-08 `awaitingResumeConfirm` gates via
  one at-rest board-match check. S-09 adds a third gate flag that funnels through the same check.
- **Disconnect-pause is already enforced** via the derived `paused` flag (`PhysicalPlayContract.kt:98`)
  and the `confirm()` guard (`Reducer:321`) — satisfying §1.7's "no move saved during the disconnect
  window" without new reducer logic.
- **Firmware F-03 matches the contract byte-for-byte** and gates 2.4–3.10 are on-hardware-verified
  (2026-06-29, board `SmartChessboard-DA3A`, nRF Connect). The characteristics are declared
  `BLE_GATT_CHR_F_NOTIFY` / `BLE_GATT_CHR_F_WRITE` (no `_ENC`) in `firmware/src/ble_service.cpp:113-135`.
- **Kotlin is 2.4.0** (`gradle/libs.versions.toml:14`), satisfying Kable 0.43.1's floor; Kable is not yet
  in the catalog.
- **A reed-occupancy dot renderer already exists** — `presentation/board/ReedDiagnosticsGrid.kt` renders
  occupancy as dots with mismatch tinting, but as a *separate* at-rest diagnostic surface shown only when
  `diagnosticsVisible`. `ChessBoardView.kt` is a stateless board with optional, defaulted overlays
  (`highlightedSquares`, `bestMoveArrow`) — the established pattern for an additive dot overlay.

## Desired End State

On a phone with the repaired board powered (common ground + DGT clock on), the user opens physical
mode, sees a scan list, taps the board, the OS pairing prompt appears, the link bonds **encrypted**,
and play begins. Live board events drive the move flow; a corner dot on each square mirrors the reed
matrix in real time. Pulling the board out of range pauses acceptance with a "reconnecting" banner; on
return the app auto-reconnects, re-requests a snapshot, and either auto-resumes (match) or opens the
reed grid to restore (mismatch). The emulator no longer ships in production DI. Verified by the full
automated suite on Android + iOS + web, and by a blocking on-hardware acceptance pass on Android **and**
iOS.

### Key Discoveries:

- The DI swap point + teardown obligation: `di/PlatformModule.android.kt:38-49` (`TODO(S-09)` at `:34-37`)
  and `di/PlatformModule.ios.kt:27-38` (`TODO` at `:23-26`) — the emulator's connect-on-bind scope is
  never cancelled; a real BLE adapter on this exact shape leaks the connection. Teardown is a required
  deliverable, not an afterthought.
- The on-connect SNAPSHOT→STATUS burst fires on **CCCD subscribe, not GAP connect**
  (`firmware/src/ble_service.cpp:299-318`) — the central must (re)enable the CCCD on every connection.
  Kable's `observe()` auto-resubscribes across reconnect, which is exactly this.
- Offline changes are reconciled **snapshot-based**, not by buffered events
  (`firmware/src/main.cpp:237-246`) — the next reconnect burst snapshot reflects the new occupancy.
- The shared `SnapshotReceived` match is `msg.occupancy == state.position.toOccupancy()`
  (`Reducer:129`); a reconnect duplicate snapshot (burst + the `BoardConnected` arm's `RequestSnapshot`)
  is idempotent.
- Fixed live values: battery always `0x64`, fw `1.0.0`, uptime unsigned seconds — the mobile must not
  model battery drain or assume a nonzero patch.
- Android `minSdk` is 24 (`libs.versions.toml`) → both the API 31+ runtime perms and the legacy
  ≤30 permission set are needed.

## What We're NOT Doing

- **No iOS background / state restoration** (foreground-first, OQ#4A) — no `UIBackgroundModes:
  bluetooth-central`, no `willRestoreState`. Reconnect is foreground-only.
- **No web BLE** — `wasmJsMain` never gets Kable or a `BoardConnection`/`BoardTransport` binding
  (`lessons.md`: web is digital-only). `supportsPhysicalBoard = false` stays.
- **No new wire-protocol messages** — the §1.3/§1.4 catalog and `BoardWireCodec` are frozen.
- **No multi-board, no OTA, no on-board LED feedback** (§1.8 out-of-scope).
- **No authenticated pairing** — Just-Works (`NO_INPUT_OUTPUT`) yields an **encrypted-but-unauthenticated**
  link; the board has no display/keypad. Hardening MITM is post-MVP.
- **No emulator UI / no-hardware on-device debug mode** — the emulator stays a test-only fake; device
  builds bind the real adapter. A debug toggle is a reversible post-MVP affordance.
- **No transistor button buffer** — ADC-direct DGT buttons on GPIO34/35 are the MVP target.
- **No mismatch-coloured matrix overlay** — the play-board overlay is plain live dots (occupied/empty);
  at-rest mismatch tinting already lives in `ReedDiagnosticsGrid`.

## Implementation Approach

Below the `BoardConnection` port is a leaf detail, so the bulk of the slice is a new
`data/board/ble/` adapter behind the existing seam plus a Koin binding swap. Above the port, only two
small presentation additions land (the connection MVI screen and the matrix overlay) and exactly one
new reducer flag. The firmware/contract encryption delta rides inside this slice (decision: inside
S-09) because the encrypted bond is only fully provable through the app. Phases are ordered by
dependency: dependency/UUIDs → firmware+contract → adapter → DI swap → connection UX → reconnect flag →
matrix overlay → blocking on-hardware acceptance. Phases 6 and 7 are pure `commonMain` logic + UI,
fully testable against the emulator, independent of real radio.

The BLE library is **Kable 0.43.1** (`com.juul.kable:kable-core`), chosen for its Flow-native surface
(1:1 with the port), exact Kotlin-2.4.0 match, and `observe()` auto-resubscribe across reconnect. The
choice lives entirely below the stable port, so a later `expect`/`actual` or Blue-Falcon swap is
contained and invisible to tests.

## Critical Implementation Details

- **iOS simulator has no BLE radio.** The adapter's real Kable path cannot execute on
  `iosSimulatorArm64Test`; that suite proves only the transport-agnostic mapping/state logic (notify
  bytes → `BoardEvent` via the already-proven codec, and connection-state derivation) behind a fake.
  Real radio behaviour is proven only in the Phase 8 hardware gate. Keep Kable calls at the very edge so
  a pure, fakeable mapping seam remains testable on Native.
- **Reconnect E2E must use `runCurrent`, not `advanceUntilIdle`, after the connect burst.** A resume/
  reconnect mismatch auto-opens diagnostics, which arms the ~10 Hz `DIAGNOSTIC` snapshot stream;
  `advanceUntilIdle()` then advances virtual time forever and the test hangs. Settle `load()` with
  `advanceUntilIdle()` while still disconnected, settle the connect burst with `runCurrent()`, deliver
  restores with a single `tick()`. Inject per-scenario occupancy — never rely on the emulator default
  (`lessons.md`, surfaced S-08 Phase 2).
- **`sensedOccupancy` must stay separate from `latestOccupancy`.** Restore/resume/setup-mismatch compare
  an *at-rest snapshot* occupancy to `position.toOccupancy()`; folding live lift/place events into
  `latestOccupancy` would break that. The new field is a display-only mirror, never read by any gate.
- **Encryption gates write (reliably) + subscribe (best-effort), not a read.** `board_event` is
  notify-only, so the bond is forced through the encryption-required `mobile_command` write (the reliable
  trigger, since the app writes `REQUEST_SNAPSHOT` on every connect), with the CCCD subscribe gated
  additionally where this NimBLE build allows (see Phase 2). With `NO_INPUT_OUTPUT` the pairing stays
  Just-Works → encrypted, unauthenticated.

## Phase 1: Dependencies + UUIDs

### Overview

Add Kable to the mobile-only source sets and lift the §1.2 UUIDs out of documentation into a typed
constant. No behaviour change.

### Changes Required:

#### 1. Version catalog + module dependencies

**File**: `SmartChessboard/gradle/libs.versions.toml`, `SmartChessboard/shared/build.gradle.kts`

**Intent**: Declare Kable 0.43.1 and add it to `androidMain` + `iosMain` only (never `wasmJsMain` or
`commonMain`). Kotlin 2.4.0 already satisfies the floor.

**Contract**: New `kable = "0.43.1"` version + `com.juul.kable:kable-core` library entry; the dependency
is added to the Android and iOS source-set blocks only.

#### 2. BLE UUID constant

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/ble/BleUuids.kt` (new)

**Intent**: One source of truth for the service + characteristic UUIDs the adapter scans/subscribes/
writes against, byte-identical to §1.2 and the firmware.

**Contract**: Constants for service `787e0001-15a4-4fc9-a469-05096dbad1a1`, `board_event`
`787e0002-…`, `mobile_command` `787e0003-…`, and the CCCD `0x2902`. `commonMain` (string form) so both
platform adapters share them.

### Success Criteria:

#### Automated Verification:

- [ ] Catalog resolves and the shared module builds: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:assemble --console=plain --no-daemon`
- [ ] All three target suites still pass: `testAndroidHostTest`, `iosSimulatorArm64Test`, `wasmJsTest`
- [ ] ktlint clean: `ktlint -F` reports no violations under `SmartChessboard/`

#### Manual Verification:

- [ ] Kable appears in the Android/iOS dependency tree but NOT in the wasm dependency tree

---

## Phase 2: Firmware encryption (F-03 delta) + contract update

### Overview

Force link encryption so iOS (and Android) trigger pairing/bonding on first access, and record the
delta in the frozen contract. Rides inside S-09 (decision: inside-S-09).

### Changes Required:

#### 1. Encryption-required characteristics

**File**: `firmware/src/ble_service.cpp`

**Intent**: Require encryption on the `board_event` CCCD subscribe and on the `mobile_command` write so a
central must bond before subscribing/writing; pairing stays Just-Works (`NO_INPUT_OUTPUT` unchanged) →
encrypted, unauthenticated.

**Contract**: Make `mobile_command` write encryption-gated with `BLE_GATT_CHR_F_WRITE_ENC` on its
`k_chrs` entry (`:129`). For `board_event` (notify-only, `:119`), the central touches only the
NimBLE-auto-added CCCD on subscribe — there is **no** `BLE_GATT_CHR_F_NOTIFY_ENC`, and whether a
characteristic `_ENC` flag propagates to the auto-CCCD's write permission is version-dependent in
NimBLE. So **the write path is the load-bearing bond trigger**: the app sends `REQUEST_SNAPSHOT` on
every (re)connect (`PhysicalPlayReducer` `BoardConnected` arm → `Send(RequestSnapshot)`), so an
encryption-required `mobile_command` forces pairing/bonding before the burst is usable even if the
subscribe itself is not gated. Additionally try to gate the CCCD subscribe (notify characteristic's
`min_key_size` + `_ENC` flags) and verify at the nRF gate which paths actually trigger pairing — if the
subscribe is not gated, the write requirement already carries it. Bonding/NVS-persist config
(`sm_io_cap=BLE_HS_IO_NO_INPUT_OUTPUT`, `sm_bonding=1`, `sm_sc=1`, key dist — `:452-456`) is unchanged.

#### 2. Contract + PRD surfaces

**File**: `docs/reference/contract-surfaces.md` (§1.1, §1.2, §1.8), `context/foundation/prd-firmware.md`

**Intent**: Record that the characteristics now require encryption and that the link is bonded +
encrypted (Just-Works, unauthenticated) — a step up from §1.8's plaintext "trust-on-first-pair".

**Contract**: §1.2 characteristic table notes encryption-required; §1.1 "bonded after first connection"
gains the encrypted-link clarification; §1.8 records the MVP hardening. `prd-firmware.md` change-control
note added. Update `docs/reference/contract-surfaces.md` registry date.

### Success Criteria:

#### Automated Verification:

- [ ] Firmware host codec tests pass: `pio test -e native` (from `firmware/`)
- [ ] Firmware builds: `pio run` (from `firmware/`)

#### Manual Verification:

- [ ] Flash the board (`pio run -t upload`) and confirm in nRF Connect that an unbonded `mobile_command` write is refused / triggers a pairing+encryption request (the load-bearing trigger), and record whether subscribing to `board_event` also triggers it
- [ ] Bond persists across a reconnect without re-pairing (Just-Works, no PIN)

---

## Phase 3: BLE adapter (`data/board/ble/`)

### Overview

The real `BoardConnection` over Kable, plus a driver surface the connection screen uses. One object,
two faces (decision: single object, like `EmulatedBoard`).

### Changes Required:

#### 1. Driver interface + discovered-board model

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/BoardTransport.kt` (new)

**Intent**: The lifecycle surface the port deliberately omits — scan, connect to a chosen device,
disconnect, plus a discovered-device stream and a richer transport state — so the connection screen (in
`commonMain`) can drive it without depending on a concrete adapter.

**Contract**: `interface BoardTransport { val transportState: StateFlow<BoardTransportState>; val
scanResults: Flow<List<DiscoveredBoard>>; fun startScan(); fun stopScan(); suspend fun
connect(id: String); suspend fun disconnect() }`. `DiscoveredBoard(id, name, rssi)`.
`BoardTransportState` enumerates Idle/Scanning/Connecting/Pairing/Connected/Disconnected and failure
variants (BluetoothOff, PermissionDenied, OutOfRange, BondFailed). Kept distinct from the port's 2-state
`connectionState`.

#### 2. Kable adapter (both platforms)

**File**: `SmartChessboard/shared/src/androidMain/kotlin/org/rurbaniak/smartchessboard/data/board/ble/KableBoardAdapter.kt` and `.../iosMain/.../KableBoardAdapter.kt` (new; shared logic factored to `commonMain` where it needs no Kable)

**Intent**: Implement both `BoardConnection` and `BoardTransport` over Kable: scan by service UUID +
name, connect, bond on the encrypted characteristic, subscribe `board_event` (receiving the burst), map
each notify through `BoardWireCodec.decodeEvent` into `events`, `encodeCommand` → write for `send`,
surface `CONNECTED`/`DISCONNECTED`, foreground auto-reconnect via `observe`, and tear down cleanly.

**Contract**: Class implementing `BoardConnection` + `BoardTransport`. RX: `peripheral.observe(boardEvent)
→ decodeEvent → emit BoardEvent` (drop `Malformed`). TX: `send(cmd) → encodeCommand → write(WriteType)`;
throws `IllegalStateException` when disconnected (port contract). `connectionState` derives CONNECTED from
Kable's connected state. `events` is a hot `SharedFlow(replay = 0)` — subscribe before connect so the
burst isn't missed. Keep Kable calls at the edge; extract a pure `notify→BoardEvent` mapping seam for the
Native test.

### Success Criteria:

#### Automated Verification:

- [ ] Mapping/state seam tests pass on Native: `iosSimulatorArm64Test`
- [ ] Mapping/state seam + emulator-parity tests pass on JVM: `testAndroidHostTest`
- [ ] wasm suite unaffected: `wasmJsTest`
- [ ] ktlint clean

#### Manual Verification:

- [ ] (Deferred to Phase 8 hardware gate — no real radio off-device.)

---

## Phase 4: DI swap + teardown

### Overview

Bind the real adapter, fix the `TODO(S-09)` leak, and drop the emulator from production DI (test-only).

### Changes Required:

#### 1. Platform module bindings

**File**: `SmartChessboard/shared/src/androidMain/.../di/PlatformModule.android.kt`, `.../iosMain/.../di/PlatformModule.ios.kt`

**Intent**: Replace the `single<BoardConnection> { EmulatedBoard(...) }` body with the Kable adapter,
bound as both `BoardConnection` and `BoardTransport` (one instance), and cancel/disconnect it on teardown
via Koin `onClose` (the prescribed `TODO(S-09)` fix). The `PhysicalPlayViewModel` binding is untouched.

**Contract**: `single { KableBoardAdapter(...) } bind BoardConnection::class bind BoardTransport::class`
with `onClose { it?.teardown() }`. **Unlike the emulator (bound with `.also { scope.launch { board.connect() } }`,
`PlatformModule.android.kt:40`), the adapter is constructed idle — no connect/scan on bind; connection is
driven solely by the connection screen via `BoardTransport.connect(id)` (Phase 5), incl. remembered-device
auto-connect.** Emulator import removed from production modules; it remains available to tests only.
`wasmJs` module unchanged (no board binding).

### Success Criteria:

#### Automated Verification:

- [ ] App graph resolves on all targets: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:assemble --console=plain --no-daemon`
- [ ] All existing physical-flow tests still green (they construct the emulator/fakes directly, not via production DI): `testAndroidHostTest`, `iosSimulatorArm64Test`
- [ ] wasm suite green: `wasmJsTest`
- [ ] ktlint clean

#### Manual Verification:

- [ ] (Covered by Phase 8 hardware gate.)

---

## Phase 5: Connection screen (MVI, richer) + permissions

### Overview

A justified MVI connection flow (scan → list → select → pair → live status) with the richer scope: RSSI,
forget/re-pair, manual retry, remembered-device auto-connect on launch, reconnect banner, and a full
error taxonomy. Plus platform permission/Info.plist setup.

### Changes Required:

#### 1. Connection MVI core

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/connection/` — `ConnectionContract.kt`, `ConnectionReducer.kt`, `ConnectionViewModel.kt` (new)

**Intent**: A headless MVI core driving `BoardTransport`: render the discovered list (name + RSSI),
connect on tap, surface the OS pairing prompt, hold the transport-state taxonomy and error states
(Bluetooth off, permission denied + rationale, out of range, bond failed), expose forget/re-pair, manual
retry, and remembered-device auto-connect on entry. MVI justified per `lessons.md` (the sanctioned "BLE
flow" case) — record the justification in the contract doc comment.

**Contract**: Sealed `ConnectionState` + `ConnectionMsg` + `ConnectionEffect`; the VM maps
`BoardTransport.transportState`/`scanResults` to intents and exposes `StateFlow<ConnectionUiState>`.
Remembered board id persisted via `multiplatform-settings`. Pure reducer; IO in effects (matches the
physical core's shape).

#### 2. Connection screen + navigation

**File**: `.../presentation/connection/ConnectionScreen.kt` (new); `.../presentation/navigation/Routes.kt`; the physical-mode entry in `App.kt`/new-game flow

**Intent**: Compose the scan/list/status UI with the reconnect banner, and add a serializable Nav3 route
that gates entry to physical play (must be connected first). Never reachable on web.

**Contract**: New `@Serializable NavKey` with explicit polymorphic registration in `Routes.kt`
(`lessons.md`: no reflection on iOS/wasm; no second nav mechanism). Route guarded by
`supportsPhysicalBoard` so web never links to it.

#### 3. Permissions + platform manifests

**File**: `SmartChessboard/composeApp` Android manifest + an `expect`/`actual` runtime-permission requester declared in `shared` `commonMain` with `androidMain` + `iosMain` + **`wasmJsMain`** actuals; iOS `Info.plist`

**Intent**: Request BLE permissions before scanning and declare the iOS usage string. Foreground-first —
no background mode.

**Contract**: Android: `BLUETOOTH_SCAN` (`android:usesPermissionFlags="neverForLocation"`) +
`BLUETOOTH_CONNECT` (runtime, API 31+); legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION`
with `android:maxSdkVersion="30"` for `minSdk 24`. iOS: `NSBluetoothAlwaysUsageDescription` string; **no**
`UIBackgroundModes`. An `expect fun` permission gate in `commonMain` with platform `actual`s — Android/iOS
request the real perms; **`wasmJsMain` provides a no-op / `Unsupported` actual** because this project has no
intermediate mobile source set, so every `commonMain` `expect` needs a wasm `actual` or `:webApp` (criterion
5.2) won't compile (mirror the `supportsPhysicalBoard = false` precedent). The web connection route is never
registered (`supportsPhysicalBoard` guard), so the stub is never invoked.

### Success Criteria:

#### Automated Verification:

- [ ] Connection reducer unit tests pass (scan→select→connect→pair→connected; denied-permission, BT-off, out-of-range, bond-failed, forget/re-pair, manual retry, remembered-device auto-connect): `testAndroidHostTest` and `iosSimulatorArm64Test`
- [ ] wasm build excludes the connection route (no physical-board route on web): `wasmJsTest` + `:webApp` assembles
- [ ] ktlint clean

#### Manual Verification:

- [ ] Android: permission prompt appears before scan; denying it shows the rationale + settings link; granting proceeds to scan
- [ ] iOS: first BLE use shows the `NSBluetooth…` prompt (no crash)
- [ ] Forget device → next entry shows the scan list again (re-pair path)

---

## Phase 6: Reconnect-reconcile reducer flag (FR-012)

### Overview

The only net-new reducer logic in the slice: a `reconnectReconciling` gate, a near-verbatim copy of
S-08's `awaitingResumeConfirm`, holding acceptance from `CONNECTED` until the post-reconnect snapshot
confirms.

### Changes Required:

#### 1. The gate flag

**File**: `SmartChessboard/shared/src/commonMain/.../presentation/physical/PhysicalPlayContract.kt`, `PhysicalPlayReducer.kt`

**Intent**: Add `reconnectReconciling: Boolean` to `Playing`; set it in the `BoardConnected` arm
(alongside the `RequestSnapshot` it already emits), clear it in `SnapshotReceived` on an at-rest match,
add it to `acceptanceBlocked`, the `accumulate()` guard, the `confirm()` gate, and the `commit()` reset.
A mismatch auto-opens the reed grid and re-checks each snapshot until restored (free, via existing
`setupMismatch`→`diagnosticsVisible`→`effectsForModeChange`).

**Contract**: New `val reconnectReconciling: Boolean = false` field; `acceptanceBlocked` becomes
`paused || recovering || awaitingResumeConfirm || reconnectReconciling`. `SnapshotReceived` adds
`reconnectVerified = state.reconnectReconciling && matchesExpected` → cleared in the `state.copy` block,
parallel to `resumeVerified`. Distinct from the resume flag so the UI can label "reconnecting" vs
"resuming" vs "rejected".

### Success Criteria:

#### Automated Verification:

- [ ] Reducer unit tests pass (set on `BoardConnected`, clear on matching `SnapshotReceived`, hold + auto-grid on mismatch, idempotent on back-to-back duplicate snapshots): `testAndroidHostTest`
- [ ] Native parity for the reducer/E2E: `iosSimulatorArm64Test`
- [ ] Reconnect E2E passes using the `runCurrent`-not-`advanceUntilIdle` discipline + injected occupancy + offline-change pattern (`disconnect → setOccupancy → connect`)
- [ ] ktlint clean

#### Manual Verification:

- [ ] (Behaviour proven on real hardware in Phase 8.)

---

## Phase 7: Live matrix overlay (sensed-occupancy dots)

### Overview

A plain, always-on corner dot per square on the play board, mirroring the live reed matrix before the
user confirms (decision: plain dots, always-on with a toggle). Display-only; zero extra BLE traffic.

### Changes Required:

#### 1. Sensed-occupancy state

**File**: `.../presentation/physical/PhysicalPlayContract.kt`, `PhysicalPlayReducer.kt`

**Intent**: Maintain a live matrix mirror, separate from `latestOccupancy`: reset to the snapshot
occupancy on `SnapshotReceived`, clear the bit on `SquareLifted`, set it on `SquarePlaced`. Never read by
any acceptance gate.

**Contract**: New `val sensedOccupancy: Long? = null` on `Playing`; updated in the `SnapshotReceived`,
`SquareLifted`, `SquarePlaced` arms (h8-safe `(1L shl sq)` bit ops). Not added to `acceptanceBlocked` or
any `commit()`/`confirm()` logic.

#### 2. Board overlay + toggle

**File**: `.../presentation/board/ChessBoardView.kt`, `.../presentation/physical/PhysicalPlayScreen.kt`

**Intent**: Add an optional, defaulted occupancy-dot overlay to the stateless board (a small neutral dot
in each occupied square's corner), set only by the physical screen; add a toggle to show/hide it,
defaulting to on. Reuse `ReedDiagnosticsGrid.isOccupied` for the h8-safe bit read.

**Contract**: New optional `ChessBoardView` parameter (e.g. `occupancyDots: Long? = null`), defaulted so
Replay/Play/web call sites are unaffected. `PhysicalPlayScreen` passes `state.sensedOccupancy` when the
toggle is on. Dot geometry reuses the shared `squareAt` placement.

### Success Criteria:

#### Automated Verification:

- [ ] Reducer tests: `sensedOccupancy` resets on snapshot, clears on lift, sets on place, and is never folded into `acceptanceBlocked`: `testAndroidHostTest`, `iosSimulatorArm64Test`
- [ ] `ChessBoardView` overlay renders dots from a bitfield and existing call sites (Replay/Play) are visually unchanged with the default (geometry/overlay test): `testAndroidHostTest`
- [ ] wasm build unaffected (overlay never passed on web): `wasmJsTest`
- [ ] ktlint clean

#### Manual Verification:

- [ ] (Live-against-real-reeds behaviour proven in Phase 8.)

---

## Phase 8: On-hardware acceptance (HARD BLOCKING GATE)

### Overview

The slice is **not complete and must not be archived** until the full app-driven gate passes on the
repaired board on **both** Android and iOS (decision: hard blocking phase).

### Changes Required:

#### 1. Acceptance gate document

**File**: `context/changes/real-board-over-ble/manual-verification.md` (new)

**Intent**: Reuse the nRF-Connect gate *structure* (2.4–3.10) but drive every check **through the app**,
asserting the app's move log, reconnect-reconcile, and matrix overlay rather than raw bytes — on both
platforms, on the fully-repaired board.

**Contract**: A checklist covering, per platform: pair + **encrypted bond** (the #1B re-verify),
on-subscribe burst, a full game incl. capture + promotion + both confirm buttons, forced
disconnect → offline-change → reconnect-reconcile (auto-resume on match; reed-grid restore on mismatch),
diagnostic-mode toggle, the live matrix overlay tracking real reeds, and the "no move saved during the
disconnect window" invariant.

### Success Criteria:

#### Automated Verification:

- [ ] Full suite green on all three targets one last time: `testAndroidHostTest`, `iosSimulatorArm64Test`, `wasmJsTest`
- [ ] ktlint clean; firmware `pio test -e native` + `pio run` green

#### Manual Verification:

- [ ] **Android**: full gate in `manual-verification.md` passes on the real board (pair/encrypted-bond, burst, full game with capture + promotion, button confirms, disconnect/offline-change/reconnect-reconcile, diagnostic toggle, live overlay)
- [ ] **iOS**: the same full gate passes on the real board
- [ ] Encrypted bond persists across reconnect without re-pairing on both platforms
- [ ] No accepted move is lost across a disconnect window; the matrix overlay matches the physical reeds

---

## Testing Strategy

### Unit Tests:

- Reducer: `reconnectReconciling` set/clear/hold + idempotent duplicate snapshot; `sensedOccupancy`
  reset/clear/set and its exclusion from gating (mirror `PhysicalPlayReducerTest`).
- Connection MVI reducer: the full scan→connect→pair→connected path plus every error/forget/retry/
  auto-connect branch.
- BLE mapping seam: notify bytes → `BoardEvent` (codec already covers byte fidelity); connection-state
  derivation.
- `ChessBoardView` overlay geometry from a bitfield; default-off leaves Replay/Play unchanged.

### Integration Tests:

- Reconnect-reconcile E2E over `EmulatedBoard` reusing the `RecordingBoardConnection` harness +
  offline-change pattern + `runCurrent`/`advanceUntilIdle` discipline (`lessons.md`).

### Manual Testing Steps:

The full on-hardware gate is Phase 8's `manual-verification.md`, run on Android **and** iOS against the
repaired board (common ground + DGT clock powered).

## Performance Considerations

Largest frame is 9 bytes — well inside the default 23-byte ATT MTU, so no MTU negotiation. The matrix
overlay adds no BLE traffic (it folds the `SQUARE_EVENT`s the board already emits in game mode). The
~10 Hz diagnostic stream only runs while the reed grid is open.

## Migration Notes

The emulator leaves production DI (test-only) — device builds now require a real, paired board to play
physical mode. No data migration; the journal/cloud schema is unchanged. The firmware encryption flag is
a one-way step-up; once flashed, plaintext-only centrals (the old app) can no longer subscribe — but the
old app is not deployed, so there is no field-compat concern.

## References

- Research: `context/changes/real-board-over-ble/research.md`
- The port: `SmartChessboard/shared/src/commonMain/.../domain/board/BoardConnection.kt:18-33`
- The codec: `.../data/board/protocol/BoardWireCodec.kt:52-283`
- The reconnect seam: `.../presentation/physical/PhysicalPlayReducer.kt:103-152` (S-09 breadcrumb comment `:107-108`)
- The DI swap point: `.../di/PlatformModule.android.kt:38-49`, `.../di/PlatformModule.ios.kt:27-38`
- The overlay precedent: `.../presentation/board/ChessBoardView.kt`, `.../ReedDiagnosticsGrid.kt`
- Firmware GATT: `firmware/src/ble_service.cpp:113-135` (chars), `:299-318` (subscribe burst)
- The frozen contract: `docs/reference/contract-surfaces.md` §1, §6.1/§6.3
- Prior slices: `context/changes/physical-resume-after-restart/` (S-08 resume seam), `reject-recover-diagnostics/` (S-07 restore loop), `firmware-ble-gatt-service/` (F-03 peripheral)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Dependencies + UUIDs

#### Automated

- [x] 1.1 Catalog resolves and the shared module builds (`:shared:assemble`) — 6c66b27
- [x] 1.2 All three target suites pass (`testAndroidHostTest`, `iosSimulatorArm64Test`, `wasmJsTest`) — 6c66b27
- [x] 1.3 ktlint clean — 6c66b27

#### Manual

- [x] 1.4 Kable in Android/iOS dependency tree but NOT wasm — 6c66b27

### Phase 2: Firmware encryption (F-03 delta) + contract update

#### Automated

- [x] 2.1 Firmware host codec tests pass (`pio test -e native`) — 1f1789c
- [x] 2.2 Firmware builds (`pio run`) — 1f1789c

#### Manual

- [x] 2.3 nRF Connect: unbonded `mobile_command` write triggers pairing+encryption (load-bearing); record whether `board_event` subscribe also does — 1f1789c
- [x] 2.4 Bond persists across reconnect without re-pairing (Just-Works) — 1f1789c

### Phase 3: BLE adapter (`data/board/ble/`)

#### Automated

- [x] 3.1 Mapping/state seam tests pass on Native (`iosSimulatorArm64Test`) — 4c71d70
- [x] 3.2 Mapping/state + emulator-parity tests pass on JVM (`testAndroidHostTest`) — 4c71d70
- [x] 3.3 wasm suite unaffected (`wasmJsTest`) — 4c71d70
- [x] 3.4 ktlint clean — 4c71d70

#### Manual

- [ ] 3.5 (Deferred to Phase 8 hardware gate — no real radio off-device.)

### Phase 4: DI swap + teardown

#### Automated

- [x] 4.1 App graph resolves on all targets (`:shared:assemble`) — b450c13
- [x] 4.2 Existing physical-flow tests still green (`testAndroidHostTest`, `iosSimulatorArm64Test`) — b450c13
- [x] 4.3 wasm suite green (`wasmJsTest`) — b450c13
- [x] 4.4 ktlint clean — b450c13

#### Manual

- [ ] 4.5 (Covered by Phase 8 hardware gate.)

### Phase 5: Connection screen (MVI, richer) + permissions

#### Automated

- [x] 5.1 Connection reducer unit tests pass on JVM + Native (`testAndroidHostTest`, `iosSimulatorArm64Test`) — 62b5a7d
- [x] 5.2 wasm build excludes the connection route (`wasmJsTest` + `:webApp` assembles) — 62b5a7d
- [x] 5.3 ktlint clean — 62b5a7d

#### Manual

- [ ] 5.4 Android: permission prompt before scan; deny → rationale + settings link; grant → scan
- [ ] 5.5 iOS: first BLE use shows the `NSBluetooth…` prompt (no crash)
- [ ] 5.6 Forget device → next entry shows the scan list (re-pair path)

### Phase 6: Reconnect-reconcile reducer flag (FR-012)

#### Automated

- [x] 6.1 Reducer unit tests pass (set/clear/hold + idempotent duplicate snapshot) (`testAndroidHostTest`) — 0e604ca
- [x] 6.2 Native parity for reducer/E2E (`iosSimulatorArm64Test`) — 0e604ca
- [x] 6.3 Reconnect E2E passes (`runCurrent` discipline + injected occupancy + offline-change pattern) — 0e604ca
- [x] 6.4 ktlint clean — 0e604ca

#### Manual

- [ ] 6.5 (Behaviour proven on real hardware in Phase 8.)

### Phase 7: Live matrix overlay (sensed-occupancy dots)

#### Automated

- [x] 7.1 Reducer tests: `sensedOccupancy` reset/clear/set + excluded from `acceptanceBlocked` (`testAndroidHostTest`, `iosSimulatorArm64Test`) — c7d6132
- [x] 7.2 `ChessBoardView` overlay renders from a bitfield; Replay/Play unchanged with default (`testAndroidHostTest`) — c7d6132
- [x] 7.3 wasm build unaffected (`wasmJsTest`) — c7d6132
- [x] 7.4 ktlint clean — c7d6132

#### Manual

- [ ] 7.5 (Live-against-real-reeds behaviour proven in Phase 8.)

### Phase 8: On-hardware acceptance (HARD BLOCKING GATE)

#### Automated

- [ ] 8.1 Full suite green on all three targets (`testAndroidHostTest`, `iosSimulatorArm64Test`, `wasmJsTest`)
- [ ] 8.2 ktlint clean; firmware `pio test -e native` + `pio run` green

#### Manual

- [ ] 8.3 Android: full gate in `manual-verification.md` passes on the real board
- [ ] 8.4 iOS: the same full gate passes on the real board
- [ ] 8.5 Encrypted bond persists across reconnect without re-pairing on both platforms
- [ ] 8.6 No accepted move lost across a disconnect window; matrix overlay matches physical reeds
