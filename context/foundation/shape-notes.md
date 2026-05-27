---
project: "Smart Chessboard"
version: 1
status: draft
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
created: 2026-05-26
updated: 2026-05-26
checkpoint:
  current_phase: 8
  phases_completed: [1, 2, 3, 4, 5, 6, 7]
  gray_areas_resolved:
    - topic: "context type"
      decision: "Greenfield session for a new software product around an existing physical smart chessboard prototype."
    - topic: "primary persona"
      decision: "Primary persona sharpened to the project author and a small circle of friends who play physical chess."
    - topic: "pain category"
      decision: "Physical chess games are trapped in the physical world: they are not automatically recorded, replayable, or analysis-ready."
    - topic: "core insight"
      decision: "Commercial smart chessboards are expensive and closed, while the author already has a physical prototype; the MVP should turn that prototype into an analysis-ready game-recording product."
    - topic: "product surface"
      decision: "iOS and Android are must-have MVP surfaces; web is a nice-to-have or partial target."
    - topic: "access control"
      decision: "Use an external OAuth identity provider selected downstream, automatically create an account on first sign-in where possible, keep a flat user model, and allow no anonymous game/history/analysis access."
    - topic: "MVP flow"
      decision: "MVP supports dual mode: digital pass-and-play and physical board move capture into the same canonical game flow."
    - topic: "timeline"
      decision: "User reaffirmed a 4-week full-time sprint with weekend work, despite added critical FR detail."
    - topic: "physical board capture"
      decision: "Use continuous move-sequence capture of piece lifts and placements since the last confirmation, with two chess-clock-style confirmation buttons."
    - topic: "FR persistence shape"
      decision: "Complete PGN is the source of truth; FEN is derived, generated, or cached for analysis."
    - topic: "support scope"
      decision: "Live reed-switch diagnostics are must-have; simulator/no-hardware validation strategy is deferred as a non-blocking technical decision; Wi-Fi onboarding is deferred to Forward notes."
    - topic: "non-goals"
      decision: "Remote play, AI opponent play, live engine bar during play, tournaments/rankings, training modules, time control, and multi-client realtime physical play are outside MVP."
  frs_drafted: 18
  quality_check_status: accepted
---

## Vision & Problem Statement

The project author and a small circle of friends play chess on a physical wooden board for enjoyment. Those games usually disappear when they end: they are not automatically recorded, cannot be replayed from a precise position, and cannot be analyzed later unless someone interrupts the flow of play with manual notation.

Commercial smart chessboards solve parts of this problem, but they are expensive and closed for this use case. The author already has a physical prototype with a reed-switch matrix and a microcontroller, so the product opportunity is a software layer that turns the existing board into an analysis-ready game recorder for a small group of real players.

The first useful smart-board experience is not a live engine overlay during play; it is a complete, legal, replayable game record after play. A canonical game record lets players return to their own games and transform positions into evaluated states without changing the physical feel of the game.

Product surface note: `product_type` is `mobile` because iOS and Android are the must-have MVP surfaces. A web target is desired as a nice-to-have or partial target, but it is not a full MVP acceptance surface.

100x scale note: at 100x the initial user scale, the domain rule does not materially change. The product still depends on complete legal game records and post-game position evaluation; only operational capacity would change.

## User & Persona

Primary persona: the project author and a small circle of friends who play physical chess at home or in small informal settings. They are amateur chess players, not tournament operators, coaches, or club administrators.

Each player wants their own account, their own game history, and the ability to return to their own games for replay and analysis. The MVP serves this small-circle use case first; it is not trying to become a mass-market chess platform.

## Success Criteria

### Primary

- A signed-in player can complete a digital pass-and-play game on iOS or Android, save every accepted move into a complete PGN source record, reopen the game from their own history, replay it with full navigation controls, and view post-game position evaluations.
- A signed-in player can play through the physical board flow: moves are captured as confirmed sequences of piece lifts and placements, assigned to the correct side through two confirmation buttons, validated against full chess legality, and saved into the same canonical game record used by the digital flow.

### Secondary

- A player can inspect live reed-switch diagnostics for every square to understand which board fields currently detect a piece or magnet.
- A player can use a web target for selected core game, replay, or analysis views, with partial parity acceptable for the MVP.
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

