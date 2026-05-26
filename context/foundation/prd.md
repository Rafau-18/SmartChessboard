---
project: "Smart Chessboard"
version: 1
status: draft
created: 2026-05-26
context_type: greenfield
product_type: mobile
target_scale:
  users: small
  qps: low
  data_volume: small
timeline_budget:
  mvp_weeks: 12
  hard_deadline: null
  after_hours_only: false
---

# Smart Chessboard — PRD

## Vision & Problem Statement

An amateur chess player plays chess on a physical wooden board with friends at home. Games played this way vanish the moment they end — they exist in no record, cannot be analyzed afterward, and there is no way to return to a specific position. Manually notating moves on paper kills the tempo and enjoyment of the game, so in practice nobody does it.

Commercial smart chessboard products solve the recording problem, but they are expensive and closed — they do not fit the author's use case or his circle of friends. The author already owns a working prototype of a physical board fitted with a magnetic-sensor matrix and a microcontroller, built during his studies. The project is a focused software layer over existing hardware, tailored to a specific group of users rather than a mass market. The insight is that with the hardware already in hand, a small mobile-first software effort is sufficient to deliver a premium digital-physical chess experience without buying into closed commercial systems.

## User & Persona

**Primary persona — amateur chess player.** The project author and his circle of friends. They play physical chess for enjoyment, not competitively, regularly at home. Each person has their own account, profile, and game history — they want to see their own statistics and return to their own games, not a shared pool.

## Success Criteria

### Primary
- A user can play a complete chess game in one of two modes: (a) digital pass-and-play on a mobile device screen; (b) physical play on the connected hardware board. In both modes the game is automatically saved in standard chess notation (PGN + per-move FEN). The user can later return to the list of their own games and walk a chosen game move by move, with every position replayed.

### Secondary
- After a game ends, a user can request a position evaluation for selected positions of the game from a free external chess position evaluation service.

### Guardrails
- **Move legality.** The product never persists an illegal move. Every move is validated against the full rules of chess (pinned-piece movement, escaping check, castling, en passant, promotion) before it is saved, in both digital and physical modes. Automatic end-of-game detection (mate, stalemate, threefold repetition, 50-move rule) is intentionally NOT in MVP — end-of-game and result are user-marked.
- **Game durability.** A saved game does not disappear from the user's account. An application crash mid-game does not lose moves accepted up to the point of the crash. Loss of connection to the physical board is covered minimally in MVP (last accepted move retained); seamless mid-game continuity after reconnect is broken out as nice-to-have.
- **Move responsiveness.** An accepted move (touch in digital mode, confirmation-button press in physical mode) appears on the device screen within 500 ms of the interaction.
- **No silent corruption of physical detection.** A misinterpreted physical move is never silently persisted. Every confirmed sensor state ends in one of two user-observable outcomes: the move is recognized as a specific legal chess move and saved, or the product visibly reports a detection problem and pauses the game until the user manually corrects the position. The hobby-grade hardware (occasional false hits, occasional non-detections) is acknowledged: the experience is partially autonomous with human-assisted correction, never silently corrupting state.

## User Stories

### US-01: Playing a game on the physical board

- **Given** a logged-in user with the mobile app connected to a physical board on the same Wi-Fi network, and an opponent physically present at the board
- **When** the user creates a new game in "physical" mode, both players make moves on the board and press the confirmation button on their side after each of their moves
- **Then** after each button press the product receives the sensor state from the board, recognizes the move, validates its legality, persists it in standard notation (PGN + FEN), and updates the on-screen position

#### Acceptance Criteria
- A press of the confirmation button on the side of the player who just made a legal move results in the move being saved under that color and a visible on-screen position update within < 500 ms.
- The product tracks the full sequence of piece lifts and placements since the last confirmation and correctly recognizes captures and castling from the sequence rather than from a single snapshot.
- If the recorded sequence does not correspond to any legal move from the current position, the product displays an error, pauses the game, and asks for restoration of the previous position; the move is not saved.
- On pawn promotion the product displays a piece-selection pop-up; the move is saved only after the choice is made.
- A diagnostic view of the sensor-matrix state (which squares detect a piece) is available to the user during the game.
- The end of the game and the result (1-0 / 0-1 / ½-½ / unfinished) are set manually by the user; the product does not declare end-of-game on its own in MVP.
- Once marked, the game appears in the history list and can be reviewed move by move.

## Functional Requirements

