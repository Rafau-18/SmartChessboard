---
project: "Smart Chessboard"
version: 1
status: draft
created: 2026-06-10
updated: 2026-06-29
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

| ID   | Change ID                     | Outcome (user can …)                                              | Prerequisites    | PRD refs                                              | Status          |
| ---- | ----------------------------- | ----------------------------------------------------------------- | ---------------- | ----------------------------------------------------- | --------------- |
| F-01 | chess-rules-engine            | (foundation) full-legality validation + mate/stalemate detection  | —                | FR-005, FR-007, Guardrails                            | awaiting review |
| F-02 | reed-board-emulator           | (foundation) physical-mode flow runs end-to-end without hardware  | —                | PRD OQ-1 (resolved), US-02                            | implemented     |
| F-03 | firmware-ble-gatt-service     | (foundation) ESP32 firmware speaks the §1 BLE board contract      | —                | FR-FW-002–013                                         | implemented     |
| S-01 | google-signin-own-history     | sign in with Google and see own private game list                 | —                | FR-001, FR-002, FR-015, US-03                         | awaiting review |
| S-02 | replay-seeded-games           | replay a saved game with full controls (seeded snapshots first)   | S-01             | FR-016, US-03                                         | awaiting review |
| S-03 | post-game-evals-in-replay     | view position evaluations in replay (north star)                  | S-02             | FR-017, US-01, US-03                                  | awaiting review |
| S-04 | digital-pass-and-play         | play a fully validated digital game with durable auto-save        | F-01, S-01, S-02 | FR-003, FR-004, FR-005, FR-006, FR-014, FR-019, US-01 | awaiting review |
| S-05 | game-end-and-result           | close a game (auto mate/stalemate + manual result)                | S-04             | FR-007, FR-018, US-01                                 | implemented     |
| S-06 | physical-capture-emulated     | play physical-mode end-to-end against the emulator                | F-01, F-02, S-04 | FR-005, FR-006, FR-008, FR-009, US-02                 | implemented     |
| S-07 | reject-recover-diagnostics    | recover from rejected sequences using live reed diagnostics       | S-06             | FR-010, FR-011, US-02                                 | in progress     |
| S-08 | physical-resume-after-restart | resume an in-progress physical game after app restart             | S-07             | FR-013, US-02                                         | in progress     |
| S-09 | real-board-over-ble           | play the physical flow on the real board over BLE                 | S-06, S-07, F-03 | FR-008, FR-009, FR-010, FR-011, FR-012, US-02         | implemented     |
| S-10 | ble-connectivity-robustness   | (hardening) reliable BLE connect/reconnect + settle pairing model | S-09             | FR-012, NFR reliability                               | proposed        |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme          | Chain                                          | Note                                                                                      |
| ------ | -------------- | ---------------------------------------------- | ----------------------------------------------------------------------------------------- |
| A      | Review loop    | `S-01` → `S-02` → `S-03`                       | Fastest route to the north star under `main_goal: speed` — no foundation, no hardware. **North star reached 2026-06-13 (S-03 implemented, three-surface cloud E2E green).** |
| B      | Play & record  | `F-01` → `S-04` → `S-05`                       | Joins Stream A at `S-04` (needs `S-01`, `S-02`); `F-01` runs parallel to Stream A from day one. **S-04 implemented 2026-06-13 (digital pass-and-play, three-surface cloud E2E green). S-05 implemented 2026-06-17 (game end & result, three-surface E2E green).** |
| C      | Physical board | `F-02` → `S-06` → `S-07` → `S-08` → `S-09`     | Joins Stream B at `S-06`; tail item `S-09` is now unblocked — reed-matrix repair done (2026-06-28) and F-03 on-hardware gates confirmed (2026-06-29); ready to plan. **S-06 implemented 2026-06-19 (physical-mode capture vs the emulator, three-target E2E green; the hardest bet proven without hardware).** |
| D      | Firmware       | `F-03`                                         | Firmware software (BLE GATT service) for the ESP32 board — unit-tested + validated against the F-02 emulator's contract; runs fully parallel to Streams A–C from now and joins Stream C at `S-09` for on-hardware integration. **F-03 implemented + impl-reviewed + merged to `main` 2026-06-20 (`b4b2810`); automated gates green; reed matrix repaired (2026-06-28) and **on-hardware F-03 gates 2.4–3.10 confirmed 2026-06-29** — `S-09` is unblocked and ready to plan.** |

