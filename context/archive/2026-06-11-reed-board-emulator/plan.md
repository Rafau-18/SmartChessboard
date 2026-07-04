# Reed-Switch Board Emulator (F-02) Implementation Plan

## Overview

Build the no-hardware foundation for the physical-board stream: a typed board port in
`domain/board/`, a wire codec for the BLE message catalog (`contract-surfaces.md` §1.3/§1.4) in
`data/board/protocol/`, and a programmatic reed-board emulator in `data/board/emulator/` that
drives **byte-identical** messages through that shared codec. Scenario helpers make realistic
lift/place sequences (capture orderings, castling interleavings, j'adoube) one-liners in tests.

This is roadmap item **F-02**, resolving PRD OQ-1's chosen strategy (programmatic emulator, no
GUI) and unlocking S-06/S-07/S-08 — the whole physical-mode flow becomes developable and
verifiable in CI with no hardware. Verification done against the emulator transfers to the real
board because every emitted event travels through the same §1.3 byte frames the firmware will
send in S-09.

## Current State Analysis

- **No board/BLE/transport code exists anywhere in the app** — pure greenfield. The only §1.3
  artifact in code is the square convention in
  [Square.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/Square.kt)
  (`index = file + 8 * rank`, a1 = 0), landed by F-01 Phase 1 (commit 211fd2c) and verified on
  physical hardware by the firmware bringup.
- **Firmware emits no messages today.** The parked bringup firmware
  ([main.cpp](firmware/src/main.cpp)) implements scan + debounce (~80 ms, 50 Hz) with
  human-readable serial output only — no event encoding, no BLE. **Contract §1.3 is therefore the
  single source of truth for the wire format**; there is no hardware behavior to defer to.
- **Architecture conventions are settled** (S-01, F-01): Clean Architecture (interfaces in
  `domain/`, impls in `data/`), Koin for injectables (but F-01 precedent: no DI wiring until a
  consumer exists), MVVM, `commonTest` with `kotlin-test` + `kotlinx-coroutines-test`,
  hand-written fakes, per-target test tasks (`testAndroidHostTest`, `iosSimulatorArm64Test`,
  `wasmJsTest`).
- **Dependencies already present suffice**: `kotlinx-coroutines-core` (Flow/StateFlow, injectable
  scope), `kotlin-test`, `kotlinx-coroutines-test` (virtual time). No new libraries → no
  `yarn.lock` actualization on the wasm target.
- **Research** (`context/changes/reed-board-emulator/research.md`, 2026-06-11) validated the
  contract's dumb-board model (industry consensus: boards stream state, hosts detect moves;
  early-move-commit boards have documented failure modes) and settled the emulator injection
  layer: **typed port as the seam, bytes through a shared codec as the substance, hand-written
  golden frames as the drift guard** (the "self-consistency trap" mitigation).

### Contract constraints (from `docs/reference/contract-surfaces.md`)

- **§1.3 board → mobile**: `BOARD_SNAPSHOT` (0x01, 8-byte occupancy bitmap), `SQUARE_EVENT`
  (0x02, 1 byte: square in low 6 bits, event in high 2 bits, `00`=lift / `01`=place),
  `BUTTON_EVENT` (0x03, 1 byte: 0x00 white / 0x01 black), `DEVICE_STATUS` (0x04, battery 1B +
  fw version 3B + uptime uint32 LE 4B).
- **§1.4 mobile → board**: `SET_MODE` (0x81 + mode byte), `REQUEST_SNAPSHOT` (0x82),
  `REQUEST_STATUS` (0x83).
- **§1.6 diagnostic mode**: snapshots at ~10 Hz in addition to `SQUARE_EVENT`s.
- **§1.7 / FR-FW-005**: snapshot emitted on every (re)connect; no move saved during a disconnect
  window; the board keeps sensing while disconnected.
- **Underspecified in §1.3**: the bit packing of the 8 snapshot bytes (bit N = square N is given,
  but byte order is not). This plan locks it (Phase 2) and mirrors the clarification back into
  the contract per its change-control rules.

## Desired End State

