---
project: "Smart Chessboard"
version: 1
status: draft
created: 2026-06-10
updated: 2026-06-11
prd_version: 1
main_goal: speed
top_blocker: capacity
---

# Roadmap: Smart Chessboard

> Derived from `context/foundation/prd.md` (v1) + auto-researched codebase baseline.
> Edit-in-place; archive when superseded.
> Slices below are listed in dependency order. The "At a glance" table is the index.

## Vision recap

The author and a small circle of friends play chess on a physical wooden board, and those games vanish when they end — unrecorded, unreplayable, unanalyzable. The product turns an existing reed-switch board prototype into an analysis-ready game recorder: every game becomes a complete, legal, replayable record that can be reopened and evaluated after play. Per the PRD's own framing, the first useful smart-board experience is that record itself — not a live engine overlay during play.

## North star

**S-03: User can request post-game analysis from the replay view and see position evaluations** — chosen in the interview (2026-06-10): it completes the sign-in → history → replay → evaluation loop that delivers the product's after-game promise, and under `main_goal: speed` it is the earliest end-to-end proof because the chain needs neither the chess-rules engine nor any hardware — the first increment runs over seeded snapshot game records, with the board view built to be reused later by play mode.

> "North star" here means: the smallest end-to-end slice whose successful delivery
> would prove the core product promise — placed as early as Prerequisites allow,
> because everything else only matters if this works.

## At a glance

| ID   | Change ID                     | Outcome (user can …)                                                      | Prerequisites          | PRD refs                                              | Status   |
| ---- | ----------------------------- | ------------------------------------------------------------------------- | ---------------------- | ----------------------------------------------------- | -------- |
| F-01 | chess-rules-engine            | (foundation) full-legality validation + mate/stalemate detection           | —                      | FR-005, FR-007, Guardrails                             | in progress |
| F-02 | reed-board-emulator           | (foundation) physical-mode flow runs end-to-end without hardware           | —                      | PRD OQ-1 (resolved), US-02                             | in progress |
| S-01 | google-signin-own-history     | sign in with Google and see own private game list                          | —                      | FR-001, FR-002, FR-015, US-03                          | ready    |
| S-02 | replay-seeded-games           | replay a saved game with full controls (seeded snapshots first)            | S-01                   | FR-016, US-03                                          | proposed |
| S-03 | post-game-evals-in-replay     | view position evaluations in replay (north star)                           | S-02                   | FR-017, US-01, US-03                                   | proposed |
| S-04 | digital-pass-and-play         | play a fully validated digital game with durable auto-save                 | F-01, S-01, S-02       | FR-003, FR-004, FR-005, FR-006, FR-014, FR-019, US-01  | proposed |
| S-05 | game-end-and-result           | close a game (auto mate/stalemate + manual result)                         | S-04                   | FR-007, FR-018, US-01                                  | proposed |
| S-06 | physical-capture-emulated     | play physical-mode end-to-end against the emulator                         | F-01, F-02, S-04       | FR-005, FR-006, FR-008, FR-009, US-02                  | proposed |
| S-07 | reject-recover-diagnostics    | recover from rejected sequences using live reed diagnostics                | S-06                   | FR-010, FR-011, US-02                                  | proposed |
| S-08 | physical-resume-after-restart | resume an in-progress physical game after app restart                      | S-07                   | FR-013, US-02                                          | proposed |
| S-09 | real-board-over-ble           | play the physical flow on the real board over BLE                          | S-06, S-07             | FR-008, FR-009, FR-010, FR-011, US-02                  | blocked  |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme          | Chain                                          | Note                                                                                      |
| ------ | -------------- | ---------------------------------------------- | ----------------------------------------------------------------------------------------- |
| A      | Review loop    | `S-01` → `S-02` → `S-03`                       | Fastest route to the north star under `main_goal: speed` — no foundation, no hardware.     |
| B      | Play & record  | `F-01` → `S-04` → `S-05`                       | Joins Stream A at `S-04` (needs `S-01`, `S-02`); `F-01` runs parallel to Stream A from day one. |
| C      | Physical board | `F-02` → `S-06` → `S-07` → `S-08` → `S-09`     | Joins Stream B at `S-06`; tail item `S-09` is blocked until firmware work resumes.         |

