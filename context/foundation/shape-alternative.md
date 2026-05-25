---
project: "Smart Chessboard"
context_type: greenfield
created: 2026-05-25
updated: 2026-05-25
product_type: mobile
target_scale:
  users: small
  qps: low
  data_volume: small
timeline_budget:
  mvp_weeks: 8
  hard_deadline: null
  after_hours_only: false
checkpoint:
  current_phase: 8
  phases_completed: [1, 2, 3, 4, 5, 6, 7]
  gray_areas_resolved:
    - topic: "Pain category"
      decision: "Workflow friction & missing capability (bridging physical board and digital recording), trapped data, and excessive commercial chessboard cost."
    - topic: "Insight"
      decision: "Commercial smart boards are too expensive/complex; cheap DIY projects lack stable UX/CI-CD validation. Physical button debouncing resolves sensor noise cheaply."
    - topic: "Primary persona"
      decision: "Chess enthusiasts/hobbyists who own/want a physical board but want frictionless PGN tracking and analysis."
    - topic: "Authentication Method"
      decision: "OAuth 2.0 (Lichess/Google login) generating a JWT for session management."
    - topic: "Authorization Model"
      decision: "Flat user model with strict owner-based data isolation. Offline local pass-and-play is available anonymously."
    - topic: "Timeline and MVP Discipline"
      decision: "Committed to an 8-week MVP timeline with sustained dedication, acknowledging the complexity of hardware-software integration."
    - topic: "Move Capture Detail"
      decision: "ESP32 must transmit both final board state and sequence of piece lift/placement events to handle ambiguous moves/captures."
    - topic: "Lichess Engine Analysis"
      decision: "Stockfish evaluation is disabled during live play to preserve authentic game experience; analysis is fully post-game."
    - topic: "Domain Rule and Logic Validation"
      decision: "Full real-time FIDE move validation computed to determine turn state transition and illegal position handling."
    - topic: "Product Surface"
      decision: "Kotlin Multiplatform (KMP) Mobile App (Android + iOS), with Web-Wasm compiled client deferred as a nice-to-have."
    - topic: "Timing & Scope Mode"
      decision: "Full-time project engagement (day job) with no hard calendar deadline, adhering to an 8-week delivery budget."
    - topic: "Non-Goals Exclusions"
      decision: "Excluded local mobile engine, BLE configuration, online matchmaking, and active RFID piece recognition to secure MVP focus."
  frs_drafted: 11
  quality_check_status: accepted
---

# Smart Chessboard - Shaping Notes

## Vision & Problem Statement
Traditional chess players are trapped in a dilemma: they must either sacrifice the physical, tactile, and spatial experience of a real wooden board to enjoy the benefits of automatic game recording, digital analysis, and remote play online, or play physically and suffer the friction of manual notation and lack of real-time analytical tools. Commercial smart chessboards (such as DGT) are excessively expensive and complex to set up for casual play.

Our insight is that by leveraging an affordable DIY hardware setup (ESP32 with magnetic reed switches) paired with a robust, responsive KMP client and a proxy backend, we can provide a premium, seamless digital-physical chess experience. An elegant physical validation button (e.g., chess clock style) eliminates transient sensor noise, enabling highly reliable move detection without expensive high-end sensors.

> **Scale Consideration:** At 100x scale, Ktor WebSockets would require clustering to handle massive concurrent states, and Lichess Cloud API rate limits would necessitate a self-hosted Stockfish backend proxy or a strict local/server query cache.


## User & Persona
### Primary Persona: The Tech-Chess Enthusiast
- **Name/Role:** Jan, a hobbyist chess player and tech enthusiast.
- **Context:** Plays chess frequently, wants to improve, and prefers the tactile feel of a physical wooden board over staring at computer screens.
- **Moment of Use:** When starting a game on his physical wooden board at home, either solo (for analysis) or in local pass-and-play with a friend, and wanting the game to be seamlessly captured digitally.

## Timeline acknowledgment
Acknowledged on 2026-05-25: 8-week MVP requires sustained dedication; user accepted.

## Success Criteria
### Primary
- **Graceful Error Recovery & 100% Valid Game Capture:** The system guarantees that 100% of physical chess moves successfully written to the PostgreSQL database are chess-legal and complete. If hardware sensor glitches, mechanical reed failures, or piece misalignments occur upon pressing the physical clock button, the app blocks move finalization, warns the user immediately, and displays a live diagnostic grid to guide physical piece correction.
- **Complete PGN/FEN Generation:** Every finished game must have a complete, syntactically correct PGN and FEN representation in the database, allowing full replay.