A `BoardConnection` port in `domain/board/` delivers typed board events; an `EmulatedBoard` in
`data/board/emulator/` implements it by encoding every event to §1.3 bytes and decoding them
through the shared `data/board/protocol/` codec; scenario helpers script realistic sequences;
golden-frame tests pin the wire format; a demo end-to-end test plays a scripted game fragment
(captures both orders, castling, promotion push, diagnostic mode, disconnect/reconnect) and
asserts the exact typed-event stream — all green on Android-host, iOS-sim, and WasmJS.

**Verification of the end state:** the three per-target test tasks pass; golden-frame vectors
(hand-derived from §1.3, independent of the codec) match encoder output and decoder input
byte-for-byte; the demo test proves a downstream consumer (S-06) can subscribe to the port,
drive the board from a script, and receive the §1.3-faithful event stream including
snapshot-on-reconnect after offline board changes.

### Key Discoveries:

- Square convention authority already exists — reuse `squareOf`/`fileOf`/`rankOf` from
  [Square.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/Square.kt);
  do not re-encode the convention.
- Industry precedent (research Finding 3): no-hardware BLE tooling fakes at the GATT/byte layer
  (Nordic CoreBluetoothMock, LightBlue, Bumble); Kable ≥ 0.36.0 exposes `Peripheral` as an
  interface whose fake is itself byte-level — the S-09 adapter will slot under the same port.
- Self-consistency trap (research Finding 3): golden frames must be hand-derived from §1.3, never
  generated by the codec under test.
- Offline mutation matters (research Finding 2 + §1.7): the real board keeps sensing while
  disconnected; S-08's reconcile-on-reconnect scenario requires the emulator to mutate occupancy
  while disconnected and reveal it only via the post-reconnect snapshot.
- ArdEBoard's abandoned reed build (research Finding 2) shows snapshot/resync is load-bearing —
  `REQUEST_SNAPSHOT` and snapshot-on-connect are first-class, not nice-to-have.

## What We're NOT Doing

- **No sequence interpreter.** Resolving lift/place sequences into candidate moves is S-06
  (`physical-capture-emulated`). The emulator is the event *source* only — it has zero chess
  knowledge beyond the square-index convention.
- **No BLE.** No Kable, no GATT, no advertising/bonding/MTU — that is S-09
  (`real-board-over-ble`). Consequently the port does not model BLE-specific lifecycle
  (scanning, pairing); it models only connected/disconnected as seen by consumers.
- **No GUI / visual simulator** (PRD OQ-1: explicitly out of MVP). Dev tooling integration (a
  debug screen driving the emulator) arrives with S-06 at the earliest.
- **No Koin wiring.** No consumer exists yet; F-01 precedent. S-06 registers the port when it
  injects it.
- **No built-in noise generator.** Spurious-event shapes (blips) are identical to j'adoube pairs
  and are scripted via primitives; randomized fuzzing is a later, separate decision.
- **No recorded-hardware-fixture replay** (PRD OQ-1 marks it nice-to-have; the firmware emits no
  parseable log to record yet).
- **No firmware changes.** The firmware stays parked; the §1.3 bit-packing clarification is a
  docs-only edit.
- **No connect() on the port.** Connection initiation/retry policy is transport-specific and
  lands with the S-09 adapter; the emulator's `connect()`/`disconnect()` live on its driver
  surface, not on `BoardConnection`.

## Implementation Approach

Four additive phases, each a reviewable checkpoint:

1. **Port first** — freeze the typed contract (`BoardEvent`, `BoardCommand`, `BoardConnection`)
   that S-06 will consume and S-09 will re-implement over BLE. Pure `domain/`, no behavior.
2. **Codec + golden frames** — make §1.3/§1.4 executable. Encoder and decoder are pinned
   *independently* by hand-written byte vectors so a shared bug cannot self-validate. The
   underspecified snapshot bit layout is locked here and mirrored into the contract doc.
3. **Emulator core** — an occupancy state machine with consistency guards, the full command
   surface, connection lifecycle with offline mutations, and time-driven behaviors (10 Hz
   diagnostic snapshots, ~30 s status) on an injectable clock. Internally, every emitted event
   makes the round trip driver-action → encode → bytes → decode → typed event, which is the
   message-identical guarantee.
