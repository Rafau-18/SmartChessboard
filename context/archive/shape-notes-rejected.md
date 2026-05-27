---
project: "Smart Chessboard"
context_type: greenfield
created: 2026-05-25
updated: 2026-05-26
checkpoint:
  current_phase: 8
  phases_completed: [1, 2, 3, 4, 5, 6, 7]
  gray_areas_resolved:
    - topic: "pain category"
      decision: "data trapped in the physical world + no analysis + friction of manual notation"
    - topic: "insight motivating the project"
      decision: "commercial smart chessboards are too expensive and closed for this use case; the author already has a hardware prototype"
    - topic: "persona scope"
      decision: "multi-user — author + a circle of friends, each with their own profile and history"
    - topic: "authentication model"
      decision: "user accounts (mechanism deferred to tech-stack)"
    - topic: "roles"
      decision: "flat — all logged-in users equal"
    - topic: "sign-up"
      decision: "open registration via external identity provider (OAuth) — initially 'closed beta with invitations' in Phase 2, revised post-Phase-7 to open OAuth-based registration; provider deferred to tech-stack"
  frs_drafted: 23
  # FR rev history: +1 FR-011 (chess clock, +Round 3); -1 FR-018 (pause/resume redundant, -Round 5); +1 FR-022 (auto-detect position recovery, post-Phase-7); +1 FR-023 (network-loss recovery, post-Phase-8); final count: 23
  quality_check_status: accepted
---

# Shape notes — Smart Chessboard

Notes from the /10x-shape session. The content is organized under PRD sections, but this is NOT yet a PRD.

## Vision & Problem Statement

An amateur chess player plays chess on a physical wooden board with friends at home. Games played this way vanish the moment they end — they exist in no record, cannot be analyzed afterward, and there is no way to return to a specific position. Attempts to manually notate moves on paper kill the tempo and enjoyment of the game, so in practice nobody does it.

Insight: commercial "smart chessboards" (Square Off, Chessnut, DGT) solve this problem, but they are expensive and closed — they don't fit the author's and his friends' use case. The author already built a prototype of a physical board with a reed-switch matrix and an ESP32 during his studies. The project is a software overlay on top of existing hardware, tailored to a specific group of users, not to the mass market.

## User & Persona

**Primary persona — amateur chess player.** The project author and his circle of friends. They play physical chess for enjoyment (not competitively), regularly at home. Each person has their own account, their own profile, and their own game history — they want to see their own statistics and return to their own games, not a "shared pool".

## Access Control

Each player has their own account and logs into the application to have their game history and statistics attributed to them.

- **Model:** flat — all logged-in users have the same permissions. No admin/user/guest roles in the MVP.
- **Sign-up:** open — anyone can create an account without an invitation. Assumption: low project visibility in the MVP ("nobody knows about the product") + a short registration path is sufficient balance for the closed-circle persona. No invitation mechanism = no separate UI and no separate code in the MVP.
- **Authentication mechanism:** login and registration via an **external identity provider (OAuth)** — a single provider, the same one for registration and login. No in-house password hashing, no in-house password-reset flow, no in-house email verification. The specific provider (Google / GitHub / Apple / Auth0 / Supabase Auth / other) is deferred to tech-stack selection.
- **Anonymous access:** none. An unauthenticated user has no access to any game views or analysis in the MVP.

## Success Criteria

### Primary
- The user can play a complete chess game in one of two modes: (a) **digital play** in pass-and-play mode on a mobile device screen (Android or iOS), (b) **physical play** on a physical board connected to the app, equipped with a reed-switch matrix (ESP32, move-confirmation button). In both modes the game is automatically saved in standard formats (PGN and FEN after each move). The user can later return to the list of their games and walk through a chosen game move by move, with every position replayed.

### Secondary
- After a game ends, the user can view a position evaluation (e.g., a Stockfish evaluation bar / chart) for selected positions in the game — the analysis is fetched from an external, free source of chess position evaluation.