### Secondary
- **Sensor State Diagnostics:** An in-app visualization dashboard showing real-time states of individual reed switches under each square to identify misaligned or missing physical pieces.
- **Dynamic Stockfish Evaluation Bar:** Visual display of engine evaluation retrieved from the Lichess Cloud API.

### Guardrails
- **Latency Floor:** End-to-end latency from pressing the physical chess clock confirmation button to the move showing on the client screen (via WebSockets) must be under 300 ms.
- **CI/CD Independence:** The entire system must be buildable, testable, and verifiable in Jenkins without requiring any physical ESP32 hardware connected (fully relying on the hardware simulator).

## Access Control
- **Authentication Method:** OAuth 2.0 (specifically integrating Lichess and/or Google login). Upon successful OAuth flow, the system issues a JWT for session management.
- **Authorization Model:** Flat user model. All authenticated users have equal privileges.
- **Data Isolation:** Strict owner-based isolation. Each user can only view, sync, or delete their own game history, profiles, and configured chessboards.
- **Unauthenticated State:** Unauthenticated users can access the landing/login screen and play in local offline digital mode (pass-and-play), but cannot sync games to the database, save history, or interface with the physical chessboard proxy backend.

## Functional Requirements
### Hardware & ESP32 Integration
- FR-001: The system can read magnetic reed switch matrices to detect piece placements. Priority: must-have
  > Socrates: Counter-argument considered: "Mechanical reed switches may fail or suffer magnetic crosstalk (false adjacent triggers)." Resolution: Accepted as a hobbyist-level risk; physical piece alignment diagnostics in the UI (FR-006) will mitigate debugging pain.
- FR-002: The ESP32 captures and sends both the stable board state and the sequence of lifted and placed squares (lift/placement events) to the Ktor proxy backend upon physical clock button press, allowing unambiguous move and capture reconstruction. Priority: must-have
  > Socrates: Counter-argument considered: "Transmitting raw events creates event noise; processing moves entirely on ESP32 or sending raw sequence + stable state have tradeoffs." Resolution: Keep flexible; the system must support transmitting stable state and enough context (computed move or event sequence) to resolve captures and ambiguities.
- FR-003: The ESP32 can launch a captive WiFi Portal (WiFiManager) for the user to securely configure local Wi-Fi credentials. Priority: must-have
  > Socrates: Counter-argument considered: "Captive portals can be unstable on modern OS security setups." Resolution: The absolute constraint is to avoid hardcoding Wi-Fi credentials; the exact mechanism (captive portal, BLE, etc.) is deferred to implementation planning.

### Game Logic & Play Modes
- FR-004: Player can play a physical chess game on the physical board with moves synchronized to the KMP app in real-time. Priority: must-have
  > Socrates: Counter-argument considered: "WebSocket live sync adds complexity over simple end-of-game syncing." Resolution: Reaffirmed; real-time visual feedback is critical for the hybrid play experience.
- FR-005: Player can play a local offline digital-only chess game (pass-and-play) directly on the KMP client touch screen. Priority: must-have
  > Socrates: Counter-argument considered: "Digital-only mode dilutes focus and increases KMP client UI scope." Resolution: Reaffirmed; pass-and-play is a crucial fallback that makes the app highly useful even when physical hardware is not present.
- FR-006: Player can see a live visual state of individual chessboard fields to identify raw piece detections for diagnostic purposes. Priority: must-have
  > Socrates: Counter-argument considered: "This is nice-to-have scope creep." Resolution: Promoted to **must-have** because hardware debugging without raw sensor state visualization in the UI is highly impractical during development.

### Backend & Integration
- FR-007: Player can log in using OAuth 2.0 (Lichess/Google) to secure their session. Priority: must-have
  > Socrates: Counter-argument considered: "OAuth blocks offline usage or causes multiplatform KMP integration pain." Resolution: The application will employ a local-first queue; unauthenticated or offline games are stored in local client storage and queued for synchronization once an internet connection and OAuth session are established.
- FR-008: The Ktor backend can calculate and record the complete move history in standard PGN and FEN formats in PostgreSQL. Priority: must-have
  > Socrates: Counter-argument considered: "PostgreSQL adds deployment weight compared to SQLite." Resolution: Reaffirmed; PostgreSQL is preferred for the central backend database, while the client uses local caching.
- FR-009: The Ktor backend can broadcast move updates via WebSockets in real-time to active KMP clients. Priority: must-have
  > Socrates: Counter-argument considered: "WebSockets require Ktor statefulness and are less resilient than polling or SSE." Resolution: Reaffirmed; low-latency sync under 300ms is essential, making WebSockets the selected transport.
