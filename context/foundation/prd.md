---
project: "Smart Chessboard"
version: 1
status: draft
created: 2026-05-26
updated: 2026-06-19
context_type: greenfield
product_type: mobile
target_scale:
  users: small
  qps: low
  data_volume: small
timeline_budget:
  mvp_weeks: 4
  hard_deadline: null
  after_hours_only: false
companion_documents:
  firmware_prd: context/foundation/prd-firmware.md
  contracts: docs/reference/contract-surfaces.md
---

## Vision & Problem Statement

The project author and a small circle of friends play chess on a physical wooden board for enjoyment. Those games usually disappear when they end: they are not automatically recorded, cannot be replayed from a precise position, and cannot be analyzed later unless someone interrupts the flow of play with manual notation.

Commercial smart chessboards solve parts of this problem, but they are expensive and closed for this use case. The author already has a physical prototype with a reed-switch matrix and a microcontroller, so the product opportunity is a software layer that turns the existing board into an analysis-ready game recorder for a small group of real players.

The first useful smart-board experience is not a live engine overlay during play; it is a complete, legal, replayable game record after play. A canonical game record lets players return to their own games and transform positions into evaluated states without changing the physical feel of the game.

## User & Persona

Primary persona: the project author and a small circle of friends who play physical chess at home or in small informal settings. They are amateur chess players, not tournament operators, coaches, or club administrators.

Each player wants their own account, their own game history, and the ability to return to their own games for replay and analysis. The MVP serves this small-circle use case first; it is not trying to become a mass-market chess platform.

## Success Criteria

### Primary

- A signed-in player can complete a digital pass-and-play game on iOS or Android, save every accepted move into a complete PGN source record, reopen the game from their own history, replay it with full navigation controls, and view post-game position evaluations.
- A signed-in player can play through the physical board flow: moves are captured as confirmed sequences of piece lifts and placements, assigned to the correct side through two confirmation buttons, validated against full chess legality, and saved into the same canonical game record used by the digital flow.

### Secondary

- A player can inspect live reed-switch diagnostics for every square to understand which board fields currently detect a piece or magnet.
- A player can use a web target for the digital subset — pass-and-play, history, replay, and post-game analysis — without the physical-board / BLE flow. Partial parity for digital flows is acceptable for MVP.
- The product can recover gracefully from physical-board network loss by pausing move acceptance, showing a clear message, and resuming from the last confirmed move after reconnect.

### Guardrails

- The product never saves an illegal chess move. Validation covers the full rules needed for legal play, including check, pinned-piece movement, king safety, castling, en passant, and promotion.
- The product does not silently corrupt physical-board input. Every confirmed physical-board state results in either a specific legal move being saved or a visible error path that asks the player to correct the board state.
- A crash must not erase moves that were already accepted and saved before the crash.
- The core digital pass-and-play, save, replay, and post-game analysis flow works without connected physical hardware.

## User Stories

### US-01: Digital game is recorded, replayed, and analyzed

- **Given** a signed-in player starts a digital pass-and-play game on iOS or Android
- **When** the players make legal moves on the on-screen board and the game history is saved as play progresses
- **Then** the player can reopen the game from their own chronological history, replay it with full move controls, and view post-game position evaluations.

#### Acceptance Criteria

- The player can create a game, choose digital mode, and assign White and Black.
- The on-screen board accepts interactive moves and validates every move against full chess rules before execution.
- Pawn promotion asks the player to choose the promoted piece before the move is saved.
- Every accepted move is persisted into the game record as play progresses.
- The saved game uses complete PGN as its source of truth, with FEN derived, generated, or cached only as needed for replay or analysis.
- The player can navigate replay with start, back, forward, and end controls.
- The player can request post-game analysis and view position evaluations while reviewing the game.

### US-02: Physical board moves enter the canonical game record

- **Given** a signed-in player has started a physical-mode game with the smart board available
- **When** players make moves on the board and confirm each move with the button on their side
- **Then** the product interprets the recorded sequence of lifts and placements as the next candidate move, validates it, and saves accepted moves into the same canonical game record as digital play.

#### Acceptance Criteria

- The player can create a game, choose physical mode, and assign White and Black.
- The board flow tracks the sequence of piece lifts and placements since the previous confirmed move.
- A confirmation button exists for each side; the pressed button identifies which side confirmed the move.
- Captures and castling are recognized from the full sequence, not from a final board snapshot alone.
- If the sequence is illegal, ambiguous, or inconsistent with the expected position, no move is saved.
- A visible error path asks the player to restore the previous legal position with help from live diagnostics and retry confirmation.
- Accepted physical-board moves appear in the game history and replay sequence.