4. **Scenario helpers + proof** — chess-agnostic sequence builders with explicit ordering
   variants, then a demo end-to-end test that doubles as living documentation for S-06, plus
   cross-target green.

## Critical Implementation Details

- **Snapshot bit packing is locked as: byte `i`, bit `j` (LSB-first) = square `i*8 + j`.**
  Byte 0 = squares 0–7 (rank 1, a1 = byte 0 bit 0), byte 7 = squares 56–63 (h8 = byte 7
  bit 7). This is the natural extension of §1.3's "bit N = square N" but the byte split is
  currently unwritten — Phase 2 amends `contract-surfaces.md` §1.3 (one sentence) and mirrors a
  dated note into **both** `prd-firmware.md` and `prd.md` per the contract's change-control section
  (§1 BLE routes to both PRDs). `prd.md` gets a single minimal Implementation-Decisions line
  recording that the clarification has no user-facing impact — satisfying the change-control rule
  literally rather than relying on a judgment-call exception.
- **Golden frames must be hand-derived from §1.3, never produced by the codec.** Example vectors
  (computed by hand, cite the § in test comments): place on e4 → square 28 (0b011100), event
  `01` in high bits → frame `[0x02, 0x5C]`; lift e2 → `[0x02, 0x0C]`; start-position snapshot →
  `[0x01, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF]`.
- **Offline mutation semantics**: `lift`/`place` while disconnected mutate occupancy but emit
  nothing (a dead link delivers nothing); the divergence surfaces only in the snapshot emitted
  on reconnect. `pressButton` while disconnected is a silent no-op (a real button press is
  simply lost). `send(command)` while disconnected throws `IllegalStateException` — the mobile
  cannot write to a dead connection.
- **Consistency guards make script bugs loud**: `lift` on an empty square or `place` on an
  occupied square throws immediately (a real board cannot produce these), instead of corrupting
  the stream. `setOccupancy` is allowed only while disconnected (while connected, occupancy may
  change only through lift/place events — anything else would silently desync the stream).
- **Mode resets to GAME on every (re)connect.** §1.7 has the mobile re-enter diagnostic mode
  explicitly after reconnect-mismatch, which implies per-connection mode. Documented in KDoc;
  flagged as a candidate §1 clarification when firmware resumes (not edited now to keep the
  contract delta minimal).
- **Time-driven behavior runs on an injectable `CoroutineScope`** so `runTest`'s virtual clock
  drives the 10 Hz diagnostic snapshots and the periodic `DEVICE_STATUS` deterministically. The
  status cadence is itself a constructor parameter — `statusInterval: Duration = 30.seconds`, with
  `Duration.INFINITE` meaning "no periodic status" — so tests asserting an exact ordered event
  stream can switch the always-on ~30 s status off and avoid it interleaving with the 10 Hz
  diagnostic job; the dedicated periodic-status test sets it to 30 s. An optional `eventDelay`
  (default `Duration.ZERO`) paces emissions for future dev-tool realism — free under virtual time.
- **Hot stream semantics**: `events` is a no-replay hot flow; subscribers attach before driving
  the board (snapshot-on-connect is missed otherwise). Documented on the port; the demo test
  models the correct usage.
- **Emulator placement is `commonTest`, not `commonMain`.** `EmulatedBoard` and `BoardScenarios`
  are test fixtures until a production consumer exists (S-06 dev tooling, not yet built) — the same
  F-01 "no wiring until a consumer exists" precedent. They live under
  `shared/src/commonTest/.../data/board/emulator/`; the codec (`data/board/protocol/`) and the port
  (`domain/board/`) stay in `commonMain` because the codec is reused by the production S-09 BLE
  adapter. Promote the emulator to `commonMain` only when S-06 actually wires a dev screen to it.

## Phase 1: Board domain port & event model

### Overview

Freeze the typed contract every later slice consumes: event and command vocabularies mirroring
§1.3/§1.4 one-to-one, and the `BoardConnection` port. No behavior yet — this is the seam S-06
subscribes to and S-09 re-implements over BLE.

### Changes Required:

#### 1. Board event vocabulary

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/BoardEvents.kt`

**Intent**: Model the four board → mobile messages of §1.3 as typed events, in the board's own
terms (occupancy, not chess pieces).

**Contract**: `sealed interface BoardEvent` with:
`SquareEvent(square: Int, type: SquareEventType)` where `enum SquareEventType { LIFT, PLACE }`;
`ButtonEvent(button: BoardButton)` where `enum BoardButton { WHITE, BLACK }`;
`BoardSnapshot(occupancy: Long)` — 64-bit bitmap, bit N = square N occupied (a1 = bit 0), with an
`isOccupied(square: Int): Boolean` helper;
`DeviceStatus(batteryPct: Int, firmwareVersion: FirmwareVersion, uptimeSeconds: Long)` with
`FirmwareVersion(major: Int, minor: Int, patch: Int)`. Square indices reuse the `domain/chess`
Square convention (document the §1.3 source inline, as `Square.kt` does).

#### 2. Mobile command vocabulary

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/BoardCommand.kt`

**Intent**: Model the three mobile → board commands of §1.4.

**Contract**: `sealed interface BoardCommand` with `SetMode(mode: BoardMode)` where
`enum BoardMode { GAME, DIAGNOSTIC }`, `data object RequestSnapshot`,
`data object RequestStatus`.

#### 3. The port

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/board/BoardConnection.kt`

**Intent**: The single seam consumers depend on — implemented by the emulator now, by the BLE
adapter in S-09.

**Contract**:

```kotlin
interface BoardConnection {
    val connectionState: StateFlow<BoardConnectionState> // CONNECTED / DISCONNECTED
    val events: SharedFlow<BoardEvent>                   // hot, no replay
    suspend fun send(command: BoardCommand)              // throws IllegalStateException if disconnected
}
```

`enum BoardConnectionState { CONNECTED, DISCONNECTED }`. No `connect()` here (transport-specific;
see "What We're NOT Doing"). KDoc records the subscribe-before-driving rule and the
disconnected-send contract — S-04/S-06 consumers read behavior from this file.

### Success Criteria:

#### Automated Verification:

- Module compiles on all three targets: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:compileKotlinWasmJs :shared:compileKotlinIosSimulatorArm64 :shared:compileAndroidMain --console=plain --no-daemon`
- Formatting clean: `cd SmartChessboard && ktlint -F` reports no violations

#### Manual Verification:

- Public types read as a consumable contract for S-06 (subscribe, script, assert) and a
  plausible target for an S-09 BLE adapter, with no chess vocabulary leaking in.
- Event/command vocabulary maps one-to-one onto §1.3/§1.4 by eye (no missing message, no extra).

**Implementation Note**: After this phase passes automated verification, pause for manual
confirmation before proceeding.

---

## Phase 2: Wire codec & golden frames

### Overview

Make the §1.3/§1.4 byte formats executable: an encoder (board side — used by the emulator, later
a reference for firmware) and a decoder (mobile side — reused verbatim by the S-09 BLE adapter),
pinned independently by hand-written golden vectors. Locks the snapshot bit packing and writes it
back into the contract.

### Changes Required:

#### 1. Codec

**File**: `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/protocol/BoardWireCodec.kt`

**Intent**: Bidirectional translation between `BoardEvent`/`BoardCommand` and §1.3/§1.4 byte
frames, total (every well-formed frame decodes, every malformed frame is reported — never
crashes).

**Contract**: `encodeEvent(event): ByteArray`, `decodeEvent(bytes): EventDecodeResult`,
`encodeCommand(command): ByteArray`, `decodeCommand(bytes): CommandDecodeResult`, where the
decode results are sealed `Decoded(value)` / `Malformed(bytes, reason)` (unknown tag, bad length,
out-of-range field — forward-compatible with the reserved 0x84–0x9F command space). Frame
layouts implemented exactly per §1.3/§1.4; snapshot packing per Critical Implementation Details
(byte `i` bit `j` LSB-first = square `i*8+j`); `DEVICE_STATUS` uptime is uint32 little-endian
held in a `Long`.