### Guardrails
- **Move legality:** the application NEVER saves an illegal move. Every move is validated against the full rules of chess — pinned-piece movement, moves that fail to escape check, illegal king moves, invalid castling, illegal en passant, illegal promotion — before being saved. Validation applies in both modes (digital and physical). **Note:** automatic *end-of-game detection* (checkmate / stalemate / threefold repetition / 50-move rule) is out of MVP — in the MVP the user manually marks the end of the game and the result (see FR).
- **Game durability:** once saved, a game does not disappear from the user's account. An application crash during an in-progress game does not cause the loss of moves made up to the point of the crash. The guardrail covers application crashes; loss of connection to the physical board is handled minimally in the MVP (last saved move retained thanks to FR-015), while a full pause + auto-reconnect without state loss is broken out as nice-to-have FR-023.
- **Move responsiveness:** an accepted move (a screen touch in digital mode or a press of the confirmation button in physical mode) appears on the device screen as executed within at most 500 ms of the interaction.
- **No silent corruption of physical detection:** the application does not silently save a misinterpreted move. Every state confirmed by a button press ends with one of two user-observable reactions: (a) the move is correctly recognized as a specific legal chess move and saved, or (b) the application visibly reports a detection problem (no matching legal move, ambiguity, detected matrix-state inconsistency) and pauses the game until manual correction. **We accept the hobbyist character of the hardware:** the reed-switch matrix may produce false hits (a magnet "lights up" an unintended square) or non-detections (a piece stands on the board, but the square does not signal). For this reason the experience is not fully autonomous — the application offers support for manual correction (diagnostic live matrix view FR-012, a clear error message with a retry option FR-013, optional auto-detect of position recovery FR-022). Better "partially autonomous + human assistance" than "100% autonomous or nothing".

## Timeline acknowledgment

Acknowledged on 2026-05-25: the MVP requires more than 12 weeks of regular, intensive work. The user has explicitly accepted the cost: a long-term educational project, with sustained-effort cost accepted up front and a buffer for learning new technologies. `mvp_weeks: 12` (post-Phase-8 revision: no hard cap — 12 weeks as a realistic budget with a buffer for nice-to-haves, not a rigid deadline). MVP scope: the full digital scope (Android + iOS, multi-user, registration, replay UI) PLUS the hardware layer (ESP32 with a reed-switch matrix, confirmation button, WiFiManager captive portal, board-state diagnostics, hardware simulator for testing and CI/CD). All 4 nice-to-have FRs (FR-016, FR-017, FR-018, FR-022) plus the new FR-023 remain in scope as optional — they enter the MVP if the time budget permits; otherwise they drop to the post-MVP roadmap without renegotiating must-haves.

## Functional Requirements

### Accounts and login
- FR-001: A new user can create an account by signing in through an external identity provider (OAuth single sign-on). Registration is open — no invitation, no code, no email verification. Priority: must-have
  > Socrates: Counter-argument considered in Round 1: "manual account creation / invitation code". Resolution: initially kept ("invitations as must-have"), then REVISED post-Phase-7: open registration via OAuth replaces the closed beta. Reasons for revision: (1) low project visibility in the MVP ("nobody knows") is a sufficient constraint; (2) OAuth eliminates registration UI + password management; (3) the author's educational goal includes a practical OAuth implementation.

- FR-002: The user can log into their account through the same external identity provider (OAuth) they used to create the account. Priority: must-have
  > Socrates: Counter-argument considered: "PIN + select-from-list / magic-link". Resolution: kept; full OAuth login stays (confirmed post-Phase-7 together with FR-001); the specific provider is in tech-stack.

- FR-003: The user can log out. Priority: must-have
  > Socrates: Counter-argument considered: "logout is a dead feature on a single-person phone". Resolution: kept; a basic auth feature, its absence would be odd even if rarely used.

### Game creation
- FR-004: A logged-in user can create a new game, indicating the play mode (digital or physical) and who plays White and Black. Priority: must-have
  > Socrates: Counter-argument considered: "auto-detect the mode from paired hardware / White auto-assigned". Resolution: kept; the user explicitly controls the mode and the colors.

### Digital play (pass-and-play)
- FR-005: The user makes moves on the on-screen board interactively (by dragging or by tap-tap on squares). Priority: must-have
  > Socrates: Counter-argument considered: "drag-and-drop only without tap-tap / algebraic notation". Resolution: kept; standard chessboard UX; the specific choice of drag vs. tap is deferred to implementation.