## Baseline

What's already in place in the codebase as of `2026-06-10` (auto-researched + user-confirmed).
Foundations below assume these are present and do NOT re-scaffold them.

- **Frontend:** partial — all KMP targets scaffolded (`SmartChessboard/`: androidApp, shared, webApp, iosApp); wizard-template UI plus a Supabase connectivity probe only; no chess UI, no domain/data/presentation layers, no navigation or DI wiring.
- **Backend / API:** partial — `supabase/` exists as project-as-code with services enabled; one read call from the app (connectivity probe); the `lichess-eval` Edge Function does not exist yet.
- **Data:** partial — `position_evals` table with RLS deployed (`supabase/migrations/20260531233302_position_evals.sql`); no games/moves schema; no local database wiring in the app.
- **Auth:** absent — no OAuth provider configured, no sign-in UI or session handling; the app's Supabase client runs anonymously.
- **Deploy / infra:** partial — web production deploy live since 2026-06-01 (`wrangler.toml`, `_headers` with COOP/COEP, `smart-chessboard-web.<subdomain>.workers.dev`). User confirmation (2026-06-10): web is a test deployment; **mobile is the primary target**, with iOS/Android installed locally during MVP. No CI workflows yet (manual deploy is fine for MVP).
- **Observability:** absent — accepted for MVP scope.

Context note (outside the app codebase): the firmware sub-project is intentionally parked. Reed-matrix scanning is implemented and hardware-verified (2026-05-28); the BLE GATT game service does not exist and its UUIDs are unassigned. This affects only S-09.

## Foundations

### F-01: Chess rules engine

- **Outcome:** (foundation) A shared, test-verified chess legality capability exists: full move validation (check, pinned pieces, king safety, castling, en passant, promotion) plus checkmate/stalemate detection — ready for any board input channel to consume.
- **Change ID:** chess-rules-engine
- **PRD refs:** FR-005, FR-007, Guardrails
- **Unlocks:** S-04 and S-06 (both play channels validate through it), and the verification path for the "never save an illegal move" guardrail.
- **Prerequisites:** —
- **Parallel with:** F-02, S-01, S-02, S-03
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Largest pure-logic block in the project; sequenced parallel to the review loop so it never delays the north star — if it slips, only play/physical slices slip. Must be verified by its own test corpus before any UI consumes it.
- **Status:** in progress — change folder opened 2026-06-11 (`context/changes/chess-rules-engine/`); next: `/10x-plan`.

### F-02: Reed-switch board emulator

- **Outcome:** (foundation) A programmatic reed-switch board emulator delivers the same event stream as the physical board, drivable from tests and dev tooling, so the whole physical-mode flow runs end-to-end with no hardware.
- **Change ID:** reed-board-emulator
- **PRD refs:** PRD OQ-1 (resolved), US-02
- **Unlocks:** S-06, S-07, S-08 (development and verification without hardware); shrinks the hardware-dependency risk of the whole physical stream to S-09 only.
- **Prerequisites:** —
- **Parallel with:** F-01, S-01, S-02, S-03, S-04, S-05
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Emulator fidelity bounds how much surprise the real board can still spring; keep its event stream message-identical to the documented board contract so verification done on S-06–S-08 transfers to hardware.
- **Status:** in progress — change folder opened 2026-06-11 (`context/changes/reed-board-emulator/`); next: `/10x-plan`.

## Slices

### S-01: Sign in with Google and see own game history