#### 2. Golden-frame test vectors

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/data/board/protocol/BoardWireCodecTest.kt`

**Intent**: Pin encoder and decoder independently against hand-derived §1.3/§1.4 byte vectors so
a shared codec bug cannot validate itself (research: crypto test-vector discipline).

**Contract**: For every message type at least: one literal hand-computed frame asserted against
`encodeEvent`/`encodeCommand` output, the same frame fed to the decoder and asserted against the
typed value, plus edge vectors (square 0 / square 63 lift and place; empty and full and
start-position snapshots; both buttons; both modes; uptime crossing a byte boundary to prove
LE order) and malformed cases (unknown tag, truncated payload, square-event with reserved high
bits `1x`). Each vector's comment cites the contract section it was derived from. Round-trip
property checks are allowed *in addition to*, never instead of, literal vectors.

#### 3. Contract clarification (snapshot bit packing)

**File**: `docs/reference/contract-surfaces.md`

**Intent**: Write the locked bit packing back into §1.3 so firmware implements the same layout in
S-09 — per the document's own change-control rules.

**Contract**: One added sentence in the `BOARD_SNAPSHOT` row/section: byte `i` bit `j`
(LSB-first) = square `i*8 + j` (byte 0 = rank 1, a1 = byte 0 bit 0); bump frontmatter `updated`.

#### 4. Mirror note in firmware PRD

**File**: `context/foundation/prd-firmware.md`

**Intent**: Satisfy the contract change-control mirror for §1 changes.

**Contract**: One dated line referencing the §1.3 snapshot bit-packing clarification (under
FR-FW-005 or an adjacent note).

#### 5. Mirror note in product PRD

**File**: `context/foundation/prd.md`

**Intent**: Complete the contract change-control mirror for §1 changes — the rule routes §1 BLE
edits to `prd-firmware.md` *and* `prd.md`.

**Contract**: One dated line in the "Implementation Decisions" (or equivalent) section recording
that the §1.3 snapshot bit-packing was clarified with **no user-facing impact** (pointer to
`contract-surfaces.md`). Deliberately minimal: no FR wording changes, since no user-facing
behavior depends on snapshot encoding.

### Success Criteria:

#### Automated Verification:

- Codec + golden-frame tests pass on host and iOS-sim: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test --console=plain --no-daemon` (Kotlin/Native is where signed-`Byte`/`Long` bit handling is most likely to diverge; WasmJS stays the Phase 4 final check)
- Formatting clean: `cd SmartChessboard && ktlint -F`

#### Manual Verification:

- Spot-check 3–4 golden vectors against §1.3 by hand (a green test with a wrong hand-derived
  vector proves nothing) — including the start-position snapshot bytes `FF FF 00 00 00 00 FF FF`.
- Confirm the contract edit is the minimal one-sentence clarification and both PRD mirror lines
  (firmware-PRD and product-PRD) are present and dated.

**Implementation Note**: Pause for manual confirmation after automated verification passes.

---

## Phase 3: Emulated board core

### Overview

The emulator proper: an occupancy state machine implementing `BoardConnection`, where every
emitted event is produced by encoding through the Phase 2 codec and decoding back (the
message-identical pipeline), with connection lifecycle, offline mutations, the full §1.4 command
surface, and clock-driven behaviors on an injectable scope.

### Changes Required:

#### 1. EmulatedBoard

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoard.kt`
(test source set — a fixture until S-06 has a production consumer; see Critical Implementation Details)

**Intent**: The driver-facing emulator: holds 64-bit occupancy, exposes script primitives, and
implements the port so consumers cannot tell it from a real board at the event-stream level.
Lives in `commonTest`: it sees `commonMain` (port + codec) and is consumed by the `commonTest`
behavior/demo tests, without shipping in any release binary.

**Contract**: `class EmulatedBoard(scope: CoroutineScope, initialOccupancy: Long =
STARTING_POSITION_OCCUPANCY, status: EmulatedDeviceStatus = default, statusInterval: Duration =
30.seconds, eventDelay: Duration = ZERO) : BoardConnection` (`statusInterval = Duration.INFINITE`
disables the periodic `DEVICE_STATUS` job — used by tests that assert an exact ordered stream).
Driver surface (not on the port): `connect()`, `disconnect()`,
`lift(square)`, `place(square)`, `pressButton(button)`, `setOccupancy(occupancy)` (disconnected
only), `val occupancy: Long` (for assertions/dev tooling). Behaviors:

- `connect()` → state CONNECTED, emits `BOARD_SNAPSHOT` then `DEVICE_STATUS` (§1.3 "on connect"),
  resets mode to GAME, starts the periodic `DEVICE_STATUS` job at `statusInterval` (skipped when
  `statusInterval == Duration.INFINITE`).
- `lift`/`place` → consistency guards (lift requires occupied, place requires empty; violation
  throws), mutate occupancy; emit `SQUARE_EVENT` only while connected (offline mutations are
  silent — Critical Implementation Details).
- `pressButton` → `BUTTON_EVENT` while connected; silent no-op while disconnected.
- `send(SetMode(DIAGNOSTIC))` → starts the 10 Hz snapshot job (in addition to square events,
  §1.6); `SetMode(GAME)` stops it. `RequestSnapshot`/`RequestStatus` → immediate emission.
  `send` while disconnected throws `IllegalStateException`.
- `disconnect()` → state DISCONNECTED, cancels periodic jobs.
- **Emission pipeline**: typed event → `encodeEvent` → bytes → `decodeEvent` → emit decoded value
  into `events`; a `Malformed` result here is a bug and throws. Commands symmetrically pass
  through `encodeCommand`/`decodeCommand` before the emulator acts on them. This pipeline is the
  load-bearing fidelity mechanism — do not shortcut it.
- `STARTING_POSITION_OCCUPANCY` constant (ranks 1, 2, 7, 8 set) lives here as a convenience —
  occupancy is a bit pattern, not chess knowledge.

#### 2. Emulator behavior tests

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoardTest.kt`

**Intent**: Pin every behavior above with `runTest` virtual time.

**Contract**: Named tests covering: snapshot+status on connect; square-event emission and
occupancy tracking; consistency-guard throws (lift empty / place occupied / setOccupancy while
connected); offline mutation revealed only by post-reconnect snapshot; button no-op while
disconnected; send-while-disconnected throws; diagnostic mode emits ~10 snapshots per virtually
advanced second and stops on `SetMode(GAME)` (constructed with `statusInterval = INFINITE` so the
periodic status does not intrude on the count); periodic `DEVICE_STATUS` at the configured interval
(its own test sets `statusInterval = 30.seconds`); mode reset to GAME on reconnect.

### Success Criteria:

#### Automated Verification:

- Emulator behavior tests pass on host and iOS-sim: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test --console=plain --no-daemon` (the virtual-time periodic jobs are the most likely Kotlin/Native divergence point; WasmJS stays the Phase 4 final check)
- Formatting clean: `cd SmartChessboard && ktlint -F`

#### Manual Verification:

- Read the emission pipeline and confirm every event genuinely passes encode → decode (no
  typed-event shortcut path exists).
- Confirm disconnect semantics match §1.7 expectations: offline changes surface only via the
  reconnect snapshot; nothing is emitted while disconnected.

**Implementation Note**: Pause for manual confirmation after automated verification passes.

---

## Phase 4: Scenario helpers, demo end-to-end, cross-target green

### Overview

The usability layer and the proof. Chess-agnostic sequence builders encode the realistic
event-orderings research showed players actually produce; the demo test scripts a game fragment
end-to-end and doubles as S-06's usage documentation; the suite goes green on all three targets.

### Changes Required:

#### 1. Scenario helpers

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/BoardScenarios.kt`

**Intent**: Make realistic multi-event sequences one-liners, with ordering variants as
first-class parameters (research: capture lift-order and castling interleavings vary by player).
Lives in `commonTest` alongside the emulator (test fixtures for now); promote to `commonMain`
together with `EmulatedBoard` when S-06 dev tooling becomes a real production consumer.