### US-03: Player reviews their own saved games

- **Given** a signed-in player has at least one saved game
- **When** they open their game history and choose a game
- **Then** they see only their own games, can replay the selected game, and can review position evaluations after play.

#### Acceptance Criteria

- The game list is scoped to the signed-in player and ordered chronologically.
- Unauthenticated users cannot access game, history, replay, or analysis views.
- The chosen game opens at a reproducible board state.
- Replay controls let the player jump to the start, step backward, step forward, and jump to the end.
- Post-game position evaluation is available from the replay/review flow.

## Functional Requirements

### Account Access

- FR-001: Player can create or sign into an account through an external OAuth identity provider selected during downstream stack selection. Priority: must-have
  > Socrates: Counter-argument considered: "Choosing a specific provider too early can over-commit the product before stack selection." Resolution: revised; OAuth remains must-have, but the provider is deferred downstream instead of hard-coding a provider in the shape notes.

- FR-002: Player can sign out of their account. Priority: nice-to-have
  > Socrates: Counter-argument considered: "Logout is not central to the first chess flow and may be rarely used on a personal device." Resolution: kept as nice-to-have; it is expected account behavior but does not block MVP acceptance.

### Game Creation And Digital Play

- FR-003: Player can create a new game, choose digital or physical play mode, and assign White and Black. Priority: must-have
  > Socrates: Counter-argument considered: "Mode and color assignment could be inferred automatically." Resolution: kept as must-have; explicit game setup removes ambiguity before the first move.

- FR-004: Player can make moves interactively on an on-screen chessboard. Priority: must-have
  > Socrates: Counter-argument considered: "A generic pass-and-play requirement might be enough." Resolution: added as must-have; the digital MVP needs an explicit input capability before recording, replay, or analysis can be validated.

- FR-005: System can validate every move against the full rules of chess before execution, including check, pinned-piece movement, king safety, castling, en passant, and promotion. Priority: must-have
  > Socrates: Counter-argument considered: "Full chess validation can slow the first version, and partial validation could be enough for a prototype." Resolution: strengthened as must-have; saving illegal moves would break the canonical game record.

- FR-006: Player can choose the promoted piece when a pawn promotes through the mobile UI (iOS/Android), regardless of whether the move originated from the digital board or the physical board. In physical mode, board input is blocked after a promotion push is detected until the player selects the promoted piece in the app and the side confirms the move. Priority: must-have
  > Socrates: Counter-argument considered: "Auto-promoting to queen would reduce UI work, and physical hardware cannot distinguish piece identity anyway." Resolution: kept as must-have; the mobile UI resolves the piece-identity gap of the reed-switch matrix and keeps PGN faithful.

- FR-007: System automatically detects checkmate and stalemate as terminal game states and closes the game with the corresponding result. Detection follows from the legality engine: zero legal moves with check is checkmate, zero legal moves without check is stalemate. Other draw-by-rule conditions (threefold repetition, 50-move, insufficient material) are not auto-detected in MVP and must be marked manually via FR-018. Priority: must-have
  > Socrates: Counter-argument considered: "Auto-detecting all draw rules would be more correct." Resolution: scoped to mate/stalemate; these fall out of the legality engine for free, while remaining draw rules require additional state tracking (move counters, position hash history) that does not pay off in casual play.

### Physical Board Play

- FR-008: Player can submit physical-board moves through a confirmed sequence of piece lifts and placements recorded since the previous confirmed move. Priority: must-have
  > Socrates: Counter-argument considered: "A button-triggered final board snapshot would be simpler." Resolution: revised to continuous sequence capture; captures and castling cannot be resolved reliably from a final snapshot alone.

- FR-009: Physical board flow can distinguish two confirmation buttons organized like a chess clock, one for each side. Priority: must-have
  > Socrates: Counter-argument considered: "A single button could confirm the stable state and color could follow turn order." Resolution: added as must-have; two side-specific buttons reduce ambiguity about who confirmed the move.

- FR-010: System can reject illegal, ambiguous, or inconsistent physical-board sequences, pause the game, and ask the player to manually restore the previous legal position with diagnostic assistance before retrying confirmation. Priority: must-have
  > Socrates: Counter-argument considered: "A generic reject-invalid-state rule might be enough." Resolution: added as must-have; hobbyist reed-switch hardware needs an explicit visible recovery path.

