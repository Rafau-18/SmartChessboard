# F-02 Reed-Board Emulator — End-of-Slice Manual Acceptance

Run this checklist **once, after all four phases are implemented and the full suite is green on
all three targets**. It consolidates the per-phase manual-verification items from `plan.md` into a
single end-to-end acceptance pass, so the contract fidelity is re-confirmed holistically rather
than only phase-by-phase. Tick a box only after eyeballing the actual code/output — a green test
with a wrong hand-derived vector proves nothing.

Source of truth for the wire format: `docs/reference/contract-surfaces.md` §1.3–§1.7.

## 1. Domain port (Phase 1)

- [ ] `domain/board/` public types read as a consumable contract: a consumer can subscribe to
      `events`, drive a board, and assert — and the same surface is a plausible target for an S-09
      BLE adapter. No chess vocabulary leaks in (occupancy/square only, never piece/move).
- [ ] Event/command vocabulary maps 1:1 onto §1.3/§1.4: four board→mobile
      (`BoardSnapshot`, `SquareEvent`, `ButtonEvent`, `DeviceStatus`), three mobile→board
      (`SetMode`, `RequestSnapshot`, `RequestStatus`). No missing message, no extra.

## 2. Wire codec & golden frames (Phase 2)

- [ ] Spot-check 3–4 golden vectors against §1.3 **by hand**, including:
      - start-position snapshot bytes `[0x01, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF]`
      - place on e4 → square 28 → `[0x02, 0x5C]`; lift e2 → `[0x02, 0x0C]`
- [ ] Snapshot bit-packing is locked and consistent everywhere: byte `i` bit `j` (LSB-first) =
      square `i*8 + j` (byte 0 = rank 1, a1 = byte 0 bit 0).
- [ ] Contract edit in `contract-surfaces.md` §1.3 is the minimal one-sentence clarification and
      the frontmatter `updated` is bumped.
- [ ] Both PRD mirror lines present and dated: `prd-firmware.md` (snapshot bit-packing) and
      `prd.md` Implementation-Decisions (no user-facing impact pointer).

## 3. Emulator core (Phase 3)

- [ ] Read the emission pipeline: every emitted event genuinely passes
      typed event → `encodeEvent` → bytes → `decodeEvent` → emit. There is **no** typed-event
      shortcut path that bypasses the codec.
- [ ] Disconnect semantics match §1.7: offline `lift`/`place` mutate occupancy but emit nothing;
      the divergence surfaces **only** via the snapshot emitted on reconnect.
- [ ] `send` while disconnected throws `IllegalStateException`; `pressButton` while disconnected is
      a silent no-op; consistency guards (lift-empty / place-occupied / setOccupancy-while-connected)
      throw immediately.
- [ ] Mode resets to GAME on every (re)connect.

## 4. Scenario helpers & demo end-to-end (Phase 4)

- [ ] Read the demo end-to-end test as if authoring S-06: subscribing, scripting, and asserting are
      all reachable from the public API; the scenario reads as living documentation, no internals
      needed.
- [ ] Both capture orders (`CAPTURED_FIRST`, `MOVER_FIRST`) and at least one castling interleaving
      genuinely appear in the asserted ordered event streams (the research-mandated variants are
      exercised, not merely available).
- [ ] The demo asserts the S-08 shape: disconnect → offline lift/place → reconnect snapshot
      reflects the offline change.

## 5. Cross-target green (final)

- [ ] `:shared:testAndroidHostTest` green
- [ ] `:shared:iosSimulatorArm64Test` green (Kotlin/Native is the likeliest divergence point for
      signed-`Byte`/`Long` bit handling and coroutines-test virtual time)
- [ ] `:shared:wasmJsTest` green (the emulator is pure Kotlin; web never wires physical mode, but it
      must still compile and run there)
- [ ] `ktlint -F` reports no violations