**Contract**: Extension functions on `EmulatedBoard`, all chess-agnostic (caller supplies
squares; helpers only sequence primitives and inherit their guards):
`quietMove(from, to)`; `capture(from, target, order: CaptureOrder)` with
`enum CaptureOrder { CAPTURED_FIRST, MOVER_FIRST }`;
`castle(kingFrom, kingTo, rookFrom, rookTo, order: CastleOrder)` with
`enum CastleOrder { KING_FIRST, ROOK_FIRST, INTERLEAVED }`;
`enPassant(from, to, capturedSquare, order: CaptureOrder)`;
`adjust(square)` (j'adoube / sensor-blip shape: lift+place same square);
`promotionPush(from, to)` (board-side identical to `quietMove`; named for test readability).
Helpers never press buttons — confirmation stays an explicit caller action.

#### 2. Demo end-to-end test

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoardEndToEndTest.kt`

**Intent**: The F-02 acceptance proof and S-06's copy-paste example: a scripted fragment
exercising every §1.3 message type and both research-mandated ordering variants, asserting the
exact ordered typed-event stream received through the port (which, by the Phase 3 pipeline, means
through §1.3 bytes).

**Contract**: One scenario (plus minimal variants if clearer), with the board constructed using
`statusInterval = Duration.INFINITE` so the periodic ~30 s `DEVICE_STATUS` never interleaves with
the asserted ordered stream, covering: connect from start-position occupancy (assert snapshot +
status); quiet move + white button; reply + black
button; a capture with `CAPTURED_FIRST` and another with `MOVER_FIRST`; a castle variant; an
`adjust` no-op pair; a `promotionPush` followed by a button press *before* any promotion choice
(asserting the button event arrives — the blocking rule §1.5 belongs to S-06, the stream just
must carry the events); `SetMode(DIAGNOSTIC)` + virtual-time advance asserting ~10 Hz snapshots
then back to GAME; `disconnect()`, offline `lift`/`place`, `connect()` asserting the reconnect
snapshot reflects the offline change (the S-08 shape). Assertions compare full ordered event
lists, not loose contains-checks.

#### 3. Cross-target green

**Intent**: The emulator ships to all targets `shared` ships to; prove it (WasmJS compiles and
runs it even though web never wires physical mode — no BLE dependency exists, per lessons.md the
restriction is on BLE, not on pure Kotlin).

**Contract**: No code change; verification step running the full suite on iOS-sim and WasmJS in
addition to host.

### Success Criteria:

#### Automated Verification:

- Full suite green on host: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest --console=plain --no-daemon`
- Green on iOS sim: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:iosSimulatorArm64Test --console=plain --no-daemon`
- Green on WasmJS: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:wasmJsTest --console=plain --no-daemon`
- Formatting clean: `cd SmartChessboard && ktlint -F`

#### Manual Verification:

- Read the demo test as if writing S-06: subscribing, scripting, asserting all reachable from
  public API; the scenario reads as documentation.
- Confirm both capture orders and a castling interleaving genuinely appear in the asserted event
  streams (the research-mandated variants are exercised, not just available).

**Implementation Note**: This is the final phase; after it passes, F-02 is complete. Pause for
manual confirmation.

---

## Testing Strategy

### Unit Tests:

- **Phase 2**: golden-frame vectors per message type (hand-derived, §-cited), edge squares 0/63,
  empty/full/start snapshots, LE uptime boundary, malformed frames (unknown tag, truncation,
  reserved bits). Round-trip checks only as a supplement.
- **Phase 3**: behavior matrix of the emulator — lifecycle, guards, offline mutations, command
  surface, periodic jobs on virtual time.

### Cross-target cadence:

- iOS-sim (`iosSimulatorArm64Test`) runs alongside host from Phase 2 onward — Kotlin/Native is the
  target most likely to diverge on the codec's signed-`Byte` / `Long` bit handling and on
  coroutines-test virtual time, and physical-board mode ships on iOS. WasmJS is the **optional**
  final cross-target check at Phase 4 (web never wires physical mode; the emulator runs there only
  because it is pure Kotlin) — catching a Native divergence early is worth a phase; a Wasm one is not.

### Integration Tests:

- **Phase 4 demo end-to-end** is the integration proof: script → primitives/helpers → encode →
  bytes → decode → port stream, asserted as exact ordered event lists across every message type
  and lifecycle transition.

### Manual Testing Steps:

1. Hand-verify 3–4 golden vectors against §1.3 (especially the snapshot byte order).
2. Read the emission pipeline for shortcut paths (there must be none).
3. Read the demo test as S-06's author; confirm the API needs no internals.

## Performance Considerations

None material. Frames are 1–9 bytes; the heaviest behavior is a 10 Hz snapshot loop on a virtual
clock in tests. `eventDelay` defaults to zero so the suite stays fast; periodic jobs cancel on
disconnect to avoid leaked coroutines (use the injected scope's structured concurrency).

## Migration Notes

Purely additive in code — new `domain/board/` (port, `commonMain`) and `data/board/protocol/`
(codec, `commonMain`) packages, plus `data/board/emulator/` (emulator + scenarios) in the
`commonTest` source set; no existing file changes, no schema, no dependencies, no DI. The emulator
sits in `commonTest` because no production consumer exists yet (F-01 precedent); it promotes to
`commonMain` when S-06 wires a dev screen — a same-package source-set move, no API change. Three
documentation files change under the contract's own change-control:
`docs/reference/contract-surfaces.md` (§1.3 bit-packing sentence + `updated` bump),
`context/foundation/prd-firmware.md` (dated mirror line), and `context/foundation/prd.md` (one
minimal dated Implementation-Decisions line, no user-facing impact).

## References

- Roadmap item F-02: `context/foundation/roadmap.md`
- Change identity: `context/changes/reed-board-emulator/change.md`
- Research (dumb-board validation + injection-layer verdict): `context/changes/reed-board-emulator/research.md`
- BLE protocol contract §1: `docs/reference/contract-surfaces.md`
- PRD OQ-1 resolution (emulator strategy, no GUI): `context/foundation/prd.md`
- Firmware validation strategy: `context/foundation/prd-firmware.md`
- Square convention: [Square.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/chess/Square.kt)
- Architecture & test discipline: `context/foundation/tech-stack.md`, `context/foundation/lessons.md`
- Sibling foundation plan (conventions mirrored): `context/changes/chess-rules-engine/plan.md`

## Progress

> Convention: `- [x]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Board domain port & event model

#### Automated

- [x] 1.1 Module compiles on all three targets: `:shared:compileKotlinWasmJs` + `compileKotlinIosSimulatorArm64` + `compileAndroidMain` — 42ee77b
- [x] 1.2 Formatting clean: `ktlint -F` — 42ee77b

#### Manual

- [x] 1.3 Public types read as a consumable contract for S-06 / plausible S-09 target, no chess vocabulary — 42ee77b
- [x] 1.4 Event/command vocabulary maps 1:1 onto §1.3/§1.4 — 42ee77b

### Phase 2: Wire codec & golden frames

#### Automated

- [x] 2.1 Codec + golden-frame tests pass on host + iOS-sim: `:shared:testAndroidHostTest` + `:shared:iosSimulatorArm64Test` — f3bd9d1
- [x] 2.2 Formatting clean: `ktlint -F` — f3bd9d1

#### Manual

- [x] 2.3 Golden vectors spot-checked against §1.3 by hand (incl. start-position snapshot bytes)
- [x] 2.4 Contract edit minimal + both PRD mirror lines (firmware + product) present and dated

### Phase 3: Emulated board core

#### Automated

- [x] 3.1 Emulator behavior tests pass on host + iOS-sim: `:shared:testAndroidHostTest` + `:shared:iosSimulatorArm64Test` — 465f5d7
- [x] 3.2 Formatting clean: `ktlint -F` — 465f5d7

#### Manual

- [x] 3.3 Emission pipeline confirmed: every event passes encode → decode, no shortcut path
- [x] 3.4 Disconnect semantics match §1.7: offline changes surface only via reconnect snapshot

### Phase 4: Scenario helpers, demo end-to-end, cross-target green

#### Automated

- [x] 4.1 Full suite green on host: `:shared:testAndroidHostTest` — 7f78e6f
- [x] 4.2 Green on iOS sim: `:shared:iosSimulatorArm64Test` — 7f78e6f
- [x] 4.3 Green on WasmJS: `:shared:wasmJsTest` — 7f78e6f
- [x] 4.4 Formatting clean: `ktlint -F` — 7f78e6f

#### Manual

- [x] 4.5 Demo test reads as S-06 documentation; public API sufficient
- [x] 4.6 Both capture orders + a castling interleaving exercised in asserted streams
