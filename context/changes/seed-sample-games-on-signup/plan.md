# Seed Sample Games on Signup — Implementation Plan

## Overview

Seed a brand-new account with **8 well-known historical chess games** the moment it is
created, so game history is never empty on first run (FR-022). The whole mechanism is
**backend-only**: a Postgres `AFTER INSERT` trigger on `auth.users` (the standard
Supabase `handle_new_user` pattern) inserts the 8 games as ordinary owned `public.games`
rows. No client/app code participates — the app never seeds and never knows seeding
happened; seeded rows surface purely through the existing history query
(`games_select_own`), replay (FR-016), analysis (FR-017), and delete (FR-021) paths.

## Current State Analysis

- **`public.games` schema + RLS already exist** (S-01): `mode` CHECK in
  (`digital`,`physical`), `status` CHECK in (`in_progress`,`finished`), `result` CHECK in
  (`white`,`black`,`draw`,NULL), `user_id` FK → `auth.users` with `default auth.uid()`,
  four per-CRUD owner policies scoped to `authenticated`
  (`supabase/migrations/20260611103324_games.sql`, `20260611110401_games_rls_hardening.sql`,
  `20260612203446_games_user_id_default.sql`). Delete path (FR-021) ships (S-11).
- **Two PGNs already fixture-verified**: The Opera Game and The Immortal Game live in
  `SmartChessboard/…/domain/chess/pgn/PgnFixtures.kt` (commonTest) and are proven to
  replay to their known mate by `PgnParserTest.kt`. They are also byte-copied into
  `supabase/seed.sql` (dev) and `supabase/cloud-seed-replay-games.sql` (manual one-off).
  **Fixture parity** — the seed PGN being byte-identical to the parser fixture — is the
  project's standing proof that a seeded game is replayable.
- **Legality oracle = the parser**: `parsePgn(pgn)` returns a `ReplayGame` with a
  `truncation: PgnTruncation?` field and the invariant `positions.size == sanMoves.size + 1`.
  A fully legal, fully resolved game ⇒ `truncation == null` and every SAN resolved to
  exactly one legal move. `status(position)` reports `Checkmate`/`Stalemate`/etc.
- **pgTAP harness exists**: `supabase/tests/games_rls.test.sql` + `position_evals.test.sql`,
  run via `supabase test db` (Postgres 17). It already inserts `auth.users` rows and does
  structural checks (`has_trigger`, `policies_are`, `col_default_is`).
- **`[db.seed]` runs `seed.sql` after migrations on `supabase db reset`** (config.toml
  `sql_paths = ["./seed.sql"]`, `enabled = true`). `seed.sql` currently `insert`s two
  `auth.users` rows (seed-alice, seed-bob) plus their games directly.
- **Mechanism pre-decided**: PRD FR-022 + Implementation Decision (2026-07-04, S-13) +
  roadmap S-13 pin the trigger approach, the fire-once-per-account semantics, and the 8-game
  set. `lessons.md` supplies two directly relevant priors: (a) a parser is not green until
  it passes on **iOS Native**, not just JVM; (b) an *optional* feature must never break the
  *core* flow (S-09 encryption lesson) — here: a seed failure must never block sign-up.

## Desired End State

A fresh account, on first sign-in, opens onto a chronological history of 8 famous games
(`mode='digital'`, `status='finished'`, correct `result`), each of which replays with full
controls, evaluates through the existing eval path, and can be permanently deleted. Seeding
fires exactly once per account (INSERT-triggered); deleting a seed never re-seeds and a
returning user is never re-seeded. All 8 PGNs pass the parser/legality oracle on every KMP
target and are byte-identical between the migration and `PgnFixtures.kt` (guarded by an
automated parity test). Verify by: `supabase db reset` → a seeded user's history has exactly
8 finished games; `supabase test db` green (incl. the new trigger suite); the parser suite
green on JVM + iOS + wasm; the parity test green.

### Key Discoveries:

- `parsePgn().truncation == null` + `positions.size == sanMoves.size + 1` is the
  target-agnostic legality bar (`PgnParser.kt:44-79`, `ReplayGame.kt:44-51`).
