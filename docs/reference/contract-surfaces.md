---
project: "Smart Chessboard"
document: contract-surfaces
version: 1
status: draft
created: 2026-05-27
updated: 2026-06-11
---

## Purpose

This document is the single source of truth for the interfaces between the
three sub-projects of Smart Chessboard:

- **Firmware** — C++ on ESP32, the physical board
- **Mobile** — iOS / Android app, the user-facing surface
- **Backend** — Supabase project (Postgres + Auth + Edge Functions)

It serves a second role: since the backend is implemented as Supabase
configuration plus one Edge Function (no custom server code beyond that), this
document also acts as the backend behavior specification. There is no separate
`prd-backend.md`; sections 2-4 below carry that weight.

## Scope

In scope:

- BLE protocol between firmware and mobile
- Supabase schema, RLS policies, indexes
- Mobile ↔ backend API (PostgREST + Edge Function `lichess-eval`)
- OAuth flow (Google → Supabase)
- PGN / FEN data model conventions
- Cross-cutting failure modes and invariants

Out of scope:

- Internal architecture of firmware (state machines, debouncing strategy)
- Internal architecture of mobile (framework, navigation, state management)
- UI / UX design
- Tech-stack rationale and starter selection (covered by `context/foundation/tech-stack.md`)
- Detailed firmware FRs (covered by `context/foundation/prd-firmware.md`)
- User-facing functional requirements (covered by `context/foundation/prd.md`)

## Change control

Any change to an interface in this document requires:

1. Update this file (bump `updated` in frontmatter).
2. Mirror the impact in affected PRDs:
   - Section 1 (BLE) → `prd-firmware.md` and `prd.md`
   - Sections 2-4 (backend) → `prd.md`
   - Sections 5-6 (data model, failure modes) → all relevant PRDs
3. Note the change with a one-line rationale in the affected PRD's
   "Implementation Decisions" or equivalent section, dated.

---

## 1. BLE Protocol (Firmware ↔ Mobile)

### 1.1 Connection model

- **Roles**: ESP32 is BLE peripheral; mobile app is BLE central.
- **Advertising**: board advertises with a fixed local name (e.g., `SmartChessboard-XXXX` where `XXXX` is the last 4 hex chars of MAC) and a chess-board service UUID.
- **Pairing / bonding**: bonded after first connection; mobile remembers the board across sessions (no re-pairing on relaunch).
- **Single-central**: only one mobile may be connected at a time (consistent with PRD non-goal "No multi-client realtime physical play").

### 1.2 GATT structure

One custom GATT service exposing two characteristics:

| Characteristic | Direction | Properties | Purpose |
| --- | --- | --- | --- |
| `board_event` | board → mobile | notify | Board pushes events to mobile |
| `mobile_command` | mobile → board | write | Mobile sends commands to board |

UUIDs are assigned during firmware implementation and recorded back into this document.

### 1.3 Message catalog — board → mobile (via `board_event` notifications)

All messages share a common 1-byte type tag followed by a typed payload. Total
payload fits within a single BLE notification (≤ 20 bytes on BLE 4.0 default
MTU; larger if MTU negotiated up).

| Type | Tag | Payload | When sent |
| --- | --- | --- | --- |
| `BOARD_SNAPSHOT` | `0x01` | 8 bytes (64 bits, bit N = square N is occupied; a1 = bit 0, h8 = bit 63) | On connect; on explicit request; periodically while in diagnostic mode (e.g., 10 Hz) |
| `SQUARE_EVENT` | `0x02` | 1 byte (square index 0-63 in low 6 bits + event in high 2 bits: `00`=lift, `01`=place) | Real-time on reed-switch state change |
| `BUTTON_EVENT` | `0x03` | 1 byte (`0x00` = white side button, `0x01` = black side button) | On physical button press |
| `DEVICE_STATUS` | `0x04` | `battery_pct` (1 byte 0-100), `firmware_version` (3 bytes: major, minor, patch), `uptime_seconds` (4 bytes LE uint32) | On connect; every ~30 s; on request |

