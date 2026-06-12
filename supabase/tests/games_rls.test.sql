-- pgTAP suite for the games RLS privacy boundary (S-01).
-- Proves contract-surfaces.md §2.4: a user reads/writes only their own rows,
-- anon reads nothing. Structural checks cover §2.4 policies, §2.5 index, §2.6 trigger.

begin;

create extension if not exists pgtap with schema extensions;
set local search_path = public, extensions;

select plan(22);

-- Structural ------------------------------------------------------------

select ok(
  (select relrowsecurity from pg_class where oid = 'public.games'::regclass),
  'RLS is enabled on public.games'
);

select policies_are(
  'public', 'games',
  array['games_select_own', 'games_insert_own', 'games_update_own', 'games_delete_own'],
  'games has exactly the four per-CRUD owner policies'
);

select has_index('public', 'games', 'games_user_created_idx',
  'chronological own-games index exists');

select policy_roles_are('public', 'games', 'games_select_own', array['authenticated'],
  'games_select_own is scoped to authenticated');
select policy_roles_are('public', 'games', 'games_insert_own', array['authenticated'],
  'games_insert_own is scoped to authenticated');
select policy_roles_are('public', 'games', 'games_update_own', array['authenticated'],
  'games_update_own is scoped to authenticated');
select policy_roles_are('public', 'games', 'games_delete_own', array['authenticated'],
  'games_delete_own is scoped to authenticated');

select has_trigger('public', 'games', 'games_set_updated_at',
  'updated_at auto-touch trigger exists');

select col_default_is('public', 'games', 'user_id', 'auth.uid()',
  'user_id defaults to auth.uid() (contract §2.2, amended S-04)');

-- Seed: two users, one game each (timestamps in the past so the trigger
-- test can observe updated_at moving to now()). Runs as postgres — table
-- owner bypasses RLS for seeding.

insert into auth.users (instance_id, id, aud, role, email)
values
  ('00000000-0000-0000-0000-000000000000', '11111111-1111-1111-1111-111111111111',
   'authenticated', 'authenticated', 'player-a@test.local'),
  ('00000000-0000-0000-0000-000000000000', '22222222-2222-2222-2222-222222222222',
   'authenticated', 'authenticated', 'player-b@test.local');

insert into public.games (id, user_id, created_at, updated_at, mode, status, white_label, black_label)
values
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111',
   now() - interval '1 day', now() - interval '1 day', 'digital', 'in_progress', 'Alice', 'Anna'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '22222222-2222-2222-2222-222222222222',
   now() - interval '1 day', now() - interval '1 day', 'digital', 'in_progress', 'Bella', 'Boris');

-- Behavioral: impersonate user A ----------------------------------------

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub": "11111111-1111-1111-1111-111111111111", "role": "authenticated"}',
  true
);

select results_eq(
  'select id from public.games',
  array['aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid],
  'A sees only their own game'
);

select lives_ok(
  $$insert into public.games (user_id, mode, status)
    values ('11111111-1111-1111-1111-111111111111', 'digital', 'in_progress')$$,
  'A can insert a game with their own user_id'
);

select throws_ok(
  $$insert into public.games (user_id, mode, status)
    values ('22222222-2222-2222-2222-222222222222', 'digital', 'in_progress')$$,
  '42501', null,
  'A cannot insert a game with B''s user_id'
);

select throws_ok(
  $$update public.games set user_id = '22222222-2222-2222-2222-222222222222'
    where id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'$$,
  '42501', null,
  'A cannot reassign their own game to B (implicit with check)'
);

-- S-04 write path: INSERT without user_id (column default) and the pgn auto-save UPDATE.

select lives_ok(
  $$insert into public.games (id, mode, status)
    values ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'digital', 'in_progress')$$,
  'A can insert a game without passing user_id (S-04 column default)'
);

select is(
  (select user_id from public.games where id = 'cccccccc-cccc-cccc-cccc-cccccccccccc'),
  '11111111-1111-1111-1111-111111111111'::uuid,
  'defaulted user_id is the caller''s auth.uid()'
);

update public.games set pgn = '1. e4 *'
  where id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';

select is(
  (select pgn from public.games where id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),
  '1. e4 *',
  'owner can update pgn (auto-save path)'
);

-- RLS silently scopes these to 0 rows; verified after role reset below.
update public.games set white_label = 'hacked'
  where id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
update public.games set pgn = 'hacked'
  where id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
delete from public.games
  where id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';

-- Behavioral: anon -------------------------------------------------------

set local role anon;
select set_config('request.jwt.claims', '', true);

select is(
  (select count(*) from public.games),
  0::bigint,
  'anon sees no games'
);

select throws_ok(
  $$insert into public.games (user_id, mode, status)
    values ('11111111-1111-1111-1111-111111111111', 'digital', 'in_progress')$$,
  '42501', null,
  'anon cannot insert games'
);

-- Verify A''s cross-user mutations did not land -------------------------

reset role;

select is(
  (select white_label from public.games where id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'),
  'Bella',
  'A''s update did not touch B''s row'
);

select is(
  (select count(*) from public.games where id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'),
  1::bigint,
  'A''s delete did not remove B''s row'
);

select is(
  (select pgn from public.games where id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'),
  '',
  'A''s pgn update did not touch B''s row'
);

-- Trigger behavior: updated_at moves to now() on update ------------------

update public.games set black_label = 'Bobby'
  where id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';

select is(
  (select updated_at from public.games where id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'),
  now(),
  'games_set_updated_at touches updated_at on update'
);

select * from finish();

rollback;