### Accounts and login
- FR-001: A new user can create an account by signing in through an external identity provider. Registration is open — no invitation, no code, no email verification. Priority: must-have
  > Socratic: Counter-argument considered in Round 1: "manual account creation / invitation code". Resolution: initially kept ("invitations as must-have"), then revised post-Phase-7: open registration via an external identity provider replaces the closed beta. Reasons for revision: (1) low project visibility in MVP is a sufficient constraint; (2) an external provider removes the need for registration UI and password management; (3) the author's educational goal includes practical hands-on integration with an external identity provider.

- FR-002: A user can log into their account through the same external identity provider used to create the account. Priority: must-have
  > Socratic: Counter-argument considered: "PIN + select-from-list / magic-link". Resolution: kept; full external-provider login stays.

- FR-003: A user can log out. Priority: must-have
  > Socratic: Counter-argument considered: "logout is a dead feature on a single-person phone". Resolution: kept; a basic auth feature whose absence would be conspicuous even if rarely used.

### Game creation
- FR-004: A logged-in user can create a new game, indicating the play mode (digital or physical) and who plays White and Black. Priority: must-have
  > Socratic: Counter-argument considered: "auto-detect mode from paired hardware / auto-assign colors". Resolution: kept; the user explicitly controls mode and colors.

### Digital play (pass-and-play)
- FR-005: A user makes moves on the on-screen board interactively (by dragging or by tap-tap on squares). Priority: must-have
  > Socratic: Counter-argument considered: "drag-and-drop only without tap-tap / algebraic notation entry". Resolution: kept; standard chessboard interaction. Specific drag-vs-tap choice is an implementation detail.

- FR-006: The product validates the legality of every move before it is executed, against the full rules of chess (pinned-piece movement, escaping check, castling, en passant, promotion). Priority: must-have
  > Socratic: Counter-argument considered: "players validate themselves / only basic validation without pinning". Resolution: kept; ties into the move-legality guardrail. Non-negotiable.

- FR-007: On pawn promotion the product displays a piece-selection pop-up (queen / rook / knight / bishop). Priority: must-have
  > Socratic: Counter-argument considered: "auto-promote to queen / gesture-based choice". Resolution: kept; standard chess UI convention.

### Physical play (hardware)
- FR-008: A user configures the physical board's Wi-Fi via a captive portal flow on the board itself — without ever entering credentials into firmware or source code. Priority: must-have
  > Socratic: Counter-argument considered: "hardcoded credentials / BLE handshake". Resolution: kept; the absolute constraint is credentials never in code; BLE is explicitly out of MVP.

- FR-009: The product establishes a connection with the physical board on the local network. In MVP, one default board per user; pairing with multiple boards is deferred to post-MVP. Priority: must-have
  > Socratic: Counter-argument considered and accepted: "only one board per user — no list-picker needed". FR was rewritten from "pairing" to a simple single-default-board connection; multi-board pairing moves to post-MVP.