Square indexing convention (binding across firmware and mobile):
`index = file + 8 * rank`, where `file` is a–h = 0–7 and `rank` is 1–8 = 0–7.
So `a1 = 0`, `h1 = 7`, `a8 = 56`, `h8 = 63`.

### 1.4 Message catalog — mobile → board (via `mobile_command` writes)

| Type | Tag | Payload | Effect |
| --- | --- | --- | --- |
| `SET_MODE` | `0x81` | 1 byte (`0x00` = game, `0x01` = diagnostic) | Enter or exit diagnostic mode (affects snapshot push rate) |
| `REQUEST_SNAPSHOT` | `0x82` | (none) | Board immediately emits a `BOARD_SNAPSHOT` |
| `REQUEST_STATUS` | `0x83` | (none) | Board immediately emits a `DEVICE_STATUS` |

Reserved tag space `0x84–0x9F` for post-MVP commands (e.g., LED hints, OTA).

### 1.5 Promotion handling

Physically, the board cannot identify piece type — it only detects a magnet/no
magnet per square. When a pawn promotes:

- Board emits `SQUARE_EVENT(square=N, event=lift)` when the pawn is lifted from
  the 7th rank (white) or 2nd rank (black).
- Board emits `SQUARE_EVENT(square=M, event=place)` when a piece is placed on
  the 8th/1st rank target square.

Board has **no awareness of promotion state**; it just keeps reporting square
events. Mobile is responsible for:

1. Detecting that the lift/place sequence corresponds to a pawn promotion
   (using PGN-derived expected position and chess rules).
2. Showing the in-app promotion picker UI.
3. Blocking acceptance of the next `BUTTON_EVENT` until the user picks a piece.
4. Once picked, completing the move logically with the chosen piece and saving
   to PGN with the appropriate suffix (`=Q`, `=R`, `=B`, `=N`).

If the player presses a confirmation button before picking a piece in the
mobile UI, mobile ignores the button event and surfaces a "Pick promotion
piece" reminder; no move is saved.

### 1.6 Diagnostic mode

Mobile sends `SET_MODE(mode=diagnostic)`. Board:

- Continues to emit `SQUARE_EVENT` on every change (as in game mode).
- Additionally emits a full `BOARD_SNAPSHOT` at higher rate (target 10 Hz).

Mobile renders a live 8×8 grid of reed-switch states. This is the user-facing
support tool for resolving the "illegal/ambiguous sequence" error path
(PRD FR-010/FR-011).

Mobile exits with `SET_MODE(mode=game)`.

### 1.7 Disconnect / reconnect semantics

BLE disconnect can occur from any of: range loss, signal interference, board
power loss, mobile backgrounded for too long, OS-level BLE state change.

On disconnect:

- Mobile pauses move acceptance immediately.
- Mobile shows a visible non-blocking status: "Board disconnected — attempting
  to reconnect" (PRD FR-012).
- Mobile attempts automatic reconnect using BLE central reconnection (typically
  resolves in < 5 s if board is in range).

On reconnect:

- Mobile sends `REQUEST_SNAPSHOT`.
- Mobile compares received snapshot to expected position derived from PGN.
- **Match** → resume normal play.
- **Mismatch** → automatically enter diagnostic mode, prompt user to restore
  the previous legal position (FR-010/FR-011 path), and require a successful
  confirmation before further moves are accepted.

**Invariant**: no move is saved during the disconnect window. Moves accepted
before the disconnect remain persisted.

### 1.8 Out of scope for MVP

- LED-based visual feedback on the board (board does not need to render hints).
- OTA firmware updates over BLE (firmware updated via cable in MVP).
- Multi-board support (one paired board per mobile install).
- Authentication of the board to the mobile (trust-on-first-pair is enough for
  small-circle MVP; can be hardened post-MVP).

---

## 2. Supabase Schema

### 2.1 Authentication (managed)

- Provider: Google OAuth (MVP). Apple Sign In is a known follow-up before iOS
  App Store submission.
- Session: JWT with refresh token (default Supabase Auth behavior).
- User identity in queries: `auth.uid()` — UUID.
- The `auth.users` table is managed by Supabase Auth; the app does not write
  to it directly. A row is created automatically on first sign-in.

