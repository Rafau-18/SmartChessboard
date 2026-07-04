-- Local development seeds, applied by `supabase db reset` ([db.seed] in config.toml).
-- Migrations run first, so the FR-022 onboarding trigger (on_auth_user_created_seed_games,
-- 20260704221841_seed_sample_games_on_signup.sql) fires on each auth.users insert below —
-- every seed user automatically gets the 8 finished fixture games (byte-identical to
-- SmartChessboard …/domain/chess/pgn/PgnFixtures.kt, guarded by SeedPgnParityTest). This
-- file therefore inserts NO finished games itself; it keeps only the edge-case rows the
-- trigger does not produce. Two users so the RLS scoping stays visible in dev (each
-- signed-in test user sees only their own rows).
--
-- User UUIDs deliberately differ from the pgTAP suites (1111…/2222… in games_rls,
-- 5555… in seed_on_signup) — those suites insert their own users and would hit a
-- duplicate key if the seeds reused them.
--
-- Runs as postgres (table owner) — RLS is bypassed for seeding, FK order still applies:
-- auth.users first, then games.

insert into auth.users (instance_id, id, aud, role, email)
values
  ('00000000-0000-0000-0000-000000000000', '33333333-3333-3333-3333-333333333333',
   'authenticated', 'authenticated', 'seed-alice@test.local'),
  ('00000000-0000-0000-0000-000000000000', '44444444-4444-4444-4444-444444444444',
   'authenticated', 'authenticated', 'seed-bob@test.local');

-- Alice: edge-case rows on top of her 8 auto-seeded games — one in-progress game with
-- partial movetext (contract §5.5: result NULL, PGN [Result "*"]), one empty-PGN game
-- (a game row exists the moment it is created — contract §2.2 pgn default '').
-- Bob keeps only his 8 auto-seeded games — proves RLS scoping in dev: Alice never sees them.

insert into public.games
  (id, user_id, created_at, updated_at, mode, status, result, white_label, black_label, pgn)
values
  -- In-progress game: movetext ends mid-pair (white's 5th, kingside castling exercised);
  -- headers per contract §5.2 ([Result "*"], custom [Mode] tag).
  ('aaaaaaaa-0000-0000-0000-000000000003', '33333333-3333-3333-3333-333333333333',
   now() - interval '2 days', now() - interval '2 days', 'digital', 'in_progress', null,
   'Alice', 'Carol',
$pgn$[Event "Smart Chessboard"]
[Date "2026.06.10"]
[White "Alice"]
[Black "Carol"]
[Result "*"]
[Mode "digital"]

1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O$pgn$),

  -- Empty-PGN game: just created, no moves yet.
  ('aaaaaaaa-0000-0000-0000-000000000004', '33333333-3333-3333-3333-333333333333',
   now() - interval '1 day', now() - interval '1 day', 'digital', 'in_progress', null,
   'Alice', 'Dave', '');