- FR-011: Player can view live reed-switch diagnostics for every square. Priority: must-have
  > Socrates: Counter-argument considered: "Live diagnostics may be only a debugging tool." Resolution: promoted to must-have; with imperfect physical detection, players need visible board-state support when resolving errors.

- FR-012: Product can handle physical-board BLE disconnect by pausing move acceptance, showing an unambiguous connection-loss message, attempting automatic reconnect, and resuming from the last confirmed move after reconnect. On reconnect, the app reconciles the board state against the expected position derived from PGN; mismatch routes the player into the diagnostic-restore path (FR-010/FR-011). See `contract-surfaces.md` §1.7 for the full BLE disconnect/reconnect semantics. Priority: nice-to-have
  > Socrates: Counter-argument considered: "The MVP can rely on persisted last confirmed moves and manual resume." Resolution: added as nice-to-have; it improves physical-game continuity but does not block MVP acceptance. Reworded 2026-05-27 from "network loss" to "BLE disconnect" after firmware ↔ mobile transport was settled as Bluetooth LE.

- FR-013: Product can resume an in-progress physical-mode game after an app restart on the same device. On resume, the app loads the last persisted move, renders the expected position, and asks the player to confirm that the physical board matches before re-enabling move acceptance. If it does not match, the player uses live diagnostics (FR-011) to restore the position manually. Cross-device handoff of an active physical-mode game is not part of MVP. Priority: must-have
  > Socrates: Counter-argument considered: "Requiring a fresh game on any restart would be simpler." Resolution: added as must-have; restarts are likely in MVP usage windows, and discarding accepted moves would violate the canonical-record guardrail.

### Game Record, History, Replay, And Analysis

- FR-014: Product can automatically save every accepted move into durable game history as play progresses, using complete PGN as the source of truth while deriving, generating, or caching FEN for replay or analysis. Priority: must-have
  > Socrates: Counter-argument considered: "Saving only at game completion and persisting FEN per move could both be simpler in different ways." Resolution: revised; auto-save protects accepted moves, PGN remains the durable source, and FEN is derived or cached as needed.

- FR-015: Player can open a chronological list of their own saved games. Priority: must-have
  > Socrates: Counter-argument considered: "Replay could open only the most recent game or a direct game link." Resolution: added as must-have; returning to saved games is part of the original pain.

- FR-016: Player can replay a chosen saved game with start, back, forward, and end controls. Priority: must-have
  > Socrates: Counter-argument considered: "Move-by-move replay could be generic or forward-only." Resolution: strengthened as must-have; reviewing a precise position requires bidirectional navigation.

- FR-017: Player can request post-game analysis and view position evaluations. Priority: must-have
  > Socrates: Counter-argument considered: "Replay alone may be enough, and external tools could analyze exported PGN." Resolution: kept as must-have; position evaluations are the required analysis output for MVP.

- FR-018: Player can manually mark the end of a game and record its result (win/loss/draw). Priority: must-have
  > Socrates: Counter-argument considered: "A completed PGN move record can exist without explicit result handling." Resolution: promoted to must-have; with no automatic detection of draw-by-rule (threefold repetition, 50-move, insufficient material) in MVP, manual end-of-game is the only way to close such games and produce a complete PGN result tag.

### Platform Surfaces

- FR-019: Player can use the core play, save, history, replay, and post-game analysis flow on iOS and Android. Priority: must-have
  > Socrates: Counter-argument considered: "A single mobile platform could reduce MVP scope." Resolution: kept as must-have; the chosen product surface is mobile across iOS and Android.

- FR-020: Player can use a web target for the digital subset of the MVP — digital pass-and-play game creation (FR-003, FR-004), game history list (FR-015), replay (FR-016), post-game analysis (FR-017), and end-of-game marking (FR-018) — without the physical-board flow (FR-008–FR-013) and without BLE diagnostics (FR-011). Priority: nice-to-have
  > Socrates: Counter-argument considered: "Web should be equal to mobile if the project target can produce it." Resolution: kept as nice-to-have; partial parity for digital flows is acceptable. Reworded 2026-05-27 to narrow scope: physical-board mode and BLE are mobile-only because Web Bluetooth has inconsistent cross-browser support (no Safari on iOS, mobile browsers limited) and the small-circle MVP use case does not justify the integration cost.