- FR-006: The application validates the legality of every move before it is executed, in accordance with the full rules of chess (pinned-piece movement, escaping check, castling, en passant). Priority: must-have
  > Socrates: Counter-argument considered: "players validate themselves / only basic validation without pinning". Resolution: kept; ties into the "Move legality" guardrail from Phase 3, non-negotiable.

- FR-007: On pawn promotion the application displays a piece-selection pop-up (queen / rook / knight / bishop). Priority: must-have
  > Socrates: Counter-argument considered: "auto-promote to queen / gesture-based piece choice". Resolution: kept; standard from Lichess/Chess.com.

### Physical play (hardware)
- FR-008: The user configures the physical board's Wi-Fi via a captive portal (WiFiManager) — without entering credentials into the firmware. Priority: must-have
  > Socrates: Counter-argument considered: "hardcoded credentials / BLE handshake". Resolution: kept; the note explicitly requires it (credentials not in code); BLE is explicitly out of MVP.

- FR-009: The application establishes a connection with the physical board on the local network. In the MVP — one default board per user; pairing with multiple boards is deferred to post-MVP. Priority: must-have
  > Socrates: Counter-argument considered and ACCEPTED: "only one board per user — no need to pick from a list". FR rewritten: instead of "pairing" — a simple connection to one default board; multi-board pairing moves to post-MVP.