- Games **#1–#4 end in checkmate** (Opera, Immortal, Evergreen, Byrne–Fischer) so may assert
  `status == Checkmate`; **#5–#8 end in resignation** (Kasparov–Topalov, Polgár–Kasparov,
  Deep Blue–Kasparov g6, Levitsky–Marshall) and must **not** carry a mate assertion.
- The `result` **column** uses `white`/`black`/`draw` — not the PGN `[Result]` tag — so each
  game needs an explicit outcome→column mapping verified against a canonical source.
- After this migration lands, `seed.sql`'s `insert into auth.users` will **itself fire the
  new trigger** during `db reset` — seed.sql must be reconciled, not left as-is.
- `androidHostTest` can already see the `internal object PgnFixtures` (screenshot tests do),
  so a JVM-host parity test that reads the migration file and compares is feasible.

## What We're NOT Doing

- **No client/app code changes.** No ViewModel, no UI, no client-side "first run" insert.
  Seeds flow entirely through existing history/replay/analysis/delete surfaces.
- **No backfill of pre-existing accounts.** The trigger fires only on *new* `auth.users`
  inserts; accounts created before this migration stay empty. `cloud-seed-replay-games.sql`
  (the manual backfill) is **withdrawn**, not extended.
- **No `sample_games` template table.** PGNs are inlined in the trigger function.
- **No "already seeded?" guard / idempotency table.** Fire-once is guaranteed by INSERT-trigger
  semantics; deleting a seed inserts no `auth.users` row, so it cannot re-fire.
- **No change to the auth model / open-signup.** Open sign-up is verified in the Supabase +
  Google consoles, not in code; the seed does not touch it.
- **No new eval work.** FR-017 already works on any finished game.

## Implementation Approach