- FR-006: Player can choose the promoted piece when a pawn promotes. Priority: must-have
  > Socrates: Counter-argument considered: "Auto-promoting to queen would reduce UI work." Resolution: added as must-have; legal replay and accurate PGN require the actual promoted piece.

### Physical Board Play

- FR-007: Player can submit physical-board moves through a confirmed sequence of piece lifts and placements recorded since the previous confirmed move. Priority: must-have
  > Socrates: Counter-argument considered: "A button-triggered final board snapshot would be simpler." Resolution: revised to continuous sequence capture; captures and castling cannot be resolved reliably from a final snapshot alone.

- FR-008: Physical board flow can distinguish two confirmation buttons organized like a chess clock, one for each side. Priority: must-have
  > Socrates: Counter-argument considered: "A single button could confirm the stable state and color could follow turn order." Resolution: added as must-have; two side-specific buttons reduce ambiguity about who confirmed the move.

- FR-009: System can reject illegal, ambiguous, or inconsistent physical-board sequences, pause the game, and ask the player to manually restore the previous legal position with diagnostic assistance before retrying confirmation. Priority: must-have
  > Socrates: Counter-argument considered: "A generic reject-invalid-state rule might be enough." Resolution: added as must-have; hobbyist reed-switch hardware needs an explicit visible recovery path.

- FR-010: Player can view live reed-switch diagnostics for every square. Priority: must-have
  > Socrates: Counter-argument considered: "Live diagnostics may be only a debugging tool." Resolution: promoted to must-have; with imperfect physical detection, players need visible board-state support when resolving errors.

- FR-011: Product can handle physical-board network loss by pausing move acceptance, showing an unambiguous connection-loss message, attempting reconnect, and resuming from the last confirmed move after reconnect. Priority: nice-to-have
  > Socrates: Counter-argument considered: "The MVP can rely on persisted last confirmed moves and manual resume." Resolution: added as nice-to-have; it improves physical-game continuity but does not block MVP acceptance.

### Game Record, History, Replay, And Analysis

- FR-012: Product can automatically save every accepted move into durable game history as play progresses, using complete PGN as the source of truth while deriving, generating, or caching FEN for replay or analysis. Priority: must-have
  > Socrates: Counter-argument considered: "Saving only at game completion and persisting FEN per move could both be simpler in different ways." Resolution: revised; auto-save protects accepted moves, PGN remains the durable source, and FEN is derived or cached as needed.

- FR-013: Player can open a chronological list of their own saved games. Priority: must-have
  > Socrates: Counter-argument considered: "Replay could open only the most recent game or a direct game link." Resolution: added as must-have; returning to saved games is part of the original pain.

- FR-014: Player can replay a chosen saved game with start, back, forward, and end controls. Priority: must-have
  > Socrates: Counter-argument considered: "Move-by-move replay could be generic or forward-only." Resolution: strengthened as must-have; reviewing a precise position requires bidirectional navigation.

- FR-015: Player can request post-game analysis and view position evaluations. Priority: must-have
  > Socrates: Counter-argument considered: "Replay alone may be enough, and external tools could analyze exported PGN." Resolution: kept as must-have; position evaluations are the required analysis output for MVP.

- FR-016: Player can manually mark the end of a game and record its result. Priority: nice-to-have
  > Socrates: Counter-argument considered: "A completed PGN move record can exist without explicit result handling." Resolution: added as nice-to-have; result capture is useful chess notation behavior but does not block the first analysis-ready record.

### Platform Surfaces

- FR-017: Player can use the core play, save, history, replay, and post-game analysis flow on iOS and Android. Priority: must-have
  > Socrates: Counter-argument considered: "A single mobile platform could reduce MVP scope." Resolution: kept as must-have; the chosen product surface is mobile across iOS and Android.

- FR-018: Player can use a web target for selected core game, replay, or analysis views. Priority: nice-to-have
  > Socrates: Counter-argument considered: "Web should be equal to mobile if the project target can produce it." Resolution: kept as nice-to-have; the project may create a web target, but full parity and active validation are not MVP acceptance criteria.

## Non-Functional Requirements

- An accepted move appears on the player's device within 500 ms of the interaction that accepted it, whether the move came from the digital board or a physical-board confirmation.
- A game is private by default: only the signed-in owner can access its game, history, replay, and analysis views in the MVP.
- The core mobile product is available and usable on the latest two major versions of iOS and Android at MVP release time.
- Wi-Fi credentials for a physical board are never committed to source control, stored in the source repository, or baked into reusable firmware artifacts.

