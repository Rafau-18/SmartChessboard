# Smart Chessboard

Software for a DIY smart chessboard: an ESP32-based reed-switch board streams piece
movements over Bluetooth LE to a Kotlin Multiplatform app, which turns them into a
complete, legal, replayable chess record with post-game engine analysis — backed by
Supabase.

The point: play on a real wooden board (or on screen) and never lose a game again.
Every accepted move is validated against the full rules of chess, saved as PGN, and
can be replayed and analyzed after the game.

## Features

- **Google sign-in** with a private, per-user game history (Supabase Auth + Postgres RLS).
- **Digital pass-and-play** on Android, iOS, and web — full legality validation
  (check, pins, castling, en passant, promotion), auto-detected checkmate/stalemate,
  crash-safe auto-save (local write-ahead journal + cloud backup).
- **Physical-board play** (Android + iOS) — piece lifts/places streamed over BLE are
  resolved into legal moves and confirmed with per-side chess-clock buttons; illegal
  or ambiguous sequences are rejected with a diagnostics-assisted recovery path, and
  an in-progress game survives app restarts and BLE drops.
- **Live reed-switch diagnostics** — per-square occupancy view of the real board.
- **Replay & analysis** — step through any saved game; position evaluations and best
  moves via Lichess Cloud Eval with a Stockfish fallback (Chess-API.com), cached
  server-side per position.
- **History management** — hard delete with confirmation; new accounts start with
  eight famous historical games pre-seeded.

## How it fits together

```
┌──────────────┐  BLE GATT    ┌───────────────────────────┐  HTTPS    ┌─────────────────┐
│ ESP32 board  │─────────────>│ KMP app                   │──────────>│ Supabase Cloud  │
│ 8×8 reeds    │ lifts/places │ Android · iOS · Web(Wasm) │ REST/auth │ Postgres + RLS  │
│ 2 buttons    │ + confirms   │ rules engine · PGN · UI   │           │ Auth · Edge Fn  │
└──────────────┘              └───────────────────────────┘           └─────────────────┘
```

- The board is a **dumb sensor** — it reports raw square transitions and button
  presses only; the app derives the moves and enforces chess legality.
- **PGN is the source of truth** for every game; FEN is derived for replay/analysis.
- The backend is **configuration, not code**: Postgres schema + RLS + one Edge
  Function. Clients call Supabase directly — there is no bespoke server.
- The contract between the three parts (BLE protocol, API shapes, schema) lives in
  [`docs/reference/contract-surfaces.md`](docs/reference/contract-surfaces.md).

## Repository layout

| Path | What it is |
| --- | --- |
| [`SmartChessboard/`](SmartChessboard/README.md) | Kotlin Multiplatform app — Compose UI, chess rules engine, BLE client |
| [`firmware/`](firmware/README.md) | ESP32 firmware — reed-matrix scan + BLE GATT peripheral |
| [`supabase/`](supabase/README.md) | Backend as code — migrations, RLS, `lichess-eval` Edge Function |
| [`context/`](context/) | Product docs driving the workflow: [PRD](context/foundation/prd.md), [firmware PRD](context/foundation/prd-firmware.md), [tech stack](context/foundation/tech-stack.md), [roadmap](context/foundation/roadmap.md), per-change plans |
| [`docs/`](docs/) | Cross-cutting reference — most importantly [`contract-surfaces.md`](docs/reference/contract-surfaces.md) |

Each sub-project README covers its own setup, build, run, and test instructions.
The `AGENTS.md` files (root + one per sub-project) add rules for AI coding agents
and contributors.

## Status

All MVP roadmap slices are implemented and verified on real hardware, including
physical play over BLE. See the [roadmap](context/foundation/roadmap.md) for
per-slice status and what remains (BLE connectivity hardening).