## Non-Functional Requirements

- An accepted move appears on the player's device within 500 ms of the interaction that accepted it, whether the move came from the digital board or a physical-board confirmation.
- A game is private by default: only the signed-in owner can access its game, history, replay, and analysis views in the MVP.
- The core mobile product is available and usable on the latest two major versions of iOS and Android at MVP release time.
- The physical board uses Bluetooth Low Energy (BLE) as its sole wireless transport in MVP; it does not initialize Wi-Fi peripherals, store Wi-Fi credentials, or depend on a local router for physical-mode play.
- Saved games are persisted locally on the player's device and backed up to cloud storage scoped to the signed-in account; the same game history is available across the player's signed-in devices.

## Business Logic

The product records each game as an analysis-ready canonical chess record made only of legal moves, then lets the player replay and transform that record into evaluated positions after the game.

The rule consumes played chess moves from two user-facing channels: interactive digital-board moves and physical-board move sequences confirmed by side-specific buttons. It accepts only move attempts that can be resolved into the legal continuation of the current game.

Every submitted move attempt ends in one of two user-visible outcomes: a specific legal move is accepted and persisted into the game record, or the attempt is rejected and the player receives a correction path. The user encounters this rule during play, while recovering from physical-board detection errors, when reopening the saved game, and when reviewing evaluated positions.

## Access Control

Users sign in through an external OAuth identity provider selected during downstream stack selection. If the person signing in does not already have an account, the product should automatically create one where possible.

The MVP uses a flat user model: every signed-in user has the same capabilities. There are no separate admin, owner, guest, coach, or viewer roles in the MVP.

Anonymous access is not part of the MVP. Unauthenticated users cannot access game, history, replay, or analysis views.

## Implementation Decisions

The following stack-shape decisions were settled on 2026-05-27, after the original PRD was drafted, in alignment with `docs/reference/contract-surfaces.md` and `context/foundation/prd-firmware.md`. They are recorded here as PRD-level decisions because they affect user-facing wording (FR-012, NFRs); concrete framework / library selection still lives in `context/foundation/tech-stack.md` (produced by `/10x-tech-stack-selector`).

