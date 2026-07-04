<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Google Sign-In & Own Game History (S-01)

- **Plan**: context/changes/google-signin-own-history/plan.md
- **Scope**: Phase 1 of 6
- **Date**: 2026-06-11
- **Verdict**: APPROVED (both warnings fixed during triage, 2026-06-11)
- **Findings**: 0 critical, 2 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING (both findings fixed in triage) |
| Architecture | PASS |
| Pattern Consistency | WARNING (shared with F1; fixed) |
| Success Criteria | PASS |

## Grounding

Drift agent: migration `20260611103324_games.sql` is a faithful transcription of contract §2.2/§2.4/§2.5/§2.6 — column-by-column MATCH, policies character-for-character, index and trigger verbatim. Notably the nullable `result` CHECK correctly omits `NULL` from the IN list (a literal transcription of the contract's informal "CHECK in (..., NULL)" notation would make the constraint never reject anything). Test suite: all 9 planned assertions present plus 4 reasonable extras (trigger structural + behavioral, A-can-insert-own positive control, anon-cannot-insert). No drift.

Safety agent: no vacuous test passes (cross-user UPDATE/DELETE no-ops verified post-`reset role`; trigger test made observable by seeding `now() - interval '1 day'`); impersonation pattern correct; rollback hygiene clean; `policies_are` asserts *exactly* four policies.

Success criteria re-run at review time: `supabase db reset` clean, `supabase test db` PASS. Manual 1.3 (cloud push + Dashboard) user-confirmed with CLI evidence (`supabase migration list` in sync).

## Findings

### F1 — RLS policies missing `to authenticated` role qualifier

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality (+ Pattern Consistency)
- **Location**: supabase/migrations/20260611103324_games.sql:21-31
- **Detail**: All four `games_*_own` policies omit a `to` clause, defaulting to PUBLIC — evaluated for `anon` too. Privacy held only because `auth.uid()` is NULL for anon. Pattern mismatch vs `position_evals_select_authenticated` (`to authenticated`) and Supabase guidance (skip policy evaluation for non-matching roles). Root cause: contract §2.4 itself omitted the qualifier.
- **Fix**: Contract-first — amend §2.4, then follow-up migration (`alter policy … to authenticated` ×4; shipped migration is append-only), push, pin with `policy_roles_are` ×4 in pgTAP.
- **Decision**: FIXED — contract §2.4 amended (+ frontmatter `updated` bump + prd.md Implementation Decisions dated note per change control); migration `20260611110401_games_rls_hardening.sql` applied locally and pushed to cloud; 4 `policy_roles_are` assertions added (suite now plan(17), PASS).

### F2 — `set_updated_at()` has a mutable search_path

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: supabase/migrations/20260611103324_games.sql:37-40
- **Detail**: Trigger function created without pinned `search_path` (Supabase linter 0011_function_search_path_mutable → standing Security Advisor warning) and without schema qualification. Low exploitability (SECURITY INVOKER, no table refs in body) but a bad template. Root cause: contract §2.6.
- **Fix**: Amend §2.6 (qualify `public.set_updated_at()`, `set search_path = ''`), replace function in the same follow-up migration.
- **Decision**: FIXED — contract §2.6 amended; function replaced via `20260611110401_games_rls_hardening.sql` (same vehicle as F1); applied locally and pushed to cloud.

### F3 — `created_at`/`updated_at` client-writable on INSERT

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: supabase/migrations/20260611103324_games.sql:8-9
- **Detail**: Trigger fires `before update` only; a client can supply arbitrary timestamps via PostgREST on insert. Self-spoofing of own history only — no cross-user impact.
- **Fix**: Accept for MVP; revisit (before-insert trigger) if FR-015 ordering is ever treated as trusted server time.
- **Decision**: SKIPPED — acceptable for MVP.

### F4 — `status='finished'` with `result IS NULL` is representable

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Data quality
- **Location**: supabase/migrations/20260611103324_games.sql:11-12
- **Detail**: No constraint ties `status` to `result`; contract §2.2 doesn't require one. May be intentional (abandoned games). Optional `check (status <> 'finished' or result is not null)` — contract change first if pursued.
- **Decision**: SKIPPED — S-02/S-04 (game creation/finish flows) will settle the semantics.

### F5 — No test that A cannot reassign their own row's `user_id` to B

- **Severity**: ℹ️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Test coverage
- **Location**: supabase/tests/games_rls.test.sql
- **Detail**: The escalation path is blocked only by the *implicit* WITH CHECK of the update policy (Postgres reuses USING) — a subtle default worth pinning.
- **Fix**: Add `throws_ok(update … set user_id = B …, '42501')`.
- **Decision**: FIXED — reassignment test added to suite (part of plan(17), PASS).

## Triage summary

- Fixed: F1, F2 (contract §2.4/§2.6 + `20260611110401_games_rls_hardening.sql` + prd.md note + pgTAP pins), F5 (test)
- Skipped: F3, F4 (MVP-acceptable; F4 revisits in S-02/S-04)
- Post-fix verification: `supabase db reset` clean (3 migrations), `supabase test db` 17/17 PASS, cloud in sync (`migration list`)