- FR-010: Player can request a post-game analysis and view Stockfish evaluation metrics (eval bar) for individual positions retrieved from the Lichess Cloud API. Priority: must-have
  > Socrates: Counter-argument considered: "Relying on external API rate limits introduces a point of failure." Resolution: Reaffirmed; Lichess Cloud is perfect for free MVP post-game analysis, and evaluations can be cached locally to avoid hitting rate limits.

### Testing & Simulation
- FR-011: The Ktor backend can expose a dedicated simulator endpoint mimicking the ESP32 chessboard to run automated E2E tests. Priority: must-have
  > Socrates: Counter-argument considered: "Backend simulator does not test actual firmware and is extra work." Resolution: Kept as must-have; allows CI/CD pipeline automation and offline client testing without physical hardware, even if firmware testing is separate.

## User Stories
### US-01: Player makes physical chess moves with real-time digital sync
- **Given** an authenticated player with the KMP app open and connected to a configured physical chessboard, with a game in progress.
- **When** the player moves a physical piece (e.g., moves a pawn from E2 to E4) and presses the physical validation button (clock button).
- **Then** the KMP app immediately (within 300 ms) visualizes the move on the digital board, updates the move notation list, and the server records the move in the PostgreSQL database.

#### Acceptance Criteria
- Pressing the physical clock button filters transient reed switch noise (debouncing).
- If the move violates chess rules, the app displays a clear error state and prompts the user to correct the piece positions on the board, preventing database sync until corrected.
- Connection losses trigger a prominent in-app diagnostics map of the board.
- The Stockfish engine evaluation bar is explicitly **disabled** during active live gameplay.

### US-02: Player reviews and analyzes a completed game post-match
- **Given** a logged-in player viewing their game history screen in the KMP client.
- **When** the player selects a completed game and navigates through the moves or requests a full game analysis.
- **Then** the KMP app fetches the Stockfish engine evaluation for each position from the Lichess Cloud API, displays a dynamic evaluation bar, and highlights blunders, mistakes, or optimal alternatives.

#### Acceptance Criteria
- Evaluation metrics (Stockfish scores) are retrieved on demand using the FEN representation of the selected position.
- The evaluation bar smoothly adapts and updates when transitioning between moves.
- Full-game analysis requests cache the retrieved Lichess evaluations in the local database to avoid redundant external API calls during future reviews.

## Business Logic
The application strictly validates and enforces FIDE chess rules in real-time based on piece placements and transition events, transitioning the game state only upon legal moves and translating physical play into canonical digital chess notation (PGN and FEN).

The system consumes three primary inputs to execute this rule: the initial/current board position (FEN), the full history of moves played in the session (PGN), and the sequence of sensor changes (lift/placement events) transmitted when the physical validation button is pressed. Using these inputs, the logic reconstructs the path of the moved piece, determines if any piece was captured, verifies if the move is legal under FIDE chess rules, and calculates the updated game state.

If the move is validated as legal, the system appends it to the game's PGN/FEN history, transitions the turn, and broadcasts the updated state to the clients. If the move is illegal, the system rejects the transaction, flags the error, and provides diagnostic visual feedback displaying the illegal board layout, preventing turn progression until the physical board is corrected.

## Non-Functional Requirements
- **Low-Latency Sync:** The end-to-end latency from pressing the physical confirmation button on the chessboard to the move visual update on the KMP client must be under 300 ms (p95) in local Wi-Fi environments.
- **Offline Caching & Resilience:** The KMP client must store active game states and PGN logs locally, enabling uninterrupted play when offline, and automatically synchronize queued games to the backend database once a network connection is re-established.
- **Automated Verification Speed:** The hardware simulator must complete a full automated game replay simulation (100 legal moves) within 10 seconds in the CI/CD pipeline.
- **Captive Portal Setup Duration:** The Captive Portal captive portal session on ESP32 must allow network configuration and board registration to be fully completed by the user in under 60 seconds from device startup.

## Non-Goals
- **Local Mobile Stockfish Engine:** Running Stockfish locally on the mobile client is out of scope for the MVP; all chess engine calculations are delegated to the backend or external Lichess Cloud APIs to reduce KMP binary size and battery consumption.
- **Bluetooth (BLE) Chessboard Pairing:** Pairing and managing the physical chessboard via Bluetooth is excluded; network and proxy connectivity is handled strictly over local Wi-Fi and configured via Captive Portal (WiFiManager) on ESP32.
- **Online Multiplayer Matchmaking:** Dedicated online multiplayer matchmaking or lobbies are out of scope; the MVP focuses strictly on local hybrid play (physical board synced to device) and digital-only pass-and-play.
- **Active Physical Piece Recognition:** Hardware piece identification (e.g., via RFID or camera vision) is out of scope; the physical board uses simple magnetic reed switches to detect presence/absence (occupancy), and the application software infers the piece identities based on chess move history.