- FR-010: The board continuously monitors the state of the sensor matrix and reports state changes to the product. The product tracks the sequence of piece lifts and placements since the last confirmation. Once the confirmation button is pressed, the product interprets the full recorded sequence as one specific legal chess move — including captures (e.g., "piece lifted from A → piece lifted from B → piece placed on B" recognized as capture AxB) and castling (two pieces lifted, two placed on different squares). Priority: must-have
  > Socratic: Counter-argument considered and accepted: "continuous detection without a button is chaos / button per player (clock)". FR was rewritten: continuous monitoring of sensor state + the full lift/placement sequence as input, instead of a delta snapshot (a snapshot is insufficient for captures — from the delta's perspective a capture looks like a disappearance). The per-player button was broken out as a separate FR-011.

- FR-011: The board exposes two move-confirmation buttons — one per player, physically arranged in the form of a chess clock. The product distinguishes which button was pressed and attributes the confirmed move to the player on that side of the clock. Priority: must-have
  > Socratic: New FR derived from the counter-argument to FR-010. Without two distinguishable buttons it is unclear whose move was confirmed (one player could press for the other). Must-have.

- FR-012: A user sees an in-product diagnostic view of the sensor-matrix state (which squares currently detect a piece / magnet). Priority: must-have
  > Socratic: Counter-argument considered and rejected: "diagnostics only in firmware logs / only on errors". Reason: hobby-grade hardware with imperfect sensors requires constant debugging support; in-product diagnostics is an absolute must-have.

- FR-013: When the recorded sensor sequence after a button press does NOT correspond to any legal move from the current position, the product signals an error, pauses the game, and asks the user to manually restore the previous piece positions (with help from the diagnostic view) and to press the confirmation button again once the board matches the expected state. The product does NOT save such a state as a move. Priority: must-have
  > Socratic: Counter-argument considered: "ignore bad states / force-update". Resolution: kept; ties into the legality and no-silent-corruption guardrails. Post-Phase-7 refinement: explicitly clarified that restoration is manual (assisted by the diagnostic view); full automatic position-recovery detection broken out into FR-022 as nice-to-have.

### End of game and persistence
- FR-014: A user can at any time manually mark the end of the game and its result (1-0 / 0-1 / ½-½ / unfinished). Priority: must-have
  > Socratic: Counter-argument considered: "result without strict enumeration / only binary 'game over'". Resolution: kept; standard chess notation requires one of these four outcomes.

- FR-015: The product automatically persists every accepted move to durable storage (PGN + per-move FEN), regardless of mode and regardless of whether the game is later finished. Priority: must-have
  > Socratic: Counter-argument considered: "persist only after the game ends / PGN only without per-move FEN". Resolution: kept; ties into the durability guardrail, and per-move FEN simplifies replay (FR-020).

- FR-016: The product automatically detects end-of-game (mate / stalemate / threefold repetition / 50-move rule) and suggests the corresponding result. Priority: nice-to-have
  > Socratic: Counter-argument considered: "promote to must-have / partial detection (mate + stalemate only)". Resolution: kept as nice-to-have — manual end-marking is sufficient for MVP.

- FR-017: A player can record resignation or a draw by agreement as a way to mark the end of the game. Priority: nice-to-have
  > Socratic: Counter-argument considered: "redundant with FR-014 / agreement UI too complex for pass-and-play". Resolution: kept as nice-to-have — enters after the Primary path.

- FR-018: A user can start a new game from a position selected from any saved game (whether finished or unfinished) — "new game from position X". Priority: nice-to-have
  > Socratic: Counter-argument considered: "FEN to an external tool / only for finished games". Resolution: kept as nice-to-have — enters after the Primary path.

### History and replay
- FR-019: A logged-in user can open the list of their own games in chronological order. Priority: must-have
  > Socratic: Counter-argument considered: "list of all games across the circle (social) / only the last 20". Resolution: kept; the list is the user's own games only.

- FR-020: A user can open a chosen game from history and walk through it move by move (forward / back / start / end), with every position replayed on screen. Priority: must-have
  > Socratic: Counter-argument considered: "forward (playback) only / side-by-side analysis board". Resolution: kept; full forward / back / start / end is non-negotiable for the original pain ("return to a position").

### Analysis
- FR-021: After a game ends, a user can request a position evaluation for selected positions of the game from a free external chess position evaluation service. Priority: nice-to-have
  > Socratic: Counter-argument considered: "end-position evaluation only / real-time bar / own engine". Resolution: kept as nice-to-have. The specific evaluation source and scope (per-position vs per-game) are deferred to implementation. Computing evaluations within the product itself (rather than from an external service) is a post-MVP roadmap item.

### Hardware extensions
- FR-022: After a detection error (FR-013), the product monitors the sensor state continuously and automatically detects the moment the physical board matches the expected previous legal position; on a match, the game resumes without requiring another confirmation-button press. Priority: nice-to-have
  > Socratic: Introduced post-Phase-7 to acknowledge realistic hobby-hardware limitations. Broken out from must-have FR-013, which retains manual retry-after-press as the baseline behavior.

- FR-023: On loss of connection between the product and the physical board mid-game, the product pauses move acceptance, displays a clear network-loss message, and attempts automatic reconnect in the background. After reconnect, the game resumes from the last accepted move without state loss and without requiring manual position reconstruction. Priority: nice-to-have
  > Socratic: Introduced post-Phase-8 after cross-check with shape-alternative.md. Counter-argument considered: "MVP can live without it — FR-015 (auto-save of every move) guarantees the last accepted state is on disk, and after reconnect the user re-opens the game from history". Resolution: kept as nice-to-have; happy-path continuity meaningfully improves UX for mid-game disconnects but requires additional buffering and a replay protocol — minimal coverage is acceptable in MVP.

## Non-Functional Requirements

- A user's game is accessible only to that user — it never appears in views shown to other logged-in users or to anonymous visitors. No exception in MVP.
- The product (both the user-facing client and any supporting software services it depends on) can be fully built, run, and automatically validated without a physical board attached — regardless of whether the developer has access to the hardware.
- The product is available and fully functional on the two current major versions of each of the two mainstream mobile platforms (Android and iOS) at the time of MVP release.
- Wi-Fi credentials (SSID, password) entered by the end user during board configuration are never persisted in any build-time artifact (firmware, source repository, configuration files) and exist only in the memory of the specific board the user paired and configured.
- For every accepted move in either play mode, the on-screen position update is visible within 500 ms of the interaction (touch in digital mode, confirmation-button press in physical mode).

## Business Logic

The product guarantees that every recorded chess game consists exclusively of legal moves and can be replayed move by move in an identical state, regardless of the medium (physical or digital) on which it was played.

**What the rule consumes.** The inputs are the raw acts of play produced by a user through one of two channels: (a) touch interactions on the device screen (drag-and-drop or tap-tap on squares), together with a promotion choice in the promotion pop-up; or (b) a physical sequence of piece lifts and placements on the wooden board with magnets, recorded continuously and concluded by a press of the per-player confirmation button. Both channels carry the same semantics — "the player wants to make this move now".

**What the rule produces.** Every submitted move attempt ends in one of two outcomes: the move is accepted as a specific legal chess move from the current position and persisted in the game's history in standard chess notation (PGN + per-position FEN); or the move is unambiguously rejected with a reason, and the game is paused until the prior position is restored. The history state is deterministically reproducible — from the saved notation, every position that ever occurred in the game can be reconstructed.

**How the user encounters the rule.** During play — immediate feedback: the move appears on the screen, or an error message asks for correction. After play — the game appears in the user's own game list, opens for step-by-step replay (forward / back / start / end), and every intermediate state is exactly what happened at the table or on the screen. The rule does not ask the user to judge chess legality; it knows what is legal and what is not.

## Access Control

Each player owns an account and signs into the product so that games and statistics are attributed to them.

- **Role model:** flat — every logged-in user has the same capabilities. No admin / user / guest distinction in MVP.
- **Sign-up:** open — anyone can create an account. No invitation, no invite code, no email-verification gate. Assumption: low MVP visibility plus a short sign-up path is a sufficient balance for the closed-circle persona.
- **Authentication:** sign-up and sign-in both go through a single external identity provider; the product itself manages no passwords, no email-verification flow, and no password-reset flow.
- **Anonymous access:** none. An unauthenticated visitor has no access to game views, history, or analysis. The pass-and-play digital mode is intentionally gated behind sign-in.
- **Data isolation:** each user sees only their own games and history. There are no cross-user views in MVP.

## Non-Goals

- **Online play / matchmaking / remote multiplayer.** MVP does not include opponent matching, remote play, or integration with online chess platforms. Play is local — one device (pass-and-play) or one connected board.
- **Play against an AI / a chess engine as the opponent.** MVP supports only human vs. human. A chess engine acting as a "computer opponent" is post-MVP. The optional secondary position-evaluation feature is post-game analysis, not gameplay.
- **Tournaments, ELO ratings, club statistics.** MVP includes no competitive or club functionality: no tournament play, no brackets, no ranking, no aggregate club statistics. Each user sees only their own games.
- **Training, puzzles, lessons.** MVP is a tool for playing and analyzing one's own games, not a teaching tool. No mate puzzles, opening drills, or guided lessons.
- **Bluetooth (BLE) hardware pairing.** MVP uses Wi-Fi only via the captive-portal flow. BLE-based shorter onboarding is post-MVP.
- **Web client.** Mobile (Android + iOS) is the only client surface in MVP. A browser-based client is post-MVP and would require its own layout and deployment design.
- **Pairing with multiple physical boards.** One default board per user in MVP. A full multi-board pairing mechanism (e.g., a chess club with several boards) is post-MVP.
- **Time control.** MVP does not support per-game time limits (10+5, blitz, classical 90+30, etc.) or wins on time. "Clock" in the hardware context refers exclusively to the physical arrangement of the two confirmation buttons, NOT to a timekeeping function. Time control is deferred to the post-MVP roadmap.
- **Multi-client real-time for physical play.** In MVP the product runs on a single device next to the board — both players share the same screen within one signed-in session. The variant "each player on their own phone, screen flipped for perspective, real-time sync between clients" is explicitly out of MVP; it would require multi-client sync infrastructure and multi-party session management.
- **Hardware redesign.** The product is a software layer over the author's existing prototype. Re-architecting the board itself — alternative sensor technologies (camera, RFID, capacitive), a different chassis, alternative microcontroller families — is out of MVP scope.

## Open Questions

1. **Is `product_type: mobile` sufficient, or should the schema be extended to an explicit "hybrid" category to capture the mobile + supporting software services + physical-hardware nature of this product?** — Owner: schema maintainer / tech-stack-selector. By: prior to running `/10x-tech-stack-selector`. Not blocking: `mobile` + a free-text description is the working interpretation; this question is about whether to formalize the hybrid shape in the schema.