## Business Logic

The product records each game as an analysis-ready canonical chess record made only of legal moves, then lets the player replay and transform that record into evaluated positions after the game.

The rule consumes played chess moves from two user-facing channels: interactive digital-board moves and physical-board move sequences confirmed by side-specific buttons. It accepts only move attempts that can be resolved into the legal continuation of the current game.

Every submitted move attempt ends in one of two user-visible outcomes: a specific legal move is accepted and persisted into the game record, or the attempt is rejected and the player receives a correction path. The user encounters this rule during play, while recovering from physical-board detection errors, when reopening the saved game, and when reviewing evaluated positions.

## Access Control

Users sign in through an external OAuth identity provider selected during downstream stack selection. If the person signing in does not already have an account, the product should automatically create one where possible.

The MVP uses a flat user model: every signed-in user has the same capabilities. There are no separate admin, owner, guest, coach, or viewer roles in the MVP.

Anonymous access is not part of the MVP. Unauthenticated users cannot access game, history, replay, or analysis views.

## Non-Goals

- No live engine bar during gameplay. Analysis happens after the game; live evaluation may become a configurable post-MVP option.
- No online matchmaking, remote multiplayer, or online play through external chess platforms. MVP play is local: digital pass-and-play or a connected physical board.
- No AI opponent or local chess engine gameplay. The MVP supports human-vs-human games; engine work is analysis-related, not opponent play.
- No automatic critical-moment detection. Critical moments are a post-MVP nice-to-have beyond basic position evaluations.
- No tournaments, ELO ratings, rankings, or club statistics. The MVP is for personal game history and analysis, not organized competition.
- No training, puzzles, lessons, or educational module. The MVP is a tool for playing and analyzing one's own games.
- No time control. Any chess-clock-shaped hardware in the MVP means confirmation buttons only, not per-player timekeeping or wins on time.
- No multi-client realtime physical play. The MVP assumes one active device next to the physical board, not separate synchronized phones for both players.

## Open Questions

1. **What no-hardware validation strategy should downstream planning use?** — Owner: downstream stack/planning step. Latest acceptable resolution: before implementation planning. Block: no; this does not block PRD generation.

## Timeline Acknowledgment

Acknowledged on 2026-05-26: the MVP remains estimated at 4 weeks of full-time sprint work with weekend effort. The user explicitly accepted the aggressive timeline after the polish round added critical FR detail and confirmed there is no hard deadline.

## Quality Cross-Check

| Check | Status | Notes |
| --- | --- | --- |
| Access Control | present | External OAuth provider deferred to stack selection, auto-created account where possible, flat user model, no anonymous game/history/analysis access. |
| Business Logic | present | One-sentence legal, analysis-ready canonical game record rule captured. |
| Project artifacts | present | This shape-notes file has a valid checkpoint frontmatter block. |
| Timeline-cost acknowledgment | present | 4-week full-time sprint with weekend work accepted on 2026-05-26 and reaffirmed during polish. |
| Non-Goals | present | Remote play, AI opponent play, live engine bar, tournaments/rankings, training, time control, and multi-client realtime physical play excluded from MVP. |
| Preserved behavior | n/a | Greenfield session. |

## Forward: tech-stack

The seed notes mention these technical preferences or candidates for downstream stack selection only: KMP, Ktor, PostgreSQL, Lichess API, WiFiManager, and Jenkins. These are not PRD commitments and should be evaluated after `/10x-prd`.

The OAuth identity provider is intentionally not selected in this shape document. Candidate provider choice belongs to downstream stack selection.

## Forward: technical-roadmap

- Web target: desired as a nice-to-have or partial target. The project may create it early, but active MVP validation focuses on iOS and Android.
- Wi-Fi onboarding: safe user-facing board setup remains a downstream planning topic. The PRD-level requirement is only that credentials are not committed to source control, stored in the repository, or baked into reusable firmware artifacts.
- No-hardware validation: decide downstream whether this is covered by a simulator, mock endpoint, fixtures, manual tooling, or another testing strategy.
- Local chess engine support is a post-MVP nice-to-have for future play against the computer or device-local analysis.
- A live engine evaluation bar during gameplay is a post-MVP nice-to-have and should be configurable if added.
- Critical-moment detection is a post-MVP nice-to-have beyond basic position evaluations.

## Handoff

Next command: `/10x-prd`
