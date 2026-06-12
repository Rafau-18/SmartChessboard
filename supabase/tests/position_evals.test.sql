-- pgTAP suite for the position_evals eval cache (S-03).
-- Proves the schema invariants the lichess-eval Edge Function relies on:
-- contract-surfaces.md §2.3 (source values incl. 'chess-api', nullable mate
-- column) and §2.4 (RLS read-open to authenticated, no write policy — only
-- the function writes, via service_role).

begin;

create extension if not exists pgtap with schema extensions;
set local search_path = public, extensions;

select plan(10);

-- Structural ------------------------------------------------------------

select ok(
  (select relrowsecurity from pg_class where oid = 'public.position_evals'::regclass),
  'RLS is enabled on public.position_evals'
);

select policies_are(
  'public', 'position_evals',
  array['position_evals_select_authenticated'],
  'position_evals has exactly the one read policy — no write policies'
);

select policy_roles_are('public', 'position_evals', 'position_evals_select_authenticated',
  array['authenticated'],
  'position_evals_select_authenticated is scoped to authenticated');

select has_column('public', 'position_evals', 'mate', 'mate column exists');

select col_type_is('public', 'position_evals', 'mate', 'integer', 'mate is integer');

select col_is_null('public', 'position_evals', 'mate', 'mate is nullable');

-- Behavioral: table owner (the function's service_role equivalent) ------

select lives_ok(
  $$insert into public.position_evals (fen, eval_cp, mate, best_move, depth, source)
    values ('rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
            22, null, 'e2e4', 18, 'chess-api')$$,
  'a source=chess-api row inserts as table owner'
);

select throws_ok(
  $$insert into public.position_evals (fen, source)
    values ('8/8/8/8/8/8/8/K6k w - - 0 1', 'stockfish')$$,
  '23514', null,
  'a bogus source value is rejected by the CHECK constraint'
);

-- Behavioral: authenticated reads, cannot write -------------------------

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub": "11111111-1111-1111-1111-111111111111", "role": "authenticated"}',
  true
);

select is(
  (select count(*) from public.position_evals),
  1::bigint,
  'authenticated reads the cache (read-open policy)'
);

select throws_ok(
  $$insert into public.position_evals (fen, source)
    values ('8/8/8/8/8/8/8/K6k w - - 0 1', 'unknown')$$,
  '42501', null,
  'authenticated cannot write the cache (no write policy)'
);

select * from finish();

rollback;
