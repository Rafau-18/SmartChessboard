# Seed Sample Games on Signup — Plan Brief

> Full plan: `context/changes/seed-sample-games-on-signup/plan.md`

## What & Why

Seed a brand-new account with **8 well-known historical chess games** at account creation, so
game history is never empty on first run (FR-022). An empty first-run history reads as broken
and gives a new player nothing to replay, analyze, or delete against; seeds that are the user's
own freely-deletable rows let them exercise every history capability immediately.

## Starting Point

`public.games` schema + RLS + delete path already ship (S-01, S-11). Two of the eight PGNs
(Opera, Immortal) are already fixture-verified in `PgnFixtures.kt` and byte-copied into
`seed.sql` and the manual `cloud-seed-replay-games.sql`. The parser (`parsePgn`) is the standing
legality oracle; a pgTAP harness (`supabase test db`) already exists.

## Desired End State

A fresh account opens onto 8 famous finished games listed chronologically, each replayable,
evaluable, and deletable like any other game. Seeding fires exactly once per account; deleting a
seed never re-seeds and returning users are never re-seeded. All 8 PGNs pass the legality oracle
on every KMP target and stay byte-identical between the migration and `PgnFixtures.kt`.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Mechanism | `AFTER INSERT` trigger on `auth.users` (backend-only) | Server-side, all surfaces at once, app knows nothing | PRD/Roadmap |
| PGN storage | Inline literals in the trigger function | Self-contained immutable migration, no extra table/RLS | Plan |
| Seed failure | Swallow errors (`exception when others → raise warning; return new`) | A seed bug must never block sign-up (lessons.md S-09 class) | Plan |
| `seed.sql` vs trigger | Trigger seeds 8; `seed.sql` keeps only edge rows | Trigger fires on `seed.sql`'s `auth.users` inserts at `db reset` — avoid duplicates, test live | Plan |
| Existing accounts | Withdraw `cloud-seed-replay-games.sql`; no backfill | FR-022 is about *new* accounts only | Plan |
| Legality bar (#3–#8) | `truncation==null` + full ply resolution; `Checkmate` only for the 4 mate games | #5–#8 end in resignation, not mate | Plan |
| DB regression | Add pgTAP `seed_on_signup.test.sql` | Automated fire-once + bypass-RLS regression | Plan |
| Parity guard | Automated migration↔`PgnFixtures` byte-equality test (JVM host) | Guarantees seed == fixtures == replayable | Plan |

## Scope

**In scope:** trigger migration + function; 6 new verified PGN fixtures + parser assertions;
pgTAP trigger suite; `seed.sql` reconciliation; JVM-host parity test; delete `cloud-seed-replay-games.sql`;
contract-surfaces §2.6 registration.

**Out of scope:** any client/app code; backfill of pre-existing accounts; a `sample_games` table;
an "already seeded?" guard; auth-model / open-signup changes; new eval work.

## Architecture / Approach

Data first, then the backend that ships it. Phase 1 proves the 6 new PGNs legal/replayable as
parser fixtures on JVM + iOS + wasm. Phase 2 builds the whole backend mechanism against those
now-trusted PGNs: the `security definer` trigger (inline PGNs byte-identical to fixtures, errors
swallowed), its pgTAP regression, the `seed.sql` reconciliation forced by the `db reset` trigger
interaction, the automated fixture-parity guard, and removal of the superseded manual seed.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Fixtures + parser legality | 8 verified, replayable PGN fixtures (6 new) on all targets | A sourced PGN carries variations/annotations and truncates; wrong outcome→`result` mapping (esp. #6 Polgár–Kasparov) |
| 2. Backend trigger + tests | Trigger seeds 8 on signup; pgTAP + parity green; `seed.sql` reconciled; manual seed retired | Trigger must bypass RLS (`security definer`, `auth.uid()` NULL at signup) and never block sign-up on failure |

**Prerequisites:** S-01 (`games` schema + RLS + history) and S-11 (delete) — both shipped.
Local Supabase CLI (`supabase db reset`, `supabase test db`) and the KMP toolchain (`ANDROID_HOME`).
**Estimated effort:** ~1–2 sessions across 2 phases; small backend-only slice.

## Open Risks & Assumptions

- **Result mapping**: the roadmap's pinned `result` for **#6 Polgár–Kasparov** looks inverted
  (Polgár played White and won ⇒ `white`, roadmap says `0-1`). Verify each game's outcome against
  a canonical source during Phase 1 and record the correct `white`/`black`/`draw` mapping.
- **PGN cleanliness**: sourced movetext must be free of variations/comments/NAGs or the parser
  truncates — source from a clean canonical database.
- **RLS/definer**: `auth.uid()` is NULL during sign-up, so the function must be `security definer`
  (owned by the table owner) with `set search_path = ''`; otherwise the insert is rejected.
- **`db reset` interaction**: the trigger fires on `seed.sql`'s `auth.users` inserts — `seed.sql`
  must be reconciled or dev data double-seeds.

## Success Criteria (Summary)

- A fresh account's history opens with 8 replayable/evaluable/deletable famous games.
- Seeding fires once per account; deleting a seed or logging in again never re-seeds.
- Parser suite green on JVM + iOS + wasm; `supabase test db` green (incl. the trigger suite);
  the fixture-parity test green.