## Baseline

What's already in place in the codebase as of `2026-06-10` (auto-researched + user-confirmed).
Foundations below assume these are present and do NOT re-scaffold them.

- **Frontend:** partial — all KMP targets scaffolded (`SmartChessboard/`: androidApp, shared, webApp, iosApp); wizard-template UI plus a Supabase connectivity probe only; no chess UI, no domain/data/presentation layers, no navigation or DI wiring.
- **Backend / API:** partial — `supabase/` exists as project-as-code with services enabled; one read call from the app (connectivity probe); the `lichess-eval` Edge Function does not exist yet.
- **Data:** partial — `position_evals` table with RLS deployed (`supabase/migrations/20260531233302_position_evals.sql`); no games/moves schema; no local database wiring in the app.
- **Auth:** absent — no OAuth provider configured, no sign-in UI or session handling; the app's Supabase client runs anonymously.
- **Deploy / infra:** partial — web production deploy live since 2026-06-01 (`wrangler.toml`, `_headers` with COOP/COEP, `smart-chessboard-web.<subdomain>.workers.dev`). User confirmation (2026-06-10): web is a test deployment; **mobile is the primary target**, with iOS/Android installed locally during MVP. No CI workflows yet (manual deploy is fine for MVP).
- **Observability:** absent — accepted for MVP scope.

Context note (outside the app codebase): firmware status has since advanced well past this baseline. Reed-matrix scanning was hardware-verified (2026-05-28); the BLE GATT game service (F-03) is now implemented, impl-reviewed and merged (2026-06-20, `b4b2810`, UUIDs assigned). The physical reed matrix has since been **repaired** and the bring-up wiring reworked (diode-direction scan inversion + DGT-clock ADC confirmation buttons, committed 2026-06-28 — `7bb2a12`/`c9719c9`); only the on-hardware F-03 verification pass remains. This affects only S-09.

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
- **Status:** awaiting review — implemented and impl-reviewed (approved, `bce5896`); plan closed out (`643c941`), `change.md` status `impl_reviewed`. Code-complete and perft-verified; **waiting on the user's final review before `/10x-archive`** (no time pressure).

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
- **Status:** implemented — planned, implemented and impl-reviewed; merged to `main` (`f73915e`). Automated gates green across all KMP targets; `change.md` status `impl_reviewed`. Remaining: the manual-acceptance gate (golden-vector hand-recompute + one cross-target test run) collected in `manual-verification.md`; then `/10x-archive`.

### F-03: ESP32 board firmware (BLE GATT game service)

- **Outcome:** (foundation) The ESP32 board firmware implements the §1 BLE contract end-to-end: advertises as the board peripheral, exposes the one GATT service (`board_event` notify + `mobile_command` write), debounces reed-switch transitions into `SQUARE_EVENT`s, encodes `BOARD_SNAPSHOT` / `BUTTON_EVENT` / `DEVICE_STATUS`, and handles `SET_MODE` / `REQUEST_SNAPSHOT` / `REQUEST_STATUS` — verified by firmware unit tests plus the F-02 emulator's contract, so a real board is ready for S-09 to drive. No user-visible outcome on its own.
- **Change ID:** firmware-ble-gatt-service
- **PRD refs:** `prd-firmware.md` FR-FW-002 through FR-FW-013 (BLE peripheral, message encoding, square indexing, debouncing, mobile commands, connection lifecycle); contract `contract-surfaces.md` §1. FR-FW-001 (raw reed sampling) is already implemented and hardware-verified (2026-05-28, see Baseline) — consumed here, not rebuilt.
- **Unlocks:** S-09 (real-board play over BLE — US-02 on hardware). Converts S-09's former open-ended "when does firmware resume" blocker into a counted dependency, and satisfies the firmware half of the §1 BLE contract that F-02's emulator implements on the mobile side.
- **Prerequisites:** — (codes against the frozen `contract-surfaces.md` §1 BLE contract; shares no code with the mobile app, so it needs no mobile slice in place).
- **Parallel with:** F-01, F-02, S-01, S-02, S-03, S-04, S-05, S-06, S-07, S-08 — firmware software is a separate ESP32 / C++ sub-project, independently unit-testable; only S-09 consumes it, so it can be built now alongside all mobile work.
- **Blockers:** —
- **Unknowns:**
  - GATT service / characteristic UUIDs are unassigned (`prd-firmware.md` OQ-5 / contract §1.2) — assigned during firmware implementation and written back into `contract-surfaces.md` §1.2 (the one firmware unknown that touches a shared contract surface). Owner: firmware implementer. Block: no.
  - Remaining `prd-firmware.md` open questions — ESP32 variant (OQ-1), power source (OQ-2), matrix wiring (OQ-3), toolchain ESP-IDF vs Arduino (OQ-4) — are firmware-internal, settled before the first firmware commit, and gate neither F-03 planning nor any other roadmap item. Owner: firmware implementer / hardware build. Block: no.
