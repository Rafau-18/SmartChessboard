# Post-Game Position Evaluations in Replay (S-03) — Plan Brief

> Full plan: `context/changes/post-game-evals-in-replay/plan.md`

## What & Why

Add position evaluations to the existing Replay screen — the roadmap's **north star** (S-03,
FR-017): a player toggles "Analysis" and sees the centipawn score (or mate-in-N), an eval bar,
and the best move for the position being reviewed. This completes the sign-in → history →
replay → evaluation loop that is the product's core after-game promise, with no hardware and no
rules-engine dependency.

## Starting Point

S-02 shipped the full replay surface (board, transport controls, move list) and the engine
(`Position`) already carries every FEN field. The backend side is specified but unbuilt: the
`lichess-eval` Edge Function does not exist, the deployed `position_evals` cache table predates
the Chess-API.com fallback decision (CHECK constraint too narrow), and the app's Supabase client
lacks the Functions plugin.

## Desired End State

In Replay, toggling Analysis evaluates the viewed position on-demand through cache → Lichess →
Chess-API.com: eval bar + text panel (score / `M3`, best move `e2→e4`) + best-move arrow on the
board. Unknown positions say "No evaluation"; provider outages say "temporarily unavailable"
with Retry; checkmate/stalemate boards short-circuit locally. Wide windows (web/tablet) get a
two-pane layout: board + bar | panel + move list. Works on Android, iOS, web against the hosted
project.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Eval chain & API shape | cache → Lichess → Chess-API → `unknown`, via Edge Function | Secret custody + sole trusted writer to the global FEN-keyed cache | Contract §3.3 / Roadmap |
| Request UX | "Analysis" toggle + auto-fetch on ply change | One deliberate request (FR-017), then frictionless stepping; zero traffic when off | Plan (interview) |
| Presentation | Text panel + eval bar + best-move arrow on board | Full at-a-glance readability for the north-star payoff | Plan (interview) |
| Mate-in-N | New nullable `mate` column + API field (contract edited first) | Both providers return mate; magic cp values would poison a permanent global cache | Plan (interview) |
| Failure UX | `unknown` → inline "no evaluation" (no retry); 429/502 → "temporarily unavailable" + Retry | Distinguishes "doesn't exist" from "try later"; negative cache makes retrying `unknown` pointless | Plan (interview) |
| Multi-pane scope | Adaptive two-pane **inside ReplayScreen** only (≈840 dp breakpoint, no Nav3 scenes) | Delivers "board + eval + moves side-by-side" without new nav machinery on the north-star path | Plan (interview) |
| LICHESS_TOKEN | Set from day one (user-held secret, manual gate) | Higher rate limit from the start; function reads it conditionally either way | Plan (interview) |
| Backend testing | `deno test` with mocked providers + pgTAP for the migration | First Edge Function in the project — full decision chain proven without egress | Plan (interview) |
| Deploy split | Agent runs `db push`/`functions deploy`; user sets secrets | Minimum manual work while secrets never transit the conversation | Plan (interview) |
| Cache key | Function normalizes FEN counters to `0 1` | Identical positions reached at different move numbers must share one cache entry | Plan (research) |
| Terminal positions | Client short-circuits via engine `status()` — no request | Chess-API errors on mate-on-board (smoke-tested); the engine already knows | Plan (research) |
| FEN en passant rule | Emit ep square only when pseudo-legally capturable | Chess-API rejects FENs with non-capturable ep targets (smoke-tested) | Plan (research) |

## Scope

**In scope:** contract amendments (mate, FEN normalization, CORS); widening migration + pgTAP;
Edge Function `lichess-eval` (Deno) + tests; `Position→FEN` in `commonMain`; `functions-kt` +
`EvalRepository`; ReplayViewModel analysis state machine; eval panel/bar/arrow; two-pane
ReplayScreen; deploy + secrets + three-surface E2E; roadmap/lessons write-backs.

**Out of scope:** live engine bar; whole-game precompute or batch prefetch; bundled Stockfish;
SAN generation (best move shows as `e2→e4`); Nav3 scenes / History-Replay list-detail; app-side
eval persistence (Room); function rate limiting/observability; any `games`-table or replay-domain
changes.

## Architecture / Approach

Contract-first, then bottom-up along the §3.3 boundary. App: `ReplayViewModel` →
`EvalRepository` (domain interface) → `functions.invoke("lichess-eval")` with
`position.toFen()`. Backend: Edge Function validates + normalizes FEN, walks cache (TTL 30 d /
24 h negative) → Lichess → Chess-API, upserts via `service_role`, answers with CORS headers
(web). Two cache layers: global Postgres + per-session per-ply map in the ViewModel —
rate-limit exposure is first-eval-only.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Contract & Schema Migration | Amended §2.3/§3.3/§5.4 + widening migration + pgTAP | Getting mate/normalization semantics wrong in a permanent global cache |
| 2. Edge Function `lichess-eval` | The §3.3 chain, deno-tested with mocked providers | First Deno toolchain use; provider quirks (string centipawns, mate signs) |
| 3. FEN Serialization & Eval Data Layer | `Position→FEN` + `EvalRepository` proven with fakes | En passant emission rule subtleties; Native-target regressions |
| 4. Replay Analysis UI & Adaptive | Toggle, panel/bar/arrow, two-pane; first real E2E (local stack) | Request lifecycle (cancel/cache) races; CORS on web |
| 5. Deploy, Cloud E2E & Write-backs | Hosted migration + function + secrets; three-surface E2E | Chess-API free-service flakiness during verification |

**Prerequisites:** S-02 merged (done); local Supabase stack with Docker; linked hosted project;
user able to generate a Lichess token.
**Estimated effort:** ~4–5 sessions, one per phase.

## Open Risks & Assumptions

- Chess-API.com has no SLA and undocumented rate limits — mitigated by the shared cache and the
  documented alternate (stockfish.online); outages degrade to `unknown`/`502`, never block replay.
- Mate sign convention for Black-mates is unverified (smoke tests covered White only) — explicit
  Phase 2 verification item before the mapping is finalized.
- Lichess anonymous→token rate-limit behavior is assumed sufficient for the small circle; the
  cache absorbs repeat traffic.
- The 840 dp two-pane breakpoint is a deliberate minimal implementation (`BoxWithConstraints`,
  no new dependency); a richer app-wide adaptive pass remains future roadmap work.

## Success Criteria (Summary)

- A player can toggle Analysis in Replay and see score/mate, best move (text + arrow), and an
  eval bar for the viewed position on Android, iOS, and web against the hosted project.
- Failure modes are honest: "no evaluation" vs "temporarily unavailable + Retry" vs terminal
  labels — and replay navigation is never blocked by analysis.
- `position_evals` accumulates shared cache rows (`lichess`/`chess-api`/`unknown`), with all
  suites (Gradle ×3, `deno test`, pgTAP) green.
