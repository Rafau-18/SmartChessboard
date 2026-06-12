-- One-off cloud seed for the replay slice (S-02) — run manually in the hosted
-- project's SQL editor. NOT applied by migrations or `supabase db reset`.
--
-- Procedure:
--   1. Sign in once in the app on any device, so your account exists in the
--      hosted project.
--   2. In the Supabase dashboard open Auth → Users and copy your user's UUID.
--   3. Replace EVERY occurrence of the :user_id placeholder below with that
--      UUID (keep the surrounding quotes: ':user_id' → 'your-uuid-here').
--   4. Run the script in the SQL editor (Database → SQL).
--
-- Unlike supabase/seed.sql this script must NOT touch auth.users — real users
-- already exist in the cloud project; it only inserts games rows. The PGNs are
-- the same finished fixture games as seed.sql (byte-identical to
-- PgnFixtures.kt), so the seeded games are provably replayable.
--
-- Idempotence: this script is NOT idempotent — running it twice inserts the
-- games twice (ids are generated). That is acceptable for a one-off; to redo,
-- delete first and rerun:
--   delete from public.games where user_id = ':user_id'
--     and white_label in ('Paul Morphy', 'Adolf Anderssen');

insert into public.games
  (user_id, mode, status, result, white_label, black_label, pgn)
values
  -- The Opera Game — Morphy vs Duke Karl / Count Isouard, Paris 1858
  (':user_id', 'digital', 'finished', 'white',
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

  -- The Immortal Game — Anderssen vs Kieseritzky, London 1851
  (':user_id', 'digital', 'finished', 'white',
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
21. Nxg7+ Kd8 22. Qf6+ Nxf6 23. Be7# 1-0$pgn$);