- **Outcome:** User can sign in with Google (account auto-created on first sign-in), sign out, and see their own private, chronologically ordered game list; unauthenticated users reach no game views. Per the S-01 plan decision (2026-06-10), the same sign-in + history surface is also wired on the web target — a deliberate small sliver of parked FR-020.
- **Change ID:** google-signin-own-history
- **PRD refs:** FR-001, FR-002, FR-015, US-03
- **Prerequisites:** —
- **Parallel with:** F-01, F-02
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Wires the auth and per-user data scoping that every later slice inherits; includes a short manual console step (OAuth credentials) — the only non-agent-drivable gate in the slice. Also the natural spike ground for the UI-architecture decision deferred in `tech-stack.md`.
- **Status:** ready

### S-02: Replay a saved game (seeded snapshots first)

- **Outcome:** User can open a saved game from their history and replay it with start / back / forward / end controls on the shared board view — first increment ships over seeded snapshot game records (no play mode yet), per the interview decision of 2026-06-10.
- **Change ID:** replay-seeded-games
- **PRD refs:** FR-016, US-03
- **Prerequisites:** S-01
- **Parallel with:** F-01, F-02
- **Blockers:** —
- **Unknowns:**
  - Exact stored-record shape consumed by replay (parse the PGN source vs cached per-move position snapshots, as FR-014 permits) — Owner: team (decide in `/10x-plan`). Block: no.
- **Risk:** The board view and record format born here are reused by play mode — keep PGN-as-source-of-truth assumptions (FR-014) intact so play-generated games later replay identically to seeded ones.
- **Status:** proposed

### S-03: Position evaluations in replay

- **Outcome:** User can request post-game analysis from the replay view and see position evaluations (centipawn score plus best move) for the position being reviewed.
- **Change ID:** post-game-evals-in-replay
- **PRD refs:** FR-017, US-01, US-03
- **Prerequisites:** S-02
- **Parallel with:** F-01, F-02, S-04
- **Blockers:** —
- **Unknowns:**
  - Chess-API.com (the fallback eval provider decided 2026-06-10) is a free community service — no SLA, undocumented rate limits; smoke-test both providers during `/10x-plan` research (designated alternate: stockfish.online) — Owner: team. Block: no.
  - UX for the residual case where both eval providers fail for a position — Owner: team (decide in `/10x-plan`). Block: no.
- **Risk:** Completes the review loop end-to-end with zero hardware and zero rules engine involved; the eval chain is two providers deep by decision of 2026-06-10 (cache → Lichess Cloud Eval → Chess-API.com, per `contract-surfaces.md` §3.3) because Lichess alone has no eval for most amateur positions — open scope is the serverless eval proxy, a constraint-widening migration on the deployed `position_evals` table, and the replay-side display.
- **Status:** proposed

### S-04: Digital pass-and-play with durable record