### 2.2 Table: `public.games`

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | `uuid` | PK, `default gen_random_uuid()` | |
| `user_id` | `uuid` | NOT NULL, FK → `auth.users(id)` ON DELETE CASCADE | Owner |
| `created_at` | `timestamptz` | NOT NULL, `default now()` | |
| `updated_at` | `timestamptz` | NOT NULL, `default now()` | Trigger updates on each row update |
| `mode` | `text` | NOT NULL, CHECK in (`'digital'`, `'physical'`) | |
| `status` | `text` | NOT NULL, CHECK in (`'in_progress'`, `'finished'`) | |
| `result` | `text` | NULL, CHECK in (`'white'`, `'black'`, `'draw'`, NULL) | NULL while in progress |
| `pgn` | `text` | NOT NULL, `default ''` | Source of truth; grows as moves accepted |
| `white_label` | `text` | NOT NULL, `default 'White'` | Display name |
| `black_label` | `text` | NOT NULL, `default 'Black'` | Display name |

### 2.3 Table: `public.position_evals`

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `fen` | `text` | PK | Cache key — exact FEN |
| `eval_cp` | `integer` | NULL | Centipawn evaluation, White POV |
| `best_move` | `text` | NULL | UCI notation (e.g., `e2e4`) |
| `depth` | `integer` | NULL | Search depth reported by the eval provider |
| `source` | `text` | NOT NULL, CHECK in (`'lichess'`, `'chess-api'`, `'unknown'`) | `'unknown'` = no provider returned an eval |
| `fetched_at` | `timestamptz` | NOT NULL, `default now()` | For staleness checks |

Notes:

- No `user_id` — cache is global. FEN is identical regardless of which user
  reached the position; sharing the cache is correct and saves upstream
  eval-provider calls.
- `source='unknown'` rows are negative-cache entries (Lichess had no eval AND
  the Chess-API.com fallback failed or was unavailable). Cached for a shorter
  TTL than positive entries to allow retry.
- Migration note: the deployed `position_evals` table
  (`supabase/migrations/20260531233302_position_evals.sql`) predates the
  `'chess-api'` source value (added 2026-06-10) — widening the CHECK
  constraint requires a new migration before the eval proxy ships.

### 2.4 Row-Level Security

**`public.games`**: enable RLS, four policies (one per CRUD op):

```sql
alter table public.games enable row level security;

create policy "games_select_own"  on public.games for select
  to authenticated using (auth.uid() = user_id);

create policy "games_insert_own"  on public.games for insert
  to authenticated with check (auth.uid() = user_id);

create policy "games_update_own"  on public.games for update
  to authenticated using (auth.uid() = user_id);

create policy "games_delete_own"  on public.games for delete
  to authenticated using (auth.uid() = user_id);
```

**`public.position_evals`**: enable RLS, read-open for any authenticated user,
no write policy (so PostgREST cannot write). The Edge Function writes via the
`service_role` key, which bypasses RLS.

```sql
alter table public.position_evals enable row level security;

create policy "position_evals_select_authenticated"
  on public.position_evals for select
  to authenticated
  using (true);
```

### 2.5 Indexes

| Index | Purpose |
| --- | --- |
| `games_user_created_idx on games (user_id, created_at desc)` | Chronological own-games list (PRD FR-015) |
| Implicit PK index on `position_evals(fen)` | Cache lookup |

### 2.6 Triggers

`updated_at` auto-touch on `games` rows:

```sql
create or replace function public.set_updated_at()
  returns trigger language plpgsql
  set search_path = '' as $$
begin new.updated_at = now(); return new; end;
$$;

create trigger games_set_updated_at
  before update on public.games
  for each row execute function public.set_updated_at();
```

---

## 3. Mobile ↔ Backend API

### 3.1 Auth (Supabase SDK)

Mobile uses the Supabase client SDK for the platform (Dart/Kotlin/Swift/TS,
depending on `tech-stack.md` outcome). All calls below assume the SDK
auto-attaches the JWT in `Authorization: Bearer <jwt>`.