- **Risk:** Firmware is validated only against the contract + the F-02 emulator until S-09 puts it on real hardware, so the residual risk is contract drift between the emulator's assumptions and real firmware behaviour (the 2026-06-16 `BOARD_SNAPSHOT` byte-layout clarification is exactly this class of bug). Keep the firmware byte-for-byte identical to `contract-surfaces.md` §1 and the emulator's event stream so the S-06–S-08 verification transfers to hardware. The physical reed-matrix repair is a separate hardware task — a precondition for S-09's on-hardware test, not for writing F-03.
- **Status:** implemented — all 4 phases shipped and impl-reviewed (verdict NEEDS ATTENTION → 3 hardening warnings fixed: BLE-consumer task stack, `volatile` conn handle, timer-command logging), merged to `main` 2026-06-20 (`b4b2810`). Automated gates green (`pio test -e native` 15/15, `pio run -e esp32dev` links with NimBLE); the three GATT UUIDs (OQ-5) are written back to `contract-surfaces.md` §1.2, and OQ-2 (USB power, `battery_pct`=100) / OQ-4 (ESP-IDF + NimBLE) are resolved in `prd-firmware.md`. `change.md` status `impl_reviewed`. On-hardware manual gates 2.4–3.10 (advertising, bonding, on-subscribe burst, live square/button events, diagnostic stream, reconnect, malformed-write ignore) are deferred to `manual-verification.md` until the partially-working board is to hand; then `/10x-archive`. Unblocks the firmware half of S-09; the reed matrix was repaired (2026-06-28) and the firmware adapted to the real wiring (matrix-scan inversion + DGT-clock ADC buttons, `7bb2a12`/`c9719c9`). **On-hardware gates 2.4–3.10 confirmed 2026-06-29** — F-03 is ready for `/10x-archive`.

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
- **Status:** awaiting review — implemented and impl-reviewed (phase-6 impl-review recorded `3decfa5`), `change.md` status `impl_reviewed`. Web sliver (Google sign-in + history) shipped per the 2026-06-10 plan decision. **Waiting on the user's final review before `/10x-archive`** (no time pressure).

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
- **Status:** awaiting review — implemented through Phase 5 on `replay-seeded-games` (`3b007e6` → `81ee442`): PGN replay domain, single-game read + seeds, shared `ChessBoardView`, Navigation 3 + Replay screen. Navigation library committed to Nav3 multiplatform (see `lessons.md`). Three-surface cloud E2E confirmed 2026-06-12 (Android / iOS / web, replay to known final position; browser Back/Forward maps to the nav stack). `change.md` status `implemented`. **Waiting on the user's final review before `/10x-archive`** (no time pressure).

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
  - Tablet/web multi-pane layout (board + eval + move list side-by-side on wide screens) — this slice is the natural home for the app-wide adaptive UI pass: the eval panel is what makes side-by-side pay off. S-02 already made `ChessBoardView` size-driven (no board rewrite needed) and capped/centred it on wide screens; decide in `/10x-plan` whether S-03 ships phone-first or adds multi-pane here (tooling: `adaptive` skill + Nav3 scenes). Owner: team. Block: no. **Resolved:** S-03 added a `BoxWithConstraints` two-pane ReplayScreen at ≥840 dp (board + eval bar | eval panel + move list); no Nav3 scenes, scope confined to inside ReplayScreen.