- **Outcome:** User can create a game (digital mode, White/Black assignment) and play pass-and-play on the on-screen board: every move fully validated before execution, promotions resolved via a piece picker, every accepted move durably auto-saved with PGN as the source of truth — and the finished record replays through S-02's view, on both iOS and Android.
- **Change ID:** digital-pass-and-play
- **PRD refs:** FR-003, FR-004, FR-005, FR-006, FR-014, FR-019, US-01
- **Prerequisites:** F-01, S-01, S-02
- **Parallel with:** F-02, S-03
- **Blockers:** —
- **Unknowns:**
  - Split between on-device persistence and cloud backup for the durable auto-save (the NFR demands both, available across the player's devices) — Owner: team (decide in `/10x-plan`). Block: no.
- **Risk:** Heaviest user-facing slice, but one coherent workflow (create → move → validate → persist); the crash-safety guardrail ("a crash must not erase accepted moves") lives in its auto-save path.
- **Status:** proposed

### S-05: Game end and result

- **Outcome:** User can close a game: checkmate and stalemate are auto-detected and end the game with the correct result; any other game can be manually ended with win/loss/draw recorded into the result of the saved record.
- **Change ID:** game-end-and-result
- **PRD refs:** FR-007, FR-018, US-01
- **Prerequisites:** S-04
- **Parallel with:** S-06
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Small closer slice; mate/stalemate detection falls out of F-01, so the work is surfacing terminal states and recording results — and manual end is the only way draw-by-rule games close in MVP.
- **Status:** proposed

### S-06: Physical-mode capture against the emulator

- **Outcome:** User can play a physical-mode game in which lift/place sequences recorded since the last confirmation, confirmed by the correct side's button, are resolved into validated moves in the same canonical record as digital play — exercised end-to-end against the emulator, with promotions blocked until resolved in the app.
- **Change ID:** physical-capture-emulated
- **PRD refs:** FR-005, FR-006, FR-008, FR-009, US-02
- **Prerequisites:** F-01, F-02, S-04
- **Parallel with:** S-05
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Carries the project's hardest open bet — that lift/place sequences resolve into exactly one legal move (captures and castling read from the full sequence, not a final snapshot). Emulator-first keeps hardware out of the loop while that bet is tested.
- **Status:** proposed

### S-07: Sequence rejection and diagnostics-assisted recovery

- **Outcome:** User can see illegal, ambiguous, or inconsistent sequences rejected with the game paused, and use a live per-square reed diagnostics view to restore the previous legal position and retry confirmation.
- **Change ID:** reject-recover-diagnostics
- **PRD refs:** FR-010, FR-011, US-02
- **Prerequisites:** S-06
- **Parallel with:** S-05
- **Blockers:** —
- **Unknowns:** —
- **Risk:** The trust-building slice for hobbyist reed hardware — rejection without a visible recovery path would dead-end games; MVP commits to raw diagnostics only (guided restoration is a PRD non-goal).
- **Status:** proposed

### S-08: Resume a physical game after app restart

- **Outcome:** User can restart the app mid physical game on the same device, see the expected position rendered, confirm the physical board matches (or restore it via diagnostics), and continue with no accepted move lost.
- **Change ID:** physical-resume-after-restart
- **PRD refs:** FR-013, US-02
- **Prerequisites:** S-07
- **Parallel with:** S-09
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Exercises the durable-save guarantee under process death; same-device only by PRD decision (cross-device handoff of an active game is out of MVP).
- **Status:** proposed

### S-09: Real board over BLE

- **Outcome:** User can play the physical flow against the actual board over BLE: connect, receive live board events, confirm with the side buttons — and save accepted moves into the canonical record on real hardware.
- **Change ID:** real-board-over-ble
- **PRD refs:** FR-008, FR-009, FR-010, FR-011, US-02
- **Prerequisites:** S-06, S-07
- **Parallel with:** S-08
- **Blockers:** —
- **Unknowns:**
  - When does firmware development resume to implement the BLE GATT game service (service/characteristic UUIDs are still unassigned — firmware sub-project intentionally parked)? — Owner: user. Block: yes.
- **Risk:** The only slice touching real hardware; everything upstream is de-risked on the emulator, so residual risk concentrates in BLE fidelity and real reed-switch noise.
- **Status:** blocked

## Backlog Handoff

| Roadmap ID | Change ID                     | Suggested issue title                                            | Ready for `/10x-plan` | Notes                                   |
| ---------- | ----------------------------- | ----------------------------------------------------------------- | --------------------- | --------------------------------------- |
| F-01       | chess-rules-engine            | Chess rules engine: full legality + mate/stalemate detection       | yes                   | Run `/10x-plan chess-rules-engine`      |
| F-02       | reed-board-emulator           | Programmatic reed-switch board emulator (test harness)             | yes                   | Run `/10x-plan reed-board-emulator`     |
| S-01       | google-signin-own-history     | Google sign-in and private game history list                       | yes                   | Run `/10x-plan google-signin-own-history` |
| S-02       | replay-seeded-games           | Replay saved games with board view and controls (seeded snapshots) | no                    | After S-01                              |
| S-03       | post-game-evals-in-replay     | Position evaluations in replay (eval proxy + cache)                | no                    | After S-02 — north star                 |
| S-04       | digital-pass-and-play         | Digital pass-and-play with validation and PGN auto-save            | no                    | After F-01, S-01, S-02                  |
| S-05       | game-end-and-result           | Game end: auto mate/stalemate + manual result                      | no                    | After S-04                              |
| S-06       | physical-capture-emulated     | Physical-mode capture via emulator (sequence → move)               | no                    | After F-01, F-02, S-04                  |
| S-07       | reject-recover-diagnostics    | Sequence rejection + live reed diagnostics recovery                | no                    | After S-06                              |
| S-08       | physical-resume-after-restart | Resume physical game after app restart                             | no                    | After S-07                              |
| S-09       | real-board-over-ble           | Real-board BLE integration                                         | no                    | Blocked: firmware GATT not implemented  |

## Open Roadmap Questions

1. **Which UI architecture and dependency-injection approach does the app commit to?** `tech-stack.md` intentionally defers both (spike a few screens, then record the commitment in `lessons.md`); the spike naturally lands inside S-01/S-02. — Owner: user. Block: roadmap-wide consistency concern, but gates no slice's planning.

## Parked

- **Web target for the digital subset (FR-020, nice-to-have)** — Why parked: `main_goal: speed` keeps the strict must-have sequence first, and the user confirmed (2026-06-10) mobile is the primary target; the deployed web shell stays live, and the shared codebase keeps this cheap to pick up post-MVP. Exception: S-01 pulls a small sliver forward (Google sign-in + empty history surface on web) per its plan decision of 2026-06-10; the rest of FR-020 stays parked.
- **BLE disconnect auto-recovery (FR-012, nice-to-have)** — Why parked: does not block MVP acceptance; its semantics are already specified in the contract document and it becomes relevant only together with S-09.
- **Store/TestFlight mobile distribution** — Why parked: user decision (2026-06-10): iOS/Android are installed locally during MVP; distribution is a post-MVP decision per `infrastructure.md`.
- **CI pipeline (build/test/deploy workflows)** — Why parked: not PRD scope; manual local builds and manual web deploy suffice for MVP; a plan already exists in `docs/vacation-workflow-todo.md` for when it earns its keep.
- **Local Stockfish per platform (offline / high-depth analysis)** — Why parked: the Chess-API.com fallback (decided 2026-06-10) closes the eval-coverage gap for MVP; a bundled engine (per-platform `expect`/`actual`: ready-made WASM engine build on web, natively compiled Stockfish C++ on iOS/Android) becomes worthwhile only for offline analysis, stricter privacy, or higher depth — PRD OQ-3.
- **Live engine bar during play** — Why parked: PRD §Non-Goals (post-MVP, configurable if added).
- **Online/remote play, matchmaking, external platforms** — Why parked: PRD §Non-Goals (MVP play is local).
- **AI opponent / local engine play** — Why parked: PRD §Non-Goals.
- **Automatic critical-moment detection** — Why parked: PRD §Non-Goals (beyond basic evaluations).
- **Tournaments, ratings, rankings, club statistics** — Why parked: PRD §Non-Goals.
- **Training, puzzles, lessons** — Why parked: PRD §Non-Goals.
- **Time control / per-player clocks** — Why parked: PRD §Non-Goals (buttons are confirmation-only).
- **Multi-client realtime physical play** — Why parked: PRD §Non-Goals (one active device next to the board).
- **Guided physical-board recovery UX** — Why parked: PRD §Non-Goals (MVP ships raw diagnostics + error message only).

## Done

(Empty on first generation. `/10x-archive` appends an entry here — and flips that item's `Status` to `done` — when a change whose `Change ID` matches the item is archived. Do NOT pre-populate.)