### 3.2 Game CRUD (PostgREST via SDK)

Listed as conceptual operations; SDK call shapes vary by language.

| Operation | SQL-equivalent | Used by (PRD ref) |
| --- | --- | --- |
| Create game | `INSERT INTO games (mode, status, pgn, white_label, black_label) VALUES (...)` | FR-003 |
| List my games (chronological) | `SELECT ... FROM games ORDER BY created_at DESC` (RLS scopes) | FR-015 |
| Get one game | `SELECT ... FROM games WHERE id = $1` | FR-015, FR-016 |
| Auto-save move | `UPDATE games SET pgn = $1, status = $2, result = $3 WHERE id = $4` | FR-014 |
| Mark finished (manual) | `UPDATE games SET status = 'finished', result = $1 WHERE id = $2` | FR-018 (manual end-of-game) |
| Delete game | `DELETE FROM games WHERE id = $1` | User cleanup (not a numbered FR) |

Mobile does **not** pass `user_id` explicitly on any write — Postgres reads it
from the JWT via `auth.uid()` and RLS enforces ownership on every row.

### 3.3 Edge Function: `lichess-eval`

**Endpoint**: `POST {SUPABASE_URL}/functions/v1/lichess-eval`

**Authorization**: required. Caller must present a valid Supabase JWT.
Anonymous calls rejected with 401.

**Request body**:

```json
{ "fen": "<FEN string>" }
```

**Responses**:

| Status | Body | Meaning |
| --- | --- | --- |
| `200` | `{ "fen", "eval_cp", "best_move", "depth", "source": "cache" \| "lichess" \| "chess-api", "fetched_at" }` | Evaluation available |
| `200` | `{ "fen", "source": "unknown" }` | No provider returned an eval; record cached as negative entry |
| `400` | `{ "error": "invalid_fen" }` | FEN failed validation |
| `401` | `{ "error": "unauthenticated" }` | Missing or invalid JWT |
| `429` | `{ "error": "rate_limited", "retry_after_seconds": N }` | Upstream rate limit hit |
| `502` | `{ "error": "upstream_unavailable" }` | Both providers returned non-2xx, non-recoverable errors |

**Function logic** (eval chain decided 2026-06-10: cache → Lichess →
Chess-API.com → `unknown`):

1. Parse and validate FEN.
2. Look up `position_evals` by FEN.
   - If hit and `fetched_at` within freshness window (suggest: 30 days for
     `source='lichess'` / `source='chess-api'`, 24 hours for
     `source='unknown'`): return cached row with `source: 'cache'`.
3. On miss / stale: call `https://lichess.org/api/cloud-eval?fen=<urlencoded>`.
   On eval: upsert with `source='lichess'` and return. (Lichess stores only
   positions already known to its database — mostly opening theory and popular
   positions — so misses are the common case for amateur games.)
4. On Lichess "no eval" or upstream error: call the fallback
   `POST https://chess-api.com/v1` with `{ "fen": <fen> }` (Stockfish 18 NNUE,
   computes arbitrary positions; no API key; short timeout; depth ≤ 18).
   On eval: upsert with `source='chess-api'` and return.
5. If the fallback also fails: when Lichess answered "no eval", upsert
   `source='unknown'` (negative cache) and return the `unknown` response;
   when both providers errored (rate limit / 5xx), return `429`/`502` without
   caching.

**Fallback provider caveat**: Chess-API.com is a free community service (no
SLA, undocumented rate limits). It sits behind the shared cache, and outages
degrade gracefully to `source='unknown'`. Designated alternate if it
disappears: `https://stockfish.online`. A locally bundled Stockfish is the
post-MVP endgame — PRD Open Question 3.

**Secrets**: function uses `LICHESS_TOKEN` (if Lichess requires; cloud-eval is
public but a token raises rate limit) and `SUPABASE_SERVICE_ROLE_KEY` to write
the cache. Both set via `supabase secrets set ...`; never bundled in mobile.
Chess-API.com requires no API key — the fallback adds no new secret.

### 3.4 Offline-first sync