- **Risk:** Completes the review loop end-to-end with zero hardware and zero rules engine involved; the eval chain is two providers deep by decision of 2026-06-10 (cache → Lichess Cloud Eval → Chess-API.com, per `contract-surfaces.md` §3.3) because Lichess alone has no eval for most amateur positions — open scope is the serverless eval proxy, a constraint-widening migration on the deployed `position_evals` table, and the replay-side display.
- **Status:** awaiting review — implemented through Phase 5 on `post-game-evals-in-replay` (north star reached): contract + widening migration (`20260612201124`, `aa30597`); the project's first Edge Function `lichess-eval` (cache → Lichess Cloud Eval → Chess-API.com → negative-cache `unknown`, CORS-complete, `46bd258`); `Position.toFen()` + the app-side eval data layer behind `EvalRepository` (`48e909c`); and the replay analysis UI — toggle, eval bar/panel, best-move arrow, two-pane adaptive ReplayScreen ≥840 dp (`dfe2afc`). Backend live on the hosted project (migration in sync; `lichess-eval` ACTIVE, redeploy reports "no change"; `LICHESS_TOKEN` set). Three-surface cloud E2E confirmed 2026-06-13 (Android / iOS / web — analysis toggle, per-ply fetch with instant revisit, terminal label, airplane-mode Retry; web two-pane at desktop width and browser Back unaffected; CORS preflight echo fixes supabase-kt `x-region`). Hosted `position_evals` accumulates rows (`lichess` openings / `chess-api` middlegames). `change.md` status `implemented`. **Waiting on the user's final review before `/10x-archive`** (no time pressure).

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
- **Status:** awaiting review — implemented through Phase 5 on `digital-pass-and-play`: domain PGN writing (SAN writer + PGN serializer, round-tripped against the S-02 parser/corpus, `5a44423`); the project's first write path — `auth.uid()` column-default migration (`20260612203446`), `createGame`/`updatePgn` repo methods, and the synchronous local write-ahead journal + `GameAutoSaver` realizing the §6.2 ordering invariant (`09dcd1b`); interactive `ChessBoardView` (tap input, legal-target highlights, orientation/flip — backwards-compatible so Replay renders unchanged) + promotion picker (`95842bf`); Play & NewGame screens, MVVM ViewModels (MVVM per `lessons.md`, justified in the plan), `PlayKey`/`NewGameKey` routes with browser-history fragments, and History routing by status/mode (`29dc82d`). Three-surface cloud E2E confirmed 2026-06-13 (Android / iOS / web — create → play with castling/capture/promotion → force-quit mid-game → resume with zero lost moves; airplane-mode sync-pending then reconnect flush; cross-surface replay of the cloud copy; stored PGN carries `[Mode "digital"]`, `[Result "*"]`, labels and date, with `user_id` defaulted server-side). Game end & result stay in S-05; the record remains `in_progress` (mate/stalemate only blocks input + shows a banner). `change.md` status `implemented`. **Waiting on the user's final review before `/10x-archive`** (no time pressure).

### S-05: Game end and result

- **Outcome:** User can close a game: checkmate and stalemate are auto-detected and end the game with the correct result; any other game can be manually ended with win/loss/draw recorded into the result of the saved record.
- **Change ID:** game-end-and-result
- **PRD refs:** FR-007, FR-018, US-01
- **Prerequisites:** S-04
- **Parallel with:** S-06
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Small closer slice; mate/stalemate detection falls out of F-01, so the work is surfacing terminal states and recording results — and manual end is the only way draw-by-rule games close in MVP.
- **Status:** implemented — three-surface E2E verified on Android, iOS, and web (2026-06-17). Pure result mapping + finished-PGN round-trip (`e266455`); atomic `finishGame(status+result+pgn)` repo method + offline-safe finish-aware journal/`GameAutoSaver` (`bbedd14`); `PlayViewModel` auto-close on mate/stalemate + manual end flow (`18df392`); end-game UI — result picker → irreversibility confirm, finished banner, Analyse / Back-to-history — and navigation (`b1e8a95`). E2E follow-up fixes: History list refresh via a repository change signal (`64937c7`), hierarchical browser history so web Back follows the stack (`653b013`), wasm fetch failures handled as `Throwable` so offline no longer crashes (`8c00bac`), and a finished game's cloud flush retried until reconnect (`7f541ab`). `change.md` status `impl_reviewed`; awaiting `/10x-archive`.

