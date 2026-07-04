-- pgTAP suite for the FR-022 onboarding seed (S-13).
-- Proves contract-surfaces.md §2.6: on_auth_user_created_seed_games fires once per
-- auth.users INSERT, seeding exactly 8 finished digital games owned by the new user;
-- deletes never re-seed and updates never fire it.
--
-- User UUID deliberately differs from seed.sql (3333…/4444…) and games_rls.test.sql
-- (1111…/2222…) so no suite collides with another's rows.

begin;

create extension if not exists pgtap with schema extensions;
set local search_path = public, extensions;

select plan(8);

-- Structural ------------------------------------------------------------

select has_function('public', 'seed_sample_games',
  'seed_sample_games function exists');

select ok(
  (select prosecdef from pg_proc where oid = 'public.seed_sample_games()'::regprocedure),
  'seed_sample_games is security definer (runs as table owner, bypasses RLS — no JWT at sign-up)'
);

select has_trigger('auth', 'users', 'on_auth_user_created_seed_games',
  'AFTER INSERT seed trigger exists on auth.users');

-- Functional: inserting one auth.users row seeds exactly 8 games ---------
-- Runs as postgres — table owner bypasses RLS, like the other suites.

insert into auth.users (instance_id, id, aud, role, email)
values
  ('00000000-0000-0000-0000-000000000000', '55555555-5555-5555-5555-555555555555',
   'authenticated', 'authenticated', 'seed-trigger@test.local');

select is(
  (select count(*) from public.games
    where user_id = '55555555-5555-5555-5555-555555555555'),
  8::bigint,
  'inserting an auth.users row seeds exactly 8 games for that user'
);

select is(
  (select count(*) from public.games
    where user_id = '55555555-5555-5555-5555-555555555555'
      and mode = 'digital' and status = 'finished'
      and result in ('white', 'black', 'draw')),
  8::bigint,
  'all 8 seeded games are finished digital games with a real result'
);

select is(
  (select count(distinct created_at) from public.games
    where user_id = '55555555-5555-5555-5555-555555555555'),
  8::bigint,
  'seeded created_at values are staggered (chronological history, FR-015)'
);

-- Fire-once: delete does not re-seed -------------------------------------

delete from public.games
  where id = (select id from public.games
                where user_id = '55555555-5555-5555-5555-555555555555'
                order by created_at limit 1);

select is(
  (select count(*) from public.games
    where user_id = '55555555-5555-5555-5555-555555555555'),
  7::bigint,
  'deleting a seeded game leaves 7 — delete does not re-seed'
);

-- Fire-once: auth.users UPDATE does not fire the INSERT-only trigger ------

update auth.users set email = 'seed-trigger-renamed@test.local'
  where id = '55555555-5555-5555-5555-555555555555';

select is(
  (select count(*) from public.games
    where user_id = '55555555-5555-5555-5555-555555555555'),
  7::bigint,
  'updating the auth.users row adds no games (INSERT-only fire-once)'
);

select * from finish();

rollback;