- FR-010: The hardware continuously monitors the state of the reed-switch matrix and sends changes (deltas) to the application. The application tracks the sequence of piece lifts and placements since the last move confirmation. After the confirmation button is pressed, the application interprets the entire recorded sequence as one specific legal chess move — including correct handling of captures (e.g., "piece lifted from square A → piece lifted from square B → piece placed on square B" recognized as capture AxB) and castling (two pieces lifted, two placed on different squares). Priority: must-have
  > Socrates: Counter-argument considered and ACCEPTED: "continuous detection without a button is chaos / button per player (clock)". FR rewritten: continuous monitoring of the matrix state + the sequence of lifts/placements as input, INSTEAD OF a delta snapshot (a snapshot is not enough for captures — from the delta's perspective a capture looks like a piece disappearance). The clock (per-player button) is broken out as a separate FR-011.

- FR-011: The hardware exposes two move-confirmation buttons — one for each player, physically organized in the shape of a chess clock. The application distinguishes which button was pressed and assigns the confirmed move to the color of the player on that side of the clock. Priority: must-have
  > Socrates: A new FR born from the counter-argument to FR-010. The note previously mentioned "a button / buttons (e.g., in the shape of a chess clock)" — Socrates pointed out that without two buttons it is unclear whose move was confirmed (one player could press for the other). Must-have.

- FR-012: The user sees a diagnostic in-app preview of the reed-switch matrix state (which squares detect a piece / magnet). Priority: must-have
  > Socrates: Counter-argument considered and REJECTED: "diagnostics only in firmware logs / only on errors". Reason: hobby project with imperfect hardware; the shortcomings of reed switches require constant debugging support — diagnostics is an absolute must-have.

- FR-013: The application signals an error when the recorded sequence of matrix states after a button press does NOT correspond to any legal move from the current position; the game is paused, the user is asked to manually restore the previous piece positions (assisted by the diagnostic view FR-012) and to press the confirmation button again once the board is consistent with the expected state. The application does NOT save such a state as a move. Priority: must-have
  > Socrates: Counter-argument considered: "ignore bad states / force-update". Resolution: kept; ties into the guardrails (legality + no silent corruption). Silence is not an unambiguous rejection. Post-Phase-7 refinement: the FR text was clarified to state that "position restoration" is manual (assisted by the diagnostic view); full auto-detect of matrix consistency without a button is broken out into the new FR-022 as nice-to-have.

### End of game and persistence
- FR-014: The user can at any time manually mark the end of the game and its result (1-0 / 0-1 / ½-½ / unfinished). Priority: must-have
  > Socrates: Counter-argument considered: "result without a strict enumeration / only binary 'game over'". Resolution: kept; standard chess notation requires one of four results.

- FR-015: The application automatically saves every accepted move to durable storage (PGN + FEN after each move, regardless of mode and regardless of whether the game is later finished). Priority: must-have
  > Socrates: Counter-argument considered: "save only after the game ends / PGN only without per-move FEN". Resolution: kept; ties into the durability guardrail (crash-safe); per-move FEN simplifies replay (FR-020).

- FR-016: The application automatically detects the end of the game (checkmate / stalemate / threefold repetition / 50-move rule) and suggests the result. Priority: nice-to-have
  > Socrates: Counter-argument considered: "promote to must-have / only checkmate and stalemate without threefold and 50". Resolution: kept as nice-to-have — author's MVP simplification; manual marking is sufficient; auto-detect is deferred.

- FR-017: A player can record resignation or a draw by agreement as one of the ways to mark the end of the game. Priority: nice-to-have
  > Socrates: Counter-argument considered: "redundant with FR-014 / agreement UI too complex for pass-and-play". Resolution: kept as nice-to-have — enters after the Primary path.

- FR-018: The user can start a new game from a position selected from any saved game (whether finished or unfinished) — "new game from position X". Priority: nice-to-have
  > Socrates: Counter-argument considered: "FEN to an external tool / only for finished games". Resolution: kept as nice-to-have — enters after the Primary path.

> Removed FR (game pause/resume): in the Socratic round the counter-argument was ACCEPTED. Pause functionality is implicitly provided by FR-015 (auto-save of every move) + FR-019 (open a game from the list) — a separate FR would be redundant.

### History and replay
- FR-019: A logged-in user can open the list of their games in chronological order. Priority: must-have
  > Socrates: Counter-argument considered: "list of all games across the circle (social) / only the last 20". Resolution: kept; a list of 'my' games (privacy); pagination vs. scroll is an implementation detail.

- FR-020: The user can open a chosen game from history and walk through it move by move (forward / back / start / end) with every position replayed on screen. Priority: must-have
  > Socrates: Counter-argument considered: "forward (playback) only / Lichess-style side-by-side analysis board". Resolution: kept; full forward/back/start/end is non-negotiable for 'returning to a position' (the original pain from Phase 1).

### Analysis (Secondary)
- FR-021: After a game ends, the user can request position evaluation for selected positions in the game from an external source (e.g., the free Lichess Cloud Eval). Priority: nice-to-have
  > Socrates: Counter-argument considered: "end-position evaluation only / real-time bar / in-house engine". Resolution: kept as nice-to-have — the specific source (Lichess vs. self-hosted Stockfish) and scope (per-position vs. per-game) is deferred to implementation. An in-house engine (on the server or on the mobile device) is clearly marked as a post-MVP roadmap item.

### Hardware extensions (nice-to-have)
- FR-022: After a detection error is reported (FR-013), the application monitors the matrix state continuously and automatically detects the moment when the physical board matches the expected previous legal position; once a match is detected the game resumes without requiring the confirmation button to be pressed again. Reduces friction in edge cases with hobbyist hardware. Priority: nice-to-have
  > Socrates: A new FR introduced post-Phase-7 as a response to the realistic limitations of hobbyist hardware (a reed-switch matrix with possible false hits / non-detections). Broken out from must-have FR-013, which retains the manual retry-after-press flow.

- FR-023: After loss of connection to the physical board during a game, the application pauses move acceptance, displays an unambiguous network-loss message, and attempts auto-reconnect in the background. Once the connection is restored the game resumes from the last confirmed move without state loss and without requiring manual position reconstruction. Priority: nice-to-have
  > Socrates: A new FR introduced post-Phase-8 as a result of the cross-check with shape-alternative.md. Counter-argument considered: "the MVP can live without it — FR-015 (auto-save of every move) guarantees that the last confirmed state is on disk, and after reconnect the user opens the game from the list". Resolution: kept as nice-to-have — the happy-path without losing game continuity meaningfully improves UX for mid-game disconnects, but it requires buffering on the ESP32 side and a replay protocol; in the MVP minimal coverage is acceptable (last save + manual resume from history).

## User Stories

### US-01: Playing a game on the physical board

- **Given** a logged-in user with the mobile app paired with a physical board connected to the same Wi-Fi network and an opponent physically present at the board
- **When** the user creates a new game in "physical" mode, both players make moves on the physical board and press the confirmation button after each of their own moves
- **Then** after every button press the application receives the matrix state from the hardware, recognizes the executed move, validates its legality, saves it to durable storage (PGN + FEN), and displays the current position on the device screen

#### Acceptance Criteria
- A press of the clock button on the side of the player who just made a legal move results in the move being saved under that color and a visible update of the on-screen position within < 500 ms (per the responsiveness guardrail and FR-011)
- The application tracks the sequence of piece lifts and placements since the last confirmation (continuous monitoring) and correctly recognizes captures and castling from the full sequence rather than from a delta snapshot alone (FR-010)
- If the recorded sequence after a button press does NOT correspond to any legal move from the current position, the application displays an error message, pauses the game, and asks for restoration of the previous position; the move is not saved (FR-013)
- On pawn promotion the application displays a piece-selection pop-up; the move is saved only after the choice is made (FR-007)
- A diagnostic view of the matrix state (which squares detect a piece) is available to the user during the game (FR-012)
- The end of the game and the result (1-0 / 0-1 / ½-½ / unfinished) are set manually by the user (FR-014); the application does not announce the end on its own in the MVP
- Once marked, the game is available in the history list (FR-019) and can be reviewed move by move (FR-020)

## Business Logic

The application guarantees that every recorded chess game consists exclusively of legal moves and can be replayed move by move in an identical state, regardless of the medium (physical or digital) on which it was played.

**What the rule consumes (input).** The input is the raw acts of play produced by the user through one of two channels: (a) touch interactions on the mobile device screen (drag-and-drop or tap-tap on squares), together with a piece choice in the promotion pop-up; (b) a physical sequence of piece lifts and placements on the wooden board with magnets, recorded continuously and concluded by a press of the confirmation button on the side of the player who made the move. Both channels carry the same semantics: "the player wants to make this move now".

**What the rule produces (output).** Every submitted move attempt ends with one of two outcomes: the move is accepted as a specific legal chess move from the current position and persisted in the game history in standard notation (PGN + FEN per position); or the move is unambiguously rejected with information about why it is illegal, and the game is paused until the previous position is restored. The history state is deterministically reproducible — from the saved notation one can reconstruct every position that ever occurred in the game.

**How the user encounters the rule.** During play — immediate feedback: the move appears on the screen, or an error message asks for correction. After play — the game appears in the user's own game list, opened for step-by-step replay (forward / back / start / end), where every intermediate state is exactly what happened at the table or on the screen. The rule does not ask the user to make chess judgments — it knows what is legal and what is not on its own.

## Non-Functional Requirements

- **Game privacy.** A game played by a user is accessible only to that user — it does not appear in the views of other logged-in or unauthenticated users. No exception exists in the MVP.
- **Testability without physical hardware.** The client application and its server side can be fully built, run, and automatically validated without connecting a physical ESP32 board — regardless of whether the developer has the hardware on hand.
- **Platform support.** The client application is available and fully functional on the two currently major versions of Android and the two currently major versions of iOS at the time of the MVP release.
- **Wi-Fi credential security.** Wi-Fi network credentials (SSID, password) are not stored in the microcontroller firmware or in the source code repository. They are entered by the end user and remain only in the memory of the specific paired device.

## Non-Goals

Functional and quality scope avoidances. Technologies are not listed here (they go to downstream tech-stack selection).

- **Online play over the internet / matchmaking / multiplayer.** The MVP does not include opponent matching, remote play, or integration with multiplayer platforms (Lichess, Chess.com). Play takes place locally — on a single device (pass-and-play) or on a connected physical board. Online multiplayer is explicitly deferred to Phase 2 (idea-shape.md note).
- **Play against AI / a local chess engine as the opponent.** The MVP supports only human-vs-human games. A local Stockfish as a "computer opponent" is a post-MVP feature (idea-shape.md explicitly). Position evaluation from an external source after a game (FR-021) is a different use case — analysis, not play.
- **Tournaments, ELO rating, club statistics.** The MVP contains no community/club functionality (tournament play, brackets, ranking, club W/L/D aggregates). Each user sees only their own games.
- **Training / puzzles / chess lessons.** The MVP contains no educational module (mate puzzles, opening training, lessons). The application is a tool for *playing and analyzing one's own games*, not for *learning chess*.
- **Bluetooth (BLE) hardware pairing.** The MVP uses Wi-Fi only (WiFiManager captive portal — FR-008). BLE pairing for a shorter hardware onboarding is deferred to Phase 2 (idea-shape.md note).
- **Web client (Kotlin/Wasm).** The original idea-shape.md note assumed a phone + tablet + web client. In Phase 3 web was cut from the MVP — a mobile client (Android + iOS) is the only access channel.
- **Pairing the application with multiple physical boards.** In the MVP, one default board per user (FR-009). The full mechanism for pairing with multiple boards (e.g., a chess club with several) is deferred to post-MVP.
- **Time control.** The MVP does not support setting a per-game time limit (10+5, 5+0, blitz, classical 90+30, etc.) or winning on time. "Clock" in the hardware description (FR-011) refers exclusively to the physical arrangement of the two confirmation buttons (chess-clock style), NOT to a time-keeping function. Time control is deferred to the post-MVP roadmap.
- **Multi-client real-time for physical play.** In the MVP the application runs on a single device next to the board — both players see the same screen within a single session of the user logged into the app (the game host). The variant "each player on their own phone, screen flipped for their perspective, real-time sync between clients" is explicitly out of MVP — it would require a full multi-client sync infrastructure and multi-party session management.

## Product framing (anticipated PRD frontmatter)

Informational note for `/10x-prd` — final frontmatter values will appear in `prd.md`.

- **`project`**: "Smart Chessboard"
- **`product_type`**: `mobile` (primary)
  - Free-text supplement: a hybrid product comprising a mobile application (Android + iOS) as the main user channel, an accompanying backend (auth, persistence, possibly an analysis proxy), and physical IoT hardware (ESP32 + reed-switch matrix). `mobile` chosen as primary because that is where the user spends time; the backend and firmware are supporting infrastructure for the product.
  - Open Question (for the PRD): should `product_type` be expanded to an explicit hybrid, or is `mobile` + an annotation in the description sufficient?
- **`target_scale`**:
  - `users`: `small` (3–7 people, closed beta)
  - `qps`: `low` (a few games per day, bursty traffic; position analysis optional)
  - `data_volume`: `small` (PGN + FEN per game; even 1000 games is a very small volume)
- **`timeline_budget`**:
  - `mvp_weeks`: `12` (post-Phase-8 revision: realistic budget with a buffer for nice-to-haves, without a hard cap)
  - `hard_deadline`: `null` (hobby/educational project, no external pressure)
  - `after_hours_only`: `false` (the author works on the project in a mixed mode, including full-time / sabbatical — this is NOT a classic "after hours only")

## Quality cross-check

Phase 7 completed. Quality status: **accepted** (all 5 required elements present, plus an additional 4 Guardrails from Phase 3).

| Element                    | State    | Note |
|----------------------------|----------|------|
| Access Control             | present  | Open registration via OAuth (post-Phase-7 revision); flat roles; OAuth provider mechanism in tech-stack. |
| Business Logic             | present  | One-sentence rule: "The application guarantees that every recorded chess game consists exclusively of legal moves…" |
| Project artifacts          | present  | shape-notes.md with a valid frontmatter checkpoint (current_phase, phases_completed, gray_areas, frs_drafted, quality_check_status). |
| Timeline-cost acknowledged | present  | `mvp_weeks: 12` (post-Phase-8 revision) with an explicit acceptance of the sustained-effort cost (Phase 3, "Timeline acknowledgment" block). |
| Non-Goals                  | present  | 9 entries: online multiplayer, AI/engine, tournaments, training, BLE pairing, web client, multi-board, time control, multi-client real-time. |
| Guardrails                 | present  | 4 (Phase 3): move legality, game durability, responsiveness < 500 ms, no silent corruption of physical detection. |

No accepted "warned" gaps — `/10x-prd` receives a complete set of inputs and does not need to project gap-warnings into `## Open Questions`.

**Post-Phase-7 refinements (after the first cross-check):**
- **Access Control (Phase 2):** closed beta with invitations → open registration via an OAuth external identity provider. Reasons: low project visibility, OAuth eliminates password management, the author's educational goal.
- **FR-001, FR-002:** rewritten for OAuth. Socrates blockquotes updated with "Resolution: revised post-Phase-7".
- **Guardrail "Fidelity of physical detection" (Phase 3):** softened from "100% correct recognition" to "no silent corruption + acceptance of manual human assistance". Adjusted to the realistic limitations of hobbyist hardware.
- **FR-013 (must-have):** clarified "manual position restoration + button re-press" as the happy-error-path.
- **FR-022 (NEW, nice-to-have):** auto-detect of position recovery without requiring a button press. Broken out post-Phase-7 from must-have FR-013.

**Post-Phase-8 refinements (after cross-check with shape-alternative.md):**
- **Timeline:** `mvp_weeks` 16 → 12. Reason: the alternative version realizes comparable scope in 8 weeks; our larger scope (22 → 23 FRs) justifies a buffer, but not as much as 4 weeks over that. 12 weeks as a realistic budget with a buffer for nice-to-haves, without a hard cap — nice-to-haves drop to the post-MVP roadmap if time runs out, without renegotiating must-haves.
- **Guardrail "Game durability":** scope clarified — covers application crashes but not loss of connection to the physical board. Full pause + auto-reconnect broken out as FR-023.
- **FR-023 (NEW, nice-to-have):** network-loss recovery — game pause + message + auto-reconnect + state preservation. In the MVP minimal coverage (FR-015 auto-save of every move); full mid-game continuity is deferred to nice-to-have.
- **Non-Goals (+2):** added "Time control" — explicitly closes the door on the idea that "clock" means anything other than the physical arrangement of buttons (FR-011), not a time-keeping function. Added "Multi-client real-time for physical play" — closes the door on a setup other than one device next to the board, not sync between both players' phones.
- **Deliberately NOT changed (after comparison with alt):** anonymous access remains "none" (the closed-circle persona does not require it); 500 ms responsiveness remains (a deliberately loose threshold for hobbyist hardware; alt's 300 ms would require aggressive optimization disproportionate to the educational goals); no NFR for captive-portal duration (deferred to implementation, no commitment in shape-notes).

<!-- Phase 7 complete. Phase 8: finalization + handoff — IN PROGRESS -->

## Forward: technical-roadmap

Notes for downstream chain steps (tech-stack selection, post-MVP roadmap planning). NOT part of the PRD schema — will not enter `prd.md`.

- **Local chess engine (post-MVP nice-to-have).** In the MVP, position analysis relies exclusively on an external source (FR-021 — e.g., Lichess Cloud Eval). Post-MVP, consider running an in-house engine (e.g., Stockfish) as an alternative: (a) on the backend to evaluate private games without sending positions to an external API; (b) directly on the mobile device (phone/tablet) as an offline analysis mode. The "where does the engine live" decision is deferred to tech-stack selection or to the post-MVP plan.
- **Hardware simulator (testing strategy, not a product).** The idea-shape.md note proposed a dedicated endpoint that mimics the physical board for automated E2E tests and CI/CD without an ESP32. This is a testing strategy / developer infrastructure, NOT a user feature — it is not an FR. The requirement of testability without physical hardware enters the PRD as an NFR (Phase 5); the specific realization (a simulator) is left to tech-stack / planning.
- **Web (Kotlin/Wasm).** Originally part of the cross-platform client in the idea-shape.md note (phone + tablet + panoramic web). Cut from the MVP in Phase 3. Post-MVP it may return — it requires a layout revision and a deployment strategy.
- **Bluetooth (BLE) pairing.** Explicitly out of MVP in the idea-shape.md note (Phase 2 post-MVP). As an alternative to WiFiManager — shorter hardware onboarding. May enter after the MVP.
- **Multi-board pairing.** In the MVP, one default board per user (FR-009). Post-MVP — a full mechanism for pairing with multiple boards (a chess club with several boards).
- **Network-loss recovery for physical play (post-MVP).** FR-023 (nice-to-have in MVP, may slip to post-MVP). Full handling of mid-game disconnects requires buffering of the last state on the ESP32 side + a replay protocol after reconnect + pause/resume UX. In the MVP, minimal coverage (the last confirmed move from FR-015 + manual resume from the game list). Full game continuity after reconnect is a natural post-MVP extension.
- **Time control (post-MVP).** The MVP treats "clock" exclusively as the physical organization of the buttons (FR-011). Full time control (10+5, blitz, classical 90+30, winning on time) requires: a time-setting UI in game creation (FR-004), per-player time-keeping state, end-of-game handling via timeout, and possibly a remaining-time display on the screen. Chess without a clock is a partial experience — a post-MVP roadmap item with high priority.