### S-06: Physical-mode capture against the emulator

- **Outcome:** User can play a physical-mode game in which lift/place sequences recorded since the last confirmation, confirmed by the correct side's button, are resolved into validated moves in the same canonical record as digital play — exercised end-to-end against the emulator, with promotions blocked until resolved in the app.
- **Change ID:** physical-capture-emulated
- **PRD refs:** FR-005, FR-006, FR-008, FR-009, US-02
- **Prerequisites:** F-01, F-02, S-04
- **Parallel with:** S-05
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Carries the project's hardest open bet — that lift/place sequences resolve into exactly one legal move (captures and castling read from the full sequence, not a final snapshot). Emulator-first keeps hardware out of the loop while that bet is tested.
- **Status:** implemented — emulator-driven E2E green on Android/iOS/web (2026-06-19): a magnet-only lift/place stream resolves to exactly one legal move (captures in both orders, interleaved castling, en passant, promotion) into the same canonical PGN as digital play, with promotion blocked until picked and mate/stalemate auto-closing. Sequence interpreter, pure domain (`b969215`); `createGame(mode)` + `expect/actual supportsPhysicalBoard` + `EmulatedBoard` promoted to `commonMain` (`d76337a`); headless MVI core — pure reducer + the §6.2 journal write gated in the `CommitMove` effect (`0b3db69`); physical screen + gated Digital/Physical picker + per-platform DI binding the emulator (`aa7b001`); emulator-driven E2E + foundation write-backs (p5). The bet is proven without hardware; interactive real-board play over BLE remains S-09. `change.md` status `implemented`; manual code-read/device checks collected in `manual-verification.md`; awaiting `/10x-impl-review` then `/10x-archive`.

### S-07: Sequence rejection and diagnostics-assisted recovery

- **Outcome:** User can see illegal, ambiguous, or inconsistent sequences rejected with the game paused, and use a live per-square reed diagnostics view to restore the previous legal position and retry confirmation.
- **Change ID:** reject-recover-diagnostics
- **PRD refs:** FR-010, FR-011, US-02
- **Prerequisites:** S-06
- **Parallel with:** S-05
- **Blockers:** —
- **Unknowns:** —
- **Risk:** The trust-building slice for hobbyist reed hardware — rejection without a visible recovery path would dead-end games; MVP commits to raw diagnostics only (guided restoration is a PRD non-goal).
- **Status:** in progress — waiting on the manual gate. Automated work for all 3 phases implemented and merged to main (`7adb2e4`): headless MVI core — gate, INCONSISTENT, restore-verify (`fc962d2`); diagnostics UI + screen wiring (`7ba6c6a`); emulator-driven recover E2E for ILLEGAL + INCONSISTENT (`48d6a8d`). Pending the manual device-verification / on-device test pass (see `manual-verification.md`); `change.md` status `implementing`; slice close-out (manual ticks + epilogue + `/10x-impl-review` + `/10x-archive`) still to run.

### S-08: Resume a physical game after app restart