Mobile keeps an authoritative local copy of in-progress and finished games in
device-local storage. Cloud is the backup/sync layer, not the live source.

- Accepted moves are saved to local storage first, then queued for sync to
  Supabase.
- If the device is offline, gameplay continues without interruption; queued
  updates flush on reconnect.
- For MVP: last-write-wins conflict policy is sufficient because:
  - Physical-mode games are bound to a single device (PRD FR-013).
  - Digital pass-and-play games are inherently single-device.
  - Cross-device editing of the *same* game is not in MVP scope.

### 3.5 What mobile does NOT call

- Mobile never calls Lichess directly. Always goes through `lichess-eval`.
- Mobile never writes to `position_evals` directly. Only the Edge Function does.
- Mobile never queries `auth.users` directly.

---

## 4. OAuth Flow (Google → Supabase)

### 4.1 Setup

**Google Cloud Console**:

- Create an OAuth 2.0 Client ID (Web application type).
- Authorized redirect URI: `https://<project>.supabase.co/auth/v1/callback`.
- Record Client ID and Client Secret.

**Supabase Dashboard → Authentication → Providers → Google**:

- Enable Google provider.
- Paste Client ID and Client Secret.

**Supabase Dashboard → Authentication → URL Configuration**:

- Mobile deep-link scheme (locked during S-01 implementation, 2026-06-10):
  `com.smartchessboard://callback`.
- Web redirect origins (web target wired in S-01): the deployed shell
  `https://smart-chessboard-web.<subdomain>.workers.dev` and the local dev server
  `http://localhost:8080`. The local Supabase stack (`config.toml`) carries the
  same deep link plus `http://localhost:8080`.

### 4.2 Sign-in flow

1. User taps "Sign in with Google" in the app.
2. The SDK starts the OAuth flow in an external browser / custom tab (never an
   embedded WebView — Google blocks WebView OAuth). On Android/iOS this returns
   via the deep link `com.smartchessboard://callback`; on web it is a full-page
   redirect to Google and back to the site origin.
3. User authenticates with Google; Google redirects to Supabase callback.
4. Supabase exchanges the auth code for tokens, creates a row in `auth.users`
   if first sign-in (auto-account creation per PRD Access Control), and
   redirects back (deep link on mobile, site origin on web) with the session.
5. The SDK persists the session via its default session manager
   (multiplatform-settings: SharedPreferences on Android, NSUserDefaults on
   iOS, localStorage on web). Hardened OS-keystore storage (Keychain on iOS,
   Keystore on Android) is **post-MVP** — accepted for the small-circle MVP,
   amended 2026-06-10 to match the implemented S-01 flow. On web, app state is
   lost across the redirect, so on startup the app must let the SDK consume the
   callback URL before concluding signed-out (modeled as an explicit
   `Restoring` auth state).
6. Subsequent SDK calls include the JWT automatically.

### 4.3 JWT lifecycle

- Access token TTL: 1 hour (Supabase default).
- Refresh token: long-lived; SDK auto-refreshes the access token before
  expiry.
- On refresh failure (revoked, network outage past the access window, etc.):
  SDK signs the user out locally and surfaces an auth state change. Mobile
  routes the user back to the sign-in screen.

### 4.4 Sign-out

Mobile calls `supabase.auth.signOut()`:

- Removes tokens from local secure storage.
- Invalidates the refresh token server-side.
- Subsequent API calls return 401 until the user signs in again.

Per PRD FR-002, sign-out is a nice-to-have, but supporting it is essentially
one SDK call and one button in the UI.

### 4.5 Apple Sign In (post-MVP)

Required by App Store Review Guideline 4.8 if the app ships other
social-login options to iOS. Adding it later is a Dashboard config change
plus a small mobile SDK call; no schema or backend code change.

---

## 5. PGN / FEN Model

### 5.1 PGN as source of truth

PGN (Portable Game Notation) is the durable, lossless representation of a
game (PRD FR-014). All other game-state representations (FEN per move, move
list arrays, ply counts) are derived from PGN.

### 5.2 PGN header tags

Every saved game carries at least the following PGN header tags:

```
[Event "Smart Chessboard"]
[Date "YYYY.MM.DD"]            ; date the game was created
[White "<white_label>"]
[Black "<black_label>"]
[Result "1-0" | "0-1" | "1/2-1/2" | "*"]   ; "*" while in progress
[Mode "digital" | "physical"]   ; custom tag for replay/analytics
```

Optional tags (added if available): `[Site]`, `[Round]`. Not required for
MVP.

### 5.3 Move list

Standard SAN (Short Algebraic Notation): `1. e4 e5 2. Nf3 Nc6`.

Promotion: `=Q`, `=R`, `=B`, `=N` (PRD FR-006). The promoted piece is chosen
by the user in mobile UI (see 1.5).

Check / mate annotations (`+`, `#`) are written by the mobile chess library
when generating SAN.

### 5.4 FEN derivation

FEN is **not stored per move**. Mobile derives FEN on demand from PGN using
its chess library (the specific library is decided during mobile
implementation).

For evaluation requests, mobile computes the FEN of the requested position
and sends it to `lichess-eval` (section 3.3). The `position_evals` cache is
keyed by FEN, so identical positions share evaluations across games and
users — even across players.

### 5.5 In-progress vs finished

- `status = 'in_progress'`: PGN may end mid-move-pair; `result = NULL`;
  PGN `[Result "*"]`.
- `status = 'finished'`: PGN is complete; `result` is one of
  `white | black | draw`; PGN `[Result]` tag matches.

Transitions:

- `in_progress → finished` automatically on checkmate or stalemate detected
  by the legality engine (PRD FR-007).
- `in_progress → finished` manually via user action (PRD FR-018) for draws
  by threefold/50-move/insufficient material or resignations.

---

## 6. Failure Modes & Invariants

### 6.1 BLE disconnect during physical game

Covered in detail in 1.7. Summary:

- Pause move acceptance.
- Auto-reconnect.
- On reconnect, verify board state against expected; resume or enter
  recovery.
- **Invariant**: no move is saved during the disconnect window; previously
  accepted moves remain persisted.

### 6.2 Mobile network loss

- Gameplay (digital or physical) continues without interruption — mobile is
  offline-first (section 3.4).
- Moves are persisted to local storage, queued for cloud sync.
- UI may surface a non-blocking "sync pending" indicator.
- On reconnect, queue flushes.
- **Invariant**: every accepted move is durably stored locally before the
  next move is accepted; cloud sync is best-effort but eventually consistent.

### 6.3 App crash mid-game

- Last persisted PGN (local) is the recovery point.
- On relaunch (PRD FR-013):
  - Mobile detects in-progress games and offers to resume.
  - In physical mode: reconnect BLE, send `REQUEST_SNAPSHOT`, compare to
    expected position derived from PGN.
    - Match → resume normal play.
    - Mismatch → enter diagnostic mode, prompt to restore.
  - In digital mode: open the game at the last position, ready for the next
    move.
- **Invariant**: accepted-and-persisted moves survive any crash on either
  side of the connection.

### 6.4 Edge Function / Lichess outage

- `lichess-eval` returns `502` or `429` (section 3.3).
- Mobile surfaces a non-blocking "Analysis temporarily unavailable" state in
  the review/analysis screen.
- Gameplay and saving are **unaffected** — Lichess is only consulted for
  post-game analysis, never on the move-acceptance path.

### 6.5 Out of scope for MVP

- Cross-device concurrent editing of the same game (single-device by design
  per PRD FR-013).
- Auto-detection of threefold repetition / 50-move rule / insufficient
  material draws (manual end-of-game per PRD FR-018 / OQ-2 resolution).
- OTA firmware updates over BLE.
- Multi-board / multi-user-per-board scenarios.
- Time control / clock semantics (PRD non-goal).

---

## References

- `context/foundation/prd.md` — product PRD (mobile + system-level FRs).
- `context/foundation/prd-firmware.md` — firmware PRD (ESP32 / C++ scope).
- `context/foundation/tech-stack.md` — starter / framework selection (produced
  by `/10x-tech-stack-selector`).
