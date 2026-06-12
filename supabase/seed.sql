-- Local development seeds, applied by `supabase db reset` ([db.seed] in config.toml).
-- Replay slice (S-02): test users + games whose PGN movetext is byte-identical to the
-- Phase 1 parser fixtures (SmartChessboard …/domain/chess/pgn/PgnFixtures.kt) — fixture
-- parity is what makes these seeds provably replayable. Two users so the RLS scoping
-- stays visible in dev (each signed-in test user sees only their own rows).
--
-- User UUIDs deliberately differ from tests/games_rls.test.sql (1111…/2222…) — that
-- suite inserts its own users and would hit a duplicate key if the seeds reused them.
--
-- Runs as postgres (table owner) — RLS is bypassed for seeding, FK order still applies:
-- auth.users first, then games.

insert into auth.users (instance_id, id, aud, role, email)
values
  ('00000000-0000-0000-0000-000000000000', '33333333-3333-3333-3333-333333333333',
   'authenticated', 'authenticated', 'seed-alice@test.local'),
  ('00000000-0000-0000-0000-000000000000', '44444444-4444-4444-4444-444444444444',
   'authenticated', 'authenticated', 'seed-bob@test.local');

-- Alice: two famous finished games (fixture parity), one in-progress game with partial
-- movetext (contract §5.5: result NULL, PGN [Result "*"]), one empty-PGN game (a game
-- row exists the moment it is created — contract §2.2 pgn default '').

insert into public.games
  (id, user_id, created_at, updated_at, mode, status, result, white_label, black_label, pgn)
values
  -- The Opera Game — Morphy vs Duke Karl / Count Isouard, Paris 1858 (PgnFixtures.OPERA_GAME)
  ('aaaaaaaa-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333',
   now() - interval '4 days', now() - interval '4 days', 'digital', 'finished', 'white',
   'Paul Morphy', 'Duke Karl / Count Isouard',
$pgn$[Event "Paris Opera"]
[Site "Paris FRA"]
[Date "1858.11.02"]
[White "Paul Morphy"]
[Black "Duke Karl / Count Isouard"]
[Result "1-0"]

1. e4 e5 2. Nf3 d6 3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 7. Qb3 Qe7
8. Nc3 c6 9. Bg5 b5 10. Nxb5 cxb5 11. Bxb5+ Nbd7 12. O-O-O Rd8 13. Rxd7 Rxd7
14. Rd1 Qe6 15. Bxd7+ Nxd7 16. Qb8+ Nxb8 17. Rd8# 1-0$pgn$),

  -- The Immortal Game — Anderssen vs Kieseritzky, London 1851 (PgnFixtures.IMMORTAL_GAME)
  ('aaaaaaaa-0000-0000-0000-000000000002', '33333333-3333-3333-3333-333333333333',
   now() - interval '3 days', now() - interval '3 days', 'digital', 'finished', 'white',
   'Adolf Anderssen', 'Lionel Kieseritzky',
$pgn$[Event "London casual"]
[Site "London ENG"]
[Date "1851.06.21"]
[White "Adolf Anderssen"]
[Black "Lionel Kieseritzky"]
[Result "1-0"]

1. e4 e5 2. f4 exf4 3. Bc4 Qh4+ 4. Kf1 b5 5. Bxb5 Nf6 6. Nf3 Qh6 7. d3 Nh5
8. Nh4 Qg5 9. Nf5 c6 10. g4 Nf6 11. Rg1 cxb5 12. h4 Qg6 13. h5 Qg5 14. Qf3 Ng8
15. Bxf4 Qf6 16. Nc3 Bc5 17. Nd5 Qxb2 18. Bd6 Bxg1 19. e5 Qxa1+ 20. Ke2 Na6
21. Nxg7+ Kd8 22. Qf6+ Nxf6 23. Be7# 1-0$pgn$),

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
   'Alice', 'Dave', ''),

  -- Bob: one finished game (fixture parity) — proves RLS scoping in dev: Alice never sees it.
  ('bbbbbbbb-0000-0000-0000-000000000001', '44444444-4444-4444-4444-444444444444',
   now() - interval '2 days', now() - interval '2 days', 'digital', 'finished', 'white',
   'Paul Morphy', 'Duke Karl / Count Isouard',
$pgn$[Event "Paris Opera"]
[Site "Paris FRA"]
[Date "1858.11.02"]
[White "Paul Morphy"]
[Black "Duke Karl / Count Isouard"]
[Result "1-0"]

1. e4 e5 2. Nf3 d6 3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 7. Qb3 Qe7
8. Nc3 c6 9. Bg5 b5 10. Nxb5 cxb5 11. Bxb5+ Nbd7 12. O-O-O Rd8 13. Rxd7 Rxd7
14. Rd1 Qe6 15. Bxd7+ Nxd7 16. Qb8+ Nxb8 17. Rd8# 1-0$pgn$);