- **Outcome:** User can restart the app mid physical game on the same device, see the expected position rendered, confirm the physical board matches (or restore it via diagnostics), and continue with no accepted move lost.
- **Change ID:** physical-resume-after-restart
- **PRD refs:** FR-013, US-02
- **Prerequisites:** S-07
- **Parallel with:** S-09
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Exercises the durable-save guarantee under process death; same-device only by PRD decision (cross-device handoff of an active game is out of MVP).
- **Status:** in progress — waiting on the manual gate. Automated work for both phases implemented on `impl/physical-resume-after-restart`, merged to `main` 2026-06-28 (`300ea92`): Phase 1 — the `awaitingResumeConfirm` resume gate + the shared `SnapshotReceived` board-confirm transition in the headless MVI core, with reducer units (`6ab630c`); Phase 2 — the fault-injected `PhysicalResumeEndToEndTest` (match→auto-resume / mismatch→restore→resume / promotion-lifted-at-kill), the History "Resume" affordance on in-progress physical rows, and these foundation write-backs. Automated gates green on Android host + iOS simulator (`:shared:testAndroidHostTest`, `:shared:iosSimulatorArm64Test`), wasmJs/iOS compile clean (no physical leak to web). Pending the manual on-device pass (see `manual-verification.md`) + the deferred Phase 1 code-read; `change.md` status `implementing`; slice close-out (manual ticks + epilogue + `/10x-impl-review` + `/10x-archive`) still to run. The `SnapshotReceived` board-confirm seam is the FR-012/S-09 reconnect-reconcile reuse point.

### S-09: Real board over BLE

- **Outcome:** User can play the physical flow against the actual board over BLE: connect, receive live board events, confirm with the side buttons — and save accepted moves into the canonical record on real hardware.
- **Change ID:** real-board-over-ble
- **PRD refs:** FR-008, FR-009, FR-010, FR-011, US-02
- **Prerequisites:** S-06, S-07, F-03
- **Parallel with:** S-08
- **Blockers:** —
- **Unknowns:**
  - The physical reed matrix was **repaired** (2026-06-28) and the firmware bring-up reworked to the real wiring. The **on-hardware F-03 verification pass is COMPLETE** — gates 2.4–3.10 confirmed 2026-06-29 via nRF Connect (advertising, bonding, on-subscribe burst, live square/button events, diagnostic stream, REQUEST_*, periodic status, offline-change reconnect snapshot, malformed-ignore). — Owner: user (hardware). Block: **resolved** — S-09 is ready to plan.
- **Risk:** The only slice touching real hardware; everything upstream is de-risked on the emulator, so residual risk concentrates in BLE fidelity and real reed-switch noise.
- **Status:** **implemented** — the full slice runs on the real board (`SmartChessboard-DA3A`, Phase 8 gate 2026-06-30): connect → live board events → side-button confirms → capture / promotion / castling / en passant → durable save, plus the live reed-matrix overlay, diagnostic mode, and resume, all verified on hardware. **Two course-corrections the on-hardware gate forced:** (1) the Phase-2 link **encryption/bonding was reverted to plaintext** — iOS bonding desynced (stale LTK → `reason=531` drops needing a manual system-Bluetooth "Forget"); plaintext (the original §1.8 posture, F-03-proven with nRF Connect) restored reliable connect. **Provisional — the pairing model must be re-tested across several devices before it's final (see S-10, `lessons.md`).** (2) foreground auto-reconnect + a manual Reconnect button + connect-timeout + scan-timeout + keep-awake + an "already-connected → don't re-scan" connection-gate guard, all added so the flow never hangs. **Residual concern:** raw BLE connect/reconnect stability was flaky on the test Android tablet (possibly device-specific) — spun off to **S-10**. Manual gate ticks in `manual-verification.md`; `change.md` status `implemented`; awaiting `/10x-impl-review` then `/10x-archive`.

### S-10: BLE connectivity robustness & pairing-model re-evaluation