Data first, then the backend that ships it. Phase 1 sources and *proves* the 6 new PGNs
legal/replayable as test fixtures (the same discipline that made #1–#2 trustworthy). Phase 2
builds the entire backend mechanism against those now-trusted PGNs — the trigger migration,
its pgTAP regression, the `seed.sql` reconciliation forced by the trigger/`db reset`
interaction, the automated fixture-parity guard, and the removal of the now-superseded manual
seed. Each phase is independently verifiable and committable.

## Critical Implementation Details

- **SECURITY DEFINER is load-bearing, not decoration.** During sign-up there is no JWT, so
  `auth.uid()` is NULL inside the trigger; the `games_insert_own` policy
  (`with check auth.uid() = user_id`) would reject the insert if RLS applied. The function
  must be `security definer` and owned by the table owner (Supabase `postgres`/`supabase_admin`)
  so RLS is bypassed, and must pin `set search_path = ''` (schema-qualify every object) to
  satisfy the Supabase linter — mirroring `public.set_updated_at()`.
- **Error isolation is mandatory.** Wrap the inserts in `exception when others then raise
  warning …; return new;` so a bad literal or constraint violation logs and lets sign-up
  proceed — a seed bug must never become an auth outage (lessons.md S-09 class).
- **`db reset` ordering interaction.** Migrations apply *before* `seed.sql`; once the trigger
  exists, each `insert into auth.users` in `seed.sql` seeds 8 games automatically. `seed.sql`
  must therefore stop inserting the finished fixtures itself and keep only the edge-case rows
  the trigger does not produce (in-progress, empty-PGN).
- **Parser must be green on Native.** Run `:shared:iosSimulatorArm64Test`, not only the JVM
  host, before declaring the fixtures verified (lessons.md).
- **Parity test path resolution.** The JVM-host parity test reads the migration `.sql` from
  disk; resolve the repo root robustly (walk up from `user.dir`) rather than hard-coding a
  relative depth, since the test's working directory is the Gradle module, not the repo root.

## Phase 1: Source & verify PGNs #3–#8 (fixtures + parser legality)

### Overview

Turn the 6 remaining games into trusted, replayable data by adding them as parser fixtures
and proving each one parses to a complete, legal game on every KMP target.

### Changes Required:

#### 1. PGN fixtures

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/domain/chess/pgn/PgnFixtures.kt`

**Intent**: Add the 6 new games as `internal val` full-PGN blocks (headers + movetext),
sourced from a canonical, byte-clean source with no annotations, comments, NAGs, or
variations (those would truncate). Keep the existing #1–#2 untouched.

**Contract**: 8 named fixtures total. Each is a complete PGN string parseable by `parsePgn`.
The set: Opera (existing), Immortal (existing), Evergreen (Anderssen–Dufresne 1852),
Game of the Century (Byrne–Fischer 1956), Kasparov–Topalov (1999), Polgár–Kasparov (2002),
Deep Blue–Kasparov game 6 (1997), Levitsky–Marshall "Gold Coins" (1912).

#### 2. Legality / replay assertions

**File**: `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/domain/chess/pgn/PgnParserTest.kt`

**Intent**: Assert every new fixture is fully legal and replayable, following the shape of the
existing `operaGameReplaysToItsKnownMate` / `immortalGameReplaysToItsKnownMate` tests.

**Contract**: For all 6 new games — `game.truncation == null` and
`game.positions.size == game.sanMoves.size + 1`. For the mate-ending games (Evergreen,
Byrne–Fischer) additionally `status(game.positions.last()) == GameStatus.Checkmate`. The
resignation-ending games (Kasparov–Topalov, Polgár–Kasparov, Deep Blue–Kasparov, Levitsky–Marshall)
carry **no** mate assertion — asserting a terminal board status on a resigned game would be wrong.

### Success Criteria:

#### Automated Verification:

- `PgnFixtures.kt` contains 8 fixtures; the 6 new ones added
- Parser suite passes on JVM host: `ANDROID_HOME=… ./gradlew :shared:testAndroidHostTest`
- Parser suite passes on iOS Native: `ANDROID_HOME=… ./gradlew :shared:iosSimulatorArm64Test`
- Parser suite passes on wasm: `ANDROID_HOME=… ./gradlew :shared:wasmJsTest`
- ktlint clean: `ktlint -F` from `SmartChessboard/`

#### Manual Verification:

- Each new PGN was sourced from a reliable canonical source (byte-clean movetext, no variations)
- Each game's outcome→`result` mapping (`white`/`black`/`draw`) is verified against a canonical
  source; the suspected roadmap discrepancy for **#6 Polgár–Kasparov** (Polgár played White and
  won ⇒ `white`, vs the roadmap's `0-1`) is resolved and the correct mapping recorded

**Implementation Note**: After Phase 1's automated verification passes, pause for manual
confirmation that PGN sourcing and result mapping were verified before proceeding to Phase 2.

---

## Phase 2: Backend — seed trigger migration + tests + reconciliation

### Overview

Ship the trigger that seeds the 8 verified games on account creation, guard it with a pgTAP
regression, reconcile `seed.sql` with the trigger's `db reset` interaction, add the automated
fixture-parity guard, and retire the superseded manual seed.

### Changes Required:

#### 1. Seed trigger migration

**File**: `supabase/migrations/<timestamp>_seed_sample_games_on_signup.sql`

**Intent**: A `security definer` function that inserts the 8 games for the newly-created user,
plus an `AFTER INSERT` trigger on `auth.users` that calls it. Errors are swallowed so seeding
never blocks sign-up.

**Contract**: `create function public.seed_sample_games() returns trigger language plpgsql
security definer set search_path = ''` — inserts 8 rows into `public.games` with
`user_id = new.id`, `mode='digital'`, `status='finished'`, the verified `result` per game,
`white_label`/`black_label`, inline PGN literals **byte-identical to `PgnFixtures.kt`**, and a
staggered `created_at` (e.g. `now() - interval '<n> days'`) so the history lists chronologically.
Body wrapped in `exception when others then raise warning 'seed_sample_games failed for %: %',
new.id, sqlerrm; return new;`. Trigger: `create trigger on_auth_user_created_seed_games after
insert on auth.users for each row execute function public.seed_sample_games();`.

#### 2. pgTAP trigger suite

**File**: `supabase/tests/seed_on_signup.test.sql`

**Intent**: Structural + functional proof of the trigger, following `games_rls.test.sql`'s style.

**Contract**: Structural — `has_trigger('auth','users','on_auth_user_created_seed_games')` and
the function is `security definer` (`prosecdef`). Functional — inserting one `auth.users` row
yields exactly 8 owned `public.games` rows, all `status='finished'` with non-null `result` in
(`white`,`black`,`draw`) and `mode='digital'`, scoped to that `user_id`; deleting one seeded
game leaves 7 and does **not** re-seed; an `update` to that `auth.users` row does **not** add
rows (INSERT-only fire-once). Wrap in `begin; … rollback;` per the existing suites.

#### 3. Reconcile `seed.sql`

**File**: `supabase/seed.sql`

**Intent**: Stop double-seeding now that the trigger fires on the `auth.users` inserts; keep
only what the trigger cannot produce.

**Contract**: Retain the two `auth.users` inserts (each user now auto-gets 8 finished games via
the trigger) and Alice's edge-case rows — the in-progress game (`[Result "*"]`, `status='in_progress'`)
and the empty-PGN game. Remove the now-redundant explicit finished-game inserts (Opera/Immortal
for Alice, and Bob's finished game — Bob is now covered by his 8 auto-seeded rows). Update the
header comment to state that finished fixtures come from the trigger.

#### 4. Fixture-parity guard (JVM host test)

**File**: `SmartChessboard/shared/src/androidHostTest/kotlin/org/rurbaniak/smartchessboard/…/SeedPgnParityTest.kt` (new)

**Intent**: Fail the build if any migration PGN drifts from its `PgnFixtures.kt` source of truth.

**Contract**: A JVM-only test that reads the seed migration `.sql`, extracts each `$pgn$…$pgn$`
block, and asserts the set is byte-equal to the 8 `PgnFixtures` strings. Resolve the repo root
by walking up from `user.dir` (the module is the working dir, not the repo root).

#### 5. Retire the manual cloud seed

**File**: `supabase/cloud-seed-replay-games.sql` (delete)

**Intent**: Remove the superseded manual one-off; the trigger now covers new accounts and
pre-existing-account backfill is explicitly out of scope.

**Contract**: File removed; no references remain.

#### 6. Contract registry

**File**: `docs/reference/contract-surfaces.md`

**Intent**: Register the new load-bearing names in §2.6 (Triggers).

**Contract**: Add `public.seed_sample_games()` + trigger `on_auth_user_created_seed_games on
auth.users` with a one-line description (seeds FR-022 sample games once at account creation).

### Success Criteria:

#### Automated Verification:

- Migration applies cleanly: `supabase db reset`
- pgTAP green (new + existing suites): `supabase test db`
- Fixture-parity + full JVM suite green: `ANDROID_HOME=… ./gradlew :shared:testAndroidHostTest`
- After `supabase db reset`, each seed user has exactly 8 finished games plus the retained edge
  rows (verify by query)
- `supabase/cloud-seed-replay-games.sql` no longer exists; no dangling references

#### Manual Verification:

- `supabase db reset`, then sign in as a fresh user (or query as the seeded user): history shows
  8 games chronologically (FR-015)
- Open a seeded game → replays with full controls (FR-016) and evaluates via the existing eval
  path (FR-017)
- Delete a seeded game (FR-021) → stays deleted; signing in again does **not** re-seed (fire-once)
- Error isolation: with a deliberately broken PGN literal in a scratch DB, creating a user still
  succeeds (a WARNING is logged, account exists without seeds) — seeding never blocks sign-up

**Implementation Note**: After Phase 2's automated verification passes, pause for manual
confirmation of the fresh-user history, replay/eval, delete-no-reseed, and error-isolation gates.

---

## Testing Strategy

### Unit / fixture Tests:

- Parser legality/replay for all 8 fixtures (`PgnParserTest.kt`) — no-truncation + full ply
  resolution for every game; `Checkmate` only for the 4 mate-ending games.
- Fixture-parity test (`SeedPgnParityTest.kt`, JVM host) — migration PGNs == `PgnFixtures`.

### Integration (DB) Tests:

- pgTAP `seed_on_signup.test.sql` — structural (trigger present, `security definer`) + functional
  (8 rows on insert, correct columns, fire-once, delete-no-reseed).

### Manual Testing Steps:

1. `supabase db reset`; confirm each seed user has 8 finished + edge rows.
2. Fresh sign-in in the app → history lists 8 games; open one → replay + eval work.
3. Delete a seeded game → gone; re-login → not re-seeded.
4. Break a PGN literal in a scratch DB → user creation still succeeds, WARNING logged.

## Performance Considerations

Negligible: 8 single-row inserts fire once per account creation. No hot path, no client cost.

## Migration Notes

- The trigger seeds only accounts created **after** the migration lands. Pre-existing accounts
  (dev/prod) stay empty by design — backfill is out of scope and the manual script is withdrawn.
- Migrations are immutable once applied; the inline PGNs are frozen at land time, with the parity
  test guarding against future fixture drift.

## References

- Change identity: `context/changes/seed-sample-games-on-signup/change.md`
- Roadmap slice: `context/foundation/roadmap.md` → S-13 (seed set pinned under Unknowns)
- Requirement: `context/foundation/prd.md` → FR-022 + Implementation Decision (2026-07-04, S-13)
- Schema/RLS/trigger contract: `docs/reference/contract-surfaces.md` §2.2/§2.4/§2.6
- Legality oracle: `SmartChessboard/…/domain/chess/pgn/PgnParser.kt`, `ReplayGame.kt`,
  existing `PgnParserTest.kt` (Opera/Immortal replay tests)
- Existing seeds: `supabase/seed.sql`, `supabase/cloud-seed-replay-games.sql`
- pgTAP pattern: `supabase/tests/games_rls.test.sql`
- Priors: `context/foundation/lessons.md` — "parser not green until Native passes",
  "force-encryption backfired" (optional feature must not break core flow)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Source & verify PGNs #3–#8 (fixtures + parser legality)

#### Automated

- [x] 1.1 `PgnFixtures.kt` contains 8 fixtures; the 6 new ones added — cbd10ad
- [x] 1.2 Parser suite passes on JVM host (`:shared:testAndroidHostTest`) — cbd10ad
- [x] 1.3 Parser suite passes on iOS Native (`:shared:iosSimulatorArm64Test`) — cbd10ad
- [x] 1.4 Parser suite passes on wasm (`:shared:wasmJsTest`) — cbd10ad
- [x] 1.5 ktlint clean — cbd10ad

#### Manual

- [x] 1.6 Each new PGN sourced from a reliable canonical source (byte-clean, no variations)
- [x] 1.7 Each outcome→`result` mapping verified; #6 Polgár–Kasparov discrepancy resolved

### Phase 2: Backend — seed trigger migration + tests + reconciliation

#### Automated

- [ ] 2.1 Migration applies cleanly (`supabase db reset`)
- [ ] 2.2 pgTAP green incl. new `seed_on_signup.test.sql` (`supabase test db`)
- [ ] 2.3 Fixture-parity + full JVM suite green (`:shared:testAndroidHostTest`)
- [ ] 2.4 Each seed user has exactly 8 finished games + retained edge rows after reset
- [ ] 2.5 `cloud-seed-replay-games.sql` deleted; no dangling references
- [ ] 2.6 `contract-surfaces.md` §2.6 registers the function + trigger names

#### Manual

- [ ] 2.7 Fresh sign-in → history shows 8 games chronologically (FR-015)
- [ ] 2.8 Seeded game replays with controls (FR-016) and evaluates (FR-017)
- [ ] 2.9 Delete a seeded game (FR-021) → stays deleted; re-login does not re-seed (fire-once)
- [ ] 2.10 Broken-PGN scratch test → sign-up still succeeds, WARNING logged (error isolation)
