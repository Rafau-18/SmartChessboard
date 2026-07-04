# Reed-Switch Board Emulator (F-02) — Plan Brief

> Full plan: `context/changes/reed-board-emulator/plan.md`
> Research: `context/changes/reed-board-emulator/research.md`

## What & Why

A programmatic emulator of the physical reed-switch chessboard: it produces the same event
stream as the real board will over BLE (contract §1.3), drivable from test scripts — so the
entire physical-mode flow (S-06 capture, S-07 diagnostics/recovery, S-08 resume) can be built and
verified end-to-end with no hardware, in CI, today. Resolves PRD OQ-1's chosen no-hardware
validation strategy; roadmap foundation F-02.

## Starting Point

The app has zero board/BLE/transport code. The parked firmware only proves scanning + debouncing
on serial output — it emits no messages, so **contract §1.3 is the sole source of truth for the
wire format**. F-01 Phase 1 already locked the shared square convention (`index = file + 8*rank`,
a1 = 0) in `domain/chess/Square.kt`.

## Desired End State

A typed `BoardConnection` port delivers board events to consumers; behind it, `EmulatedBoard`
emits every event as §1.3 **bytes through a shared codec** (the same decoder the S-09 BLE adapter
will reuse). Scenario helpers script realistic sequences (both capture orders, castling
interleavings, j'adoube, promotion push). A demo end-to-end test plays a game fragment and
asserts the exact event stream — green on Android-host, iOS-sim, and WasmJS.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Board vs host intelligence | Dumb board, smart phone (contract §1.3 unchanged) | Industry consensus (DGT/Certabo/Chessnut/Millennium stream state, host detects moves); early-move-commit boards have documented failure modes. | Research |
| Emulator injection layer | Typed port as seam + bytes through shared codec underneath | Tests exercise the same decode path radio bytes will take in S-09; "message-identical" becomes executable, not declarative. | Research |
| Drift guard | Hand-written golden byte frames (never codec-generated) | A shared codec bug cannot validate itself (crypto test-vector discipline). | Research |
| Catalog scope | Full §1.3–§1.6 incl. diagnostic mode + DEVICE_STATUS | S-06/S-07/S-08 get a closed foundation; no later slice reopens the emulator. | Plan |
| Connection lifecycle | Modeled (connect→snapshot, disconnect, offline mutations, reconnect) | S-08 resume and §1.7 reconcile flows become testable without hardware. | Plan |
| Driving API | Primitives + chess-agnostic sequence helpers with ordering variants | Real players vary capture/castling order (research-confirmed); helpers keep S-06 tests readable without coupling to unfinished F-01. | Plan |
| Time model | Virtual-time coroutines (injectable scope) | Deterministic fast tests for 10 Hz diagnostics and ~30 s status; stack already has kotlinx-coroutines-test. | Plan |
| Noise simulation | Via primitives only (no generator, no fixtures) | A sensor blip has the same shape as j'adoube — primitives already cover it; fixtures stay OQ-1 nice-to-have. | Plan |
| Done criterion | Golden frames + behavior tests + demo e2e + 3-target green | Proves the message-identical claim end-to-end and hands S-06 a usage example. | Plan |

## Scope

**In scope:** `domain/board/` port + event/command types; `data/board/protocol/` codec for all 7
message types + golden-frame tests; `data/board/emulator/` EmulatedBoard (occupancy state machine,
lifecycle, guards, periodic behaviors) + scenario helpers; demo e2e test; §1.3 snapshot
bit-packing clarification written back into `contract-surfaces.md` (+ dated mirror in
`prd-firmware.md`).

**Out of scope:** sequence interpreter (S-06); BLE/Kable (S-09); any GUI (PRD OQ-1); Koin wiring
(no consumer yet); noise generator; recorded-fixture replay; firmware changes.

## Architecture / Approach

```
script / future dev tool
        │ primitives + scenario helpers
        ▼
 EmulatedBoard (occupancy, lifecycle, clock)   data/board/emulator/
        │ encodeEvent → §1.3 bytes → decodeEvent     data/board/protocol/  ← golden frames
        ▼
 BoardConnection port (typed events, commands)  domain/board/
        ▼
 consumers: S-06 interpreter, S-07 diagnostics UI … (later)
```

S-09 swaps the emulator for a Kable-based adapter under the same port, reusing the same codec.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Board domain port & event model | Frozen typed contract (events, commands, port) | Vocabulary drift vs §1.3 — mitigated by 1:1 mapping review |
| 2. Wire codec & golden frames | Executable §1.3/§1.4 + bit-packing locked into contract | Wrong hand-derived vector — mitigated by manual spot-check gate |
| 3. Emulated board core | Lifecycle, guards, offline mutations, periodic behaviors | Shortcut path skipping the byte pipeline — explicit review gate |
| 4. Helpers + demo e2e + cross-target | Scenario DSL, acceptance proof, 3-target green | Demo asserting too loosely — exact ordered-list assertions required |

**Prerequisites:** none (parallel to F-01; uses only its committed Phase-1 Square convention).
**Estimated effort:** ~2–3 sessions across 4 phases.

## Open Risks & Assumptions

- Emulator fidelity bounds hardware surprises (roadmap risk): mitigated by byte-level pipeline +
  golden frames, but radio realities (MTU, GATT queueing, status-133) stay out of scope until
  S-09's thin adapter — accepted residual risk.
- §1.3 snapshot byte order was underspecified; this plan locks it docs-first. Firmware (parked)
  must implement the same packing when it resumes — the contract edit is the coordination point.
- `events` is a hot no-replay stream: consumers must subscribe before driving; documented on the
  port and modeled in the demo test.

## Success Criteria (Summary)

- Hand-derived golden frames match encoder and decoder byte-for-byte for every §1.3/§1.4 message.
- A scripted game fragment (both capture orders, castling, promotion push, diagnostic mode,
  disconnect → offline change → reconnect snapshot) yields the exact expected typed-event stream
  through the port — i.e. through §1.3 bytes.
- Full suite green on `testAndroidHostTest`, `iosSimulatorArm64Test`, and `wasmJsTest`.