- **Outcome:** Reliable BLE connect / disconnect / reconnect for physical play across multiple real devices, and a settled decision on the board's pairing/security model (confirm or replace the S-09 plaintext revert).
- **Change ID:** ble-connectivity-robustness (proposed)
- **PRD refs:** FR-012 (implemented as reconnect-reconcile in S-09), NFR reliability
- **Prerequisites:** S-09
- **Why:** The S-09 on-hardware gate surfaced BLE connect/reconnect flakiness that was hard to localise (possibly the specific Android tablet's Bluetooth). Separately, S-09 **reverted the Phase-2 link encryption/bonding to plaintext** because iOS bonding desynced (stale-LTK `reason=531` drops needing a manual "Forget"). That revert made the link reliable in single-device testing but is **provisional** — it needs validation across several Android + iOS devices to decide whether plaintext-no-bond is the final MVP model, or whether a bonded/encrypted approach hardened against iOS's stale-LTK behaviour (or a different reconnection / scan-fallback strategy) is warranted.
- **Scope (proposed):** reproduce the connectivity/reconnect issues on ≥2 Android + ≥2 iOS devices; capture the drop `reason=` codes; decide the final pairing model; if device-independent problems remain, harden the reconnect/scan flow (e.g. a scan-by-name fallback should the service-UUID filter miss on some Android stacks). The single-central board stays a constraint (PRD non-goal).
- **Status:** proposed — a post-S-09 hardening slice; not MVP-blocking (S-09 is accepted with the plaintext link working on the tested devices).

## Backlog Handoff

| Roadmap ID | Change ID                     | Suggested issue title                                            | Ready for `/10x-plan` | Notes                                   |
| ---------- | ----------------------------- | ----------------------------------------------------------------- | --------------------- | --------------------------------------- |
| F-01       | chess-rules-engine            | Chess rules engine: full legality + mate/stalemate detection       | yes                   | Run `/10x-plan chess-rules-engine`      |
| F-02       | reed-board-emulator           | Programmatic reed-switch board emulator (test harness)             | yes                   | Run `/10x-plan reed-board-emulator`     |
| F-03       | firmware-ble-gatt-service     | ESP32 board firmware: BLE GATT service, events, commands           | yes                   | Run `/10x-plan firmware-ble-gatt-service` |
| S-01       | google-signin-own-history     | Google sign-in and private game history list                       | yes                   | Run `/10x-plan google-signin-own-history` |
| S-02       | replay-seeded-games           | Replay saved games with board view and controls (seeded snapshots) | no                    | After S-01                              |
| S-03       | post-game-evals-in-replay     | Position evaluations in replay (eval proxy + cache)                | no                    | After S-02 — north star                 |
| S-04       | digital-pass-and-play         | Digital pass-and-play with validation and PGN auto-save            | no                    | After F-01, S-01, S-02                  |
| S-05       | game-end-and-result           | Game end: auto mate/stalemate + manual result                      | no                    | After S-04                              |
| S-06       | physical-capture-emulated     | Physical-mode capture via emulator (sequence → move)               | no                    | After F-01, F-02, S-04                  |
| S-07       | reject-recover-diagnostics    | Sequence rejection + live reed diagnostics recovery                | no                    | After S-06                              |
| S-08       | physical-resume-after-restart | Resume physical game after app restart                             | no                    | After S-07                              |
| S-09       | real-board-over-ble           | Real-board BLE integration                                         | no                    | In implementation — `F-03` done, reed matrix repaired 2026-06-28 |

## Open Roadmap Questions

1. **Which UI architecture and dependency-injection approach does the app commit to?** `tech-stack.md` intentionally defers both (spike a few screens, then record the commitment in `lessons.md`); the spike naturally lands inside S-01/S-02. — Owner: user. Block: roadmap-wide consistency concern, but gates no slice's planning.

## Parked

- **Web target for the digital subset (FR-020, nice-to-have)** — Why parked: `main_goal: speed` keeps the strict must-have sequence first, and the user confirmed (2026-06-10) mobile is the primary target; the deployed web shell stays live, and the shared codebase keeps this cheap to pick up post-MVP. Exception: S-01 pulls a small sliver forward (Google sign-in + empty history surface on web) per its plan decision of 2026-06-10, and S-04 likewise pulls the FR-020 digital-play sliver forward (interactive pass-and-play + durable record verified on web) per its plan decision of 2026-06-12, mirroring the S-01 precedent; the rest of FR-020 stays parked.
- **BLE disconnect auto-recovery (FR-012)** — **Implemented in S-09** (the `reconnectReconciling` reducer gate + foreground auto-reconnect + manual Reconnect, 2026-06-30). Remaining raw-BLE-stack connect/reconnect robustness + the final pairing-model decision are tracked as **S-10**, not here.
- **Deferred UI polish (physical / connection screens, S-09)** — minor UI tweaks the user noted during the S-09 on-hardware gate, to be done opportunistically. Specifics TBD by the user (jot concrete items here or in the `real-board-over-ble` change folder); not MVP-blocking.
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