- **System decomposition**: the product is implemented as three sub-projects — **mobile** (this PRD), **firmware** (`prd-firmware.md`), and **backend** (no separate PRD; fully specified by `contract-surfaces.md` §2-4 because the backend is Supabase configuration plus one Edge Function, not custom server code).
- **Firmware ↔ mobile transport**: Bluetooth Low Energy (BLE). The board does not use Wi-Fi in MVP — no Wi-Fi onboarding flow, no Wi-Fi credentials to manage, and no router dependency for physical-mode play. This resolves the prior NFR about Wi-Fi credentials, which is now superseded by a BLE-only transport NFR.
- **Backend**: Supabase (managed Postgres + Auth + Edge Functions). Game persistence, OAuth, RLS-based per-user scoping, and the Lichess Cloud Eval proxy are all hosted on Supabase. No bespoke server is written for MVP. Backend behavior is specified in `contract-surfaces.md` §2-4.
- **Identity provider**: Google OAuth via Supabase Auth, with automatic account creation on first sign-in (per FR-001 and Access Control). Apple Sign In and other providers are deferred and out of MVP scope. The MVP does not target App Store publication, so App Store Review Guideline 4.8 is not in force.
- **Post-game analysis (FR-017)**: Lichess Cloud Eval API, called server-side from a Supabase Edge Function with a shared server-side cache in `position_evals` keyed by FEN. Centipawn evaluation plus best move per position, on-demand only. Amended 2026-06-10: Lichess Cloud Eval returns only positions already known to its database (mostly opening theory and popular positions), so arbitrary amateur positions would routinely get no eval; on a Lichess miss the Edge Function falls back to Chess-API.com (free Stockfish 18 NNUE REST API, evaluates any FEN, no API key), caching results in the same `position_evals` table. A locally bundled Stockfish (per-platform `expect`/`actual`: WASM engine on web, natively compiled Stockfish C++ on iOS/Android) remains a post-MVP nice-to-have — see Open Question 3. See `contract-surfaces.md` §3.3.
- **Web target scope**: digital flows only (pass-and-play, history, replay, post-game analysis). Physical-board mode (FR-008–FR-013), BLE transport, and reed-switch diagnostics (FR-011) are intentionally excluded from the web target because Web Bluetooth has inconsistent cross-browser support (Chromium-only on desktop, no Safari on iOS, mobile browsers limited) and the small-circle MVP use case does not justify the integration cost. Decided 2026-05-27 alongside tech-stack selection (`context/foundation/tech-stack.md`).
- **RLS hardening (2026-06-11)**: `games` RLS policies are scoped `to authenticated` and the `set_updated_at()` trigger function pins an empty `search_path` (`contract-surfaces.md` §2.4/§2.6 amended per S-01 phase-1 implementation review). No user-facing behavior change — the per-user privacy guarantee (games private by default) is unchanged; this closes a Supabase linter warning and an over-broad default policy role.
- **Eval response shape & cache keying (2026-06-12, S-03)**: eval responses and the `position_evals` cache gain a mate-in-N representation (`mate` column / response field, White-POV signed, NULL unless forced mate) — both eval providers report forced mates and the previous centipawn-only shape could not represent them. FEN cache keys are normalized by the Edge Function (halfmove/fullmove counters zeroed) so identical positions reached at different move numbers share one cache row. No user-facing scope change to FR-017. See `contract-surfaces.md` §2.3/§3.3/§5.4.
- **OAuth flow specifics (2026-06-10, S-01)**: the mobile deep-link scheme is locked as `com.smartchessboard://callback`, and OAuth always runs in an external browser / custom tab (never an embedded WebView). Session persistence uses the Supabase SDK default (multiplatform-settings: SharedPreferences / NSUserDefaults / localStorage); hardened OS-keystore storage (Keychain / Keystore) is deferred to post-MVP. No user-facing behavior change. See `contract-surfaces.md` §4.1/§4.2.
- **`games.user_id` column default (2026-06-12, S-04)**: `user_id` gains `default auth.uid()` so the first write path (game creation, FR-003) can honor §3.2's "mobile does not pass user_id explicitly on any write" — RLS ownership checks are unchanged. No user-facing behavior change. See `contract-surfaces.md` §2.2.
- **Eval function CORS allow-headers echo (2026-06-13, S-03)**: the `lichess-eval` preflight response echoes the browser's `Access-Control-Request-Headers` instead of a static list — supabase-kt attaches `x-region` to every function invocation, which the static list rejected, breaking analysis on the web target. No user-facing scope change to FR-017. See `contract-surfaces.md` §3.3.
- **Eval provenance survives cache hits (2026-06-13, S-03)**: the `lichess-eval` 200 response keeps `source` as the provider that produced the eval (`lichess` / `chess-api`) and adds `cached: bool`, instead of replacing `source` with `"cache"` on cache hits — the analysis panel shows the true engine source for cached positions. No schema change (`position_evals.source` already stores the provider). See `contract-surfaces.md` §3.3.
- **Snapshot bit-packing clarification (2026-06-16, F-02)**: `contract-surfaces.md` §1.3 now pins the `BOARD_SNAPSHOT` byte layout (byte `i` bit `j`, LSB-first = square `i*8 + j`), which the previous "bit N = square N" wording left to the byte split underspecified. No user-facing impact — snapshot encoding is an internal firmware↔mobile wire detail with no FR behavior depending on it; recorded here only to satisfy the contract's §1 change-control mirror.
- **`createGame` carries an explicit mode (2026-06-19, S-06)**: game creation now passes `mode` (`'digital'`/`'physical'`) as an explicit client argument instead of hardcoding `'digital'`, so physical-mode games (FR-008) are created through the same path as digital play. No DB migration — `games.mode` already accepts `'physical'` (CHECK constraint since the schema's creation); RLS and the `user_id` default are unchanged. See `contract-surfaces.md` §3.2.

These decisions resolve the earlier Open Questions 1, 3, and 4 in the way that those questions' resolutions already anticipated — see the Open Questions section below for the full reasoning trail.

## Non-Goals

- No live engine bar during gameplay. Analysis happens after the game; live evaluation may become a configurable post-MVP option.
- No online matchmaking, remote multiplayer, or online play through external chess platforms. MVP play is local: digital pass-and-play or a connected physical board.
- No AI opponent or local chess engine gameplay. The MVP supports human-vs-human games; engine work is analysis-related, not opponent play.
- No automatic critical-moment detection. Critical moments are a post-MVP nice-to-have beyond basic position evaluations.
- No tournaments, ELO ratings, rankings, or club statistics. The MVP is for personal game history and analysis, not organized competition.
- No training, puzzles, lessons, or educational module. The MVP is a tool for playing and analyzing one's own games.
- No time control. Any chess-clock-shaped hardware in the MVP means confirmation buttons only, not per-player timekeeping or wins on time.
- No multi-client realtime physical play. The MVP assumes one active device next to the physical board, not separate synchronized phones for both players.
- No guided physical-board recovery UX in MVP. When a sequence is rejected, the player uses the raw live reed-switch diagnostics (FR-011) to manually restore the previous legal position. A step-by-step guided restoration flow is post-MVP; MVP only commits to the happy path plus a visible error message and raw diagnostics.
- No physical-board mode on the web target. The BLE connection to the smart chessboard is mobile-only (iOS + Android). The web target supports digital play, history, replay, and post-game analysis exclusively.

## Open Questions

1. **What no-hardware validation strategy should downstream planning use?** — Owner: downstream stack/planning step. Latest acceptable resolution: before implementation planning. Block: no; this does not block PRD generation.
   > Resolved 2026-05-26: MVP combines (A) unit tests of pure sequence-interpreter and rules-engine logic with (B) a programmatic reed-switch emulator — an interface that delivers the same messages as the microcontroller but is driven by test scripts or a dev tool, with no GUI. Together they let the full physical-mode flow (transport → interpreter → rules → UI) run end-to-end without the board, including in CI and from AI-assisted contributors. (C) Recorded hardware fixtures (replay of real microcontroller logs) is nice-to-have, useful for regression coverage of hardware artefacts (debouncing, spurious triggers), but does not block MVP acceptance. A visual click-driven simulator is out of scope for MVP.

2. **What is the MVP scope of end-of-game detection?** — Must the system auto-detect checkmate and stalemate from validation, or also threefold repetition, 50-move rule, and insufficient material? Owner: downstream stack/planning step. Latest acceptable resolution: before validation engine implementation. Block: no; FR-005 covers move legality, but terminal-state detection scope needs explicit confirmation.
   > Resolved 2026-05-26: MVP auto-detects only checkmate and stalemate (free from the legality engine — see FR-007). Threefold repetition, 50-move, and insufficient material are not auto-detected; players close such games manually via FR-018 (promoted to must-have).

3. **What concrete output does post-game position evaluation produce, and which engine produces it?** — Numeric centipawn score only, or best-move suggestion / principal variation? Local engine on device, embedded engine in backend, or external API? On-demand per position or precomputed for the whole game? Owner: downstream stack/planning step. Latest acceptable resolution: before analysis subsystem implementation. Block: no; FR-017 commits to "position evaluations" but leaves shape and cost open.
   > Resolved 2026-05-26: MVP uses the Lichess Cloud Eval API, invoked from the backend (centralised API key and rate-limit handling). Output is centipawn evaluation plus best move per position, on-demand only (no precompute, no live bar). Bundled Stockfish (local on device or in backend) is a post-MVP upgrade if offline analysis or stricter privacy becomes a requirement.
   > Amended 2026-06-10: the original resolution implicitly assumed Lichess Cloud Eval covers arbitrary positions — it does not (it serves only positions already in its database, so most amateur middlegame positions miss). Surfaced during the roadmap north-star review. Resolution extended with a fallback: on a Lichess miss the Edge Function calls Chess-API.com (Stockfish 18 NNUE, computes any FEN, free, no key), caching into `position_evals` with `source='chess-api'`; `unknown` becomes the residual case when both providers fail. Bundled Stockfish stays post-MVP, now sketched as per-platform `expect`/`actual` (ready-made WASM engine build on web, e.g. stockfish.js / lila-stockfish-web; natively compiled Stockfish C++ on iOS and Android). See `contract-surfaces.md` §3.3.

4. **How is an in-progress physical-mode game reconciled after an app restart or device change?** — Does the app resume from the last persisted move and expect the player to confirm the current board matches, or does it require restarting the game? Owner: downstream stack/planning step. Latest acceptable resolution: before physical-mode session handling implementation. Block: no; FR-012 covers BLE disconnect but not full app/process restart mid-game.
   > Resolved 2026-05-26: Same-device resume after app restart is in MVP via a position-confirmation screen plus live diagnostics — see FR-013. Cross-device handoff of an active physical-mode game is post-MVP; cloud-backed history covers cross-device access to completed games only.
