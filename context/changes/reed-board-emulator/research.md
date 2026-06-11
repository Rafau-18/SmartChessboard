---
topic: "Reed-board emulator: where move detection lives & at which layer the emulator injects events"
change_id: reed-board-emulator
researcher: claude (3 parallel web-research agents)
date: 2026-06-11
status: complete
---

# Research: Smart-board move detection & emulator injection layer

## Questions

1. Should the board (firmware) pre-detect moves, or stream raw occupancy events with the host
   (mobile app) doing move interpretation? (Validates / challenges contract §1.3's "dumb board" model.)
2. At which layer should the F-02 emulator inject events — binary wire messages (§1.3 bytes through
   a shared codec) or typed Kotlin domain events?

## Method

Three parallel web-research agents: (1) commercial smart-board protocols (DGT, Certabo, Chessnut,
Millennium, Square Off, DGT Pegasus), (2) DIY occupancy-only (reed/hall) board projects and their
move-detection strategies, (3) BLE no-hardware testing practice (fake layering, GOOS
"don't mock what you don't own", Kable testability, golden-vector precedents).

## Finding 1 — Industry consensus: board streams state, host detects moves

Every commercial board with a public or reverse-engineered protocol transmits **board state
(per-square contents), not moves**; move detection, rules, and special-move resolution live in the
host software:

| Board | Sensing | Wire payload | Move detection |
|---|---|---|---|
| DGT e-Board | piece identity (LC) | board dumps + per-square field updates | host |
| Certabo | piece identity (RFID) | raw 64×5-byte chip-ID stream | host (incl. noise filtering) |
| Chessnut Air | piece identity | 32-byte full state on every change | host (FEN callback API only) |
| Millennium ChessLink | piece identity | 64 piece-code chars | host |
| Millennium eOne | **occupancy only** + 3 buttons | ChessLink-style states | host |
| DGT Pegasus | **occupancy only** | move committed early | board/app pre-commit — documented failure modes |
| Square Off GKS | occupancy + press | **move strings** (`xd2d4z`) | app holds all rules ("board is dumb") |

Key evidence:

- DGT's own protocol header warns square updates arrive in counter-intuitive order and "some effort
  needs to be made to reconstruct the actual moves" — host-side reconstruction is by design
  (dgtbrd protocol header via picochess: https://github.com/well69/picochess-1/blob/master/test/dgtbrd-ruud.h).
- Boards that pair lift/place into moves **before** the rules engine sees them ship documented
  downsides: DGT Pegasus manual — slow slides register intermediate squares and "since the move was
  sent to the platform, it cannot be corrected afterwards"; capture choreography imposed on players
  (https://gambitchesssupplies.com.au/content/manuals/dgt/22010.pdf). Millennium eOne manual —
  dragged captures produce no destination sensor change and error out; nudging an adjacent piece can
  register as a move if it happens to be legal.
- The eOne (occupancy + buttons) is the closest commercial analogue to our design; its extra buttons
  exist precisely for the one case occupancy + rules cannot resolve (underpromotion) — mirroring our
  §1.5 in-app promotion picker.

**Conclusion 1:** contract §1.3's "dumb board, smart phone" split matches the industry norm, and the
counter-examples validate the PRD's FR-008 rejection of early move-commit. No contract change needed.

## Finding 2 — DIY occupancy-only precedents: sequence/event-order interpretation on host; recovery path is load-bearing

- **Autopatzer** (closest DIY precedent; hall sensors, occupancy-only): board daemon emits typed
  `pieceup e2` / `piecedown e4` events + on-demand full scans; host pattern-matches lost/gained sets
  against the expected position, with **event order** disambiguating captures; physical button
  commits the move (https://github.com/jes/autopatzer,
  https://incoherency.co.uk/blog/stories/autopatzer.html). Unrecognized patterns return undef — no
  recovery, a gap our FR-010/FR-011 diagnostics path fills.
- **Capture-on-occupancy ambiguity is real and documented**: "one square loses a piece, but no
  square gains a piece … so the move is ambiguous"
  (https://incoherency.co.uk/blog/stories/automatic-chess-board-design.html). Mitigations seen in the
  wild: catch the brief capture "blip" via continuous scanning (≈ our sequence capture), magnet
  polarity per color (eChess, https://github.com/aherve/eChess), or constraining the player's
  movement order (OPENCHESSBOARD — capture = lift own piece first;
  https://github.com/TimoKropp/OPENCHESSBOARD_WiFi). Our sequence-based contract is the
  player-friendly variant.
- **certabo-lichess** reconciliation pattern: enumerate `generate_legal_moves()`, apply each to a
  copy, compare resulting placement to the sensed state (depth ≤ 2) — captures/castling/en
  passant/promotion fall out of the chess library automatically
  (https://github.com/haklein/certabo-lichess). Generalizes better than fixed pattern tables —
  informs S-06 interpreter design (not F-02 scope).
- **ArdEBoard abandoned its reed variant** over unrecoverable board states ("how to recover if you
  accidentally knock over some or all pieces") — evidence that the snapshot/resync command and the
  diagnostics-assisted recovery path are load-bearing, not nice-to-have
  (https://github.com/asdfjkl/ArdEBoard).
- **Emulator precedents exist at both layers**: Autopatzer's `mock-autopatzerd` fakes at the typed
  JSON layer; **DGTCentaurMods impersonates commercial boards at the wire-protocol/bytes layer** so
  unmodified vendor apps connect to it (https://github.com/DGTCentaurMods/DGTCentaurMods).

**Conclusion 2:** any-order interleaving support in the emulator's scenario API (captures both
orders, castling permutations, j'adoube no-ops, button-before-promotion-pick) is required by
real-world player behavior, and snapshot/resync must be first-class.

## Finding 3 — BLE no-hardware testing practice: fake at the GATT/byte layer, keep a typed port as the seam

- "Don't mock what you don't own" (GOOS) targets *other people's* APIs (CoreBluetooth,
  BluetoothGatt, Kable). **Our wire protocol is owned** — we author §1.3 — so a byte-level emulator
  is a fake of an owned contract behind our own port, not a violation
  (https://martinfowler.com/bliki/IntegrationContractTest.html,
  https://medium.com/trainline/steve-freeman-on-ports-and-adapters-in-system-testing-f21e584966ce).
- Mainstream no-hardware BLE tooling uniformly fakes at the GATT/byte level: Nordic
  CoreBluetoothMock (mock peripherals answer with `Data` bytes; marketed for app dev before firmware
  exists — https://github.com/NordicSemiconductor/IOS-CoreBluetooth-Mock), Punch Through LightBlue
  Virtual Devices (with the explicit caveat: protocol simulation ≠ hardware testing —
  https://punchthrough.com/lightblue-virtual-devices/), Google Bumble virtual peripherals against
  the Android emulator (https://google.github.io/bumble/).
- **Kable** (our planned BLE library) ships no test doubles but made `Peripheral` an interface
  specifically for fakeability (issues #474/#802, release 0.36.0 —
  https://github.com/JuulLabs/kable/releases/tag/0.36.0). A fake Kable `Peripheral` is itself
  byte-level (characteristics carry `ByteArray`) — one seam below a domain port.
- **Self-consistency trap** (the key honest downside): if the emulator encodes with the same codec
  the app decodes with, round-trips pass even when both are wrong vs the spec. Mitigation =
  **hand-written golden byte frames** derived from §1.3 literally (the crypto test-vector
  discipline: external vectors so implementations can't validate themselves against their own bugs —
  https://cryptography.io/en/latest/development/test-vectors/). Later: replay frames captured from
  real hardware (Fowler's SelfInitializingFake).
- Residual risks a byte-level emulator does NOT cover: MTU negotiation, one-GATT-op-at-a-time
  queueing, status-133 disconnects, OEM stack variance (https://punchthrough.com/android-ble-guide/).
  With 1–8-byte frames, framing/MTU risk is near-zero for this protocol; residual risk concentrates
  in connection lifecycle → covered later by a thin, integration-tested S-09 transport adapter.

**Conclusion 3 (verdict):** the A-vs-B dichotomy resolves as **"B's shape with A's substance"** —
define the typed domain port (consumers see typed events; GOOS demands the port regardless), but the
emulator behind it produces **§1.3 byte frames flowing through the shared codec**, pinned by
hand-written golden vectors independent of the codec. The future S-09 BLE adapter swaps in at the
transport line and reuses codec + port; everything above is untouched.

## Implications for the F-02 plan

1. Keep contract §1.3 unchanged — research validates the dumb-board model; no firmware intelligence.
2. Architecture: typed `domain` port (board events) as the consumer seam; emulator internally
   emits §1.3 bytes → shared codec → typed events. Codec is a first-class deliverable of F-02.
3. Golden byte fixtures hand-derived from §1.3 (not codec round-trips) pin encoder and decoder
   independently.
4. Emulator scenario API must support: capture lift-order variants, castling interleavings,
   j'adoube/no-op pairs, spurious-event injection via primitives, button-before-promotion-pick,
   disconnect/reconnect with snapshot-on-connect.
5. Snapshot/resync is first-class (ArdEBoard failure precedent; FR-FW-005/§1.7).
6. S-06 interpreter (out of F-02 scope) should prefer certabo-style legal-move-set matching over
   fixed pattern tables — note carried forward to that slice's planning.

## Sources

Primary links inline above. Agent transcripts available in session history (agents:
a1f00b6f0581a7628 commercial protocols, a705379701d4d31b8 DIY projects, a8056e1713b5d902f BLE
testing practice).
