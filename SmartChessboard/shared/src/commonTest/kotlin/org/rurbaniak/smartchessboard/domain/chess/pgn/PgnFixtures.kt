package org.rurbaniak.smartchessboard.domain.chess.pgn

/**
 * Curated PGN corpus shared between the parser tests and — by content parity — `supabase/seed.sql`
 * (Phase 2 of the replay slice). Keeping the seeds byte-identical to these fixtures is what makes
 * the seeded games provably replayable.
 */
internal object PgnFixtures {
    /**
     * Morphy vs Duke Karl / Count Isouard, Paris Opera 1858 — 33 plies. Exercises queenside
     * castling, the famous `Nbd7` file disambiguation, check suffixes, and mate.
     */
    val OPERA_GAME =
        """
        [Event "Paris Opera"]
        [Site "Paris FRA"]
        [Date "1858.11.02"]
        [White "Paul Morphy"]
        [Black "Duke Karl / Count Isouard"]
        [Result "1-0"]

        1. e4 e5 2. Nf3 d6 3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 7. Qb3 Qe7
        8. Nc3 c6 9. Bg5 b5 10. Nxb5 cxb5 11. Bxb5+ Nbd7 12. O-O-O Rd8 13. Rxd7 Rxd7
        14. Rd1 Qe6 15. Bxd7+ Nxd7 16. Qb8+ Nxb8 17. Rd8# 1-0
        """.trimIndent()

    /**
     * Anderssen vs Kieseritzky, London 1851 (the Immortal Game) — 45 plies. Long tactical game
     * with king moves, captures on every rank, and a bishop mate.
     */
    val IMMORTAL_GAME =
        """
        [Event "London casual"]
        [Site "London ENG"]
        [Date "1851.06.21"]
        [White "Adolf Anderssen"]
        [Black "Lionel Kieseritzky"]
        [Result "1-0"]

        1. e4 e5 2. f4 exf4 3. Bc4 Qh4+ 4. Kf1 b5 5. Bxb5 Nf6 6. Nf3 Qh6 7. d3 Nh5
        8. Nh4 Qg5 9. Nf5 c6 10. g4 Nf6 11. Rg1 cxb5 12. h4 Qg6 13. h5 Qg5 14. Qf3 Ng8
        15. Bxf4 Qf6 16. Nc3 Bc5 17. Nd5 Qxb2 18. Bd6 Bxg1 19. e5 Qxa1+ 20. Ke2 Na6
        21. Nxg7+ Kd8 22. Qf6+ Nxf6 23. Be7# 1-0
        """.trimIndent()

    /**
     * Anderssen vs Dufresne, Berlin 1852 (the Evergreen Game) — 47 plies. Evans Gambit crowned
     * by the queen sacrifice 21. Qxd7+ into a double-check mating sequence.
     */
    val EVERGREEN_GAME =
        """
        [Event "Berlin casual"]
        [Site "Berlin GER"]
        [Date "1852.??.??"]
        [White "Adolf Anderssen"]
        [Black "Jean Dufresne"]
        [Result "1-0"]

        1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. b4 Bxb4 5. c3 Ba5 6. d4 exd4 7. O-O d3
        8. Qb3 Qf6 9. e5 Qg6 10. Re1 Nge7 11. Ba3 b5 12. Qxb5 Rb8 13. Qa4 Bb6
        14. Nbd2 Bb7 15. Ne4 Qf5 16. Bxd3 Qh5 17. Nf6+ gxf6 18. exf6 Rg8 19. Rad1 Qxf3
        20. Rxe7+ Nxe7 21. Qxd7+ Kxd7 22. Bf5+ Ke8 23. Bd7+ Kf8 24. Bxe7# 1-0
        """.trimIndent()

    /**
     * Donald Byrne vs Bobby Fischer, New York 1956 (the Game of the Century) — 82 plies. The
     * 13-year-old Fischer's queen sacrifice and windmill of discovered checks, ending in a
     * pure mate.
     */
    val GAME_OF_THE_CENTURY =
        """
        [Event "Rosenwald Memorial"]
        [Site "New York, NY USA"]
        [Date "1956.10.17"]
        [White "Donald Byrne"]
        [Black "Bobby Fischer"]
        [Result "0-1"]

        1. Nf3 Nf6 2. c4 g6 3. Nc3 Bg7 4. d4 O-O 5. Bf4 d5 6. Qb3 dxc4 7. Qxc4 c6
        8. e4 Nbd7 9. Rd1 Nb6 10. Qc5 Bg4 11. Bg5 Na4 12. Qa3 Nxc3 13. bxc3 Nxe4
        14. Bxe7 Qb6 15. Bc4 Nxc3 16. Bc5 Rfe8+ 17. Kf1 Be6 18. Bxb6 Bxc4+ 19. Kg1 Ne2+
        20. Kf1 Nxd4+ 21. Kg1 Ne2+ 22. Kf1 Nc3+ 23. Kg1 axb6 24. Qb4 Ra4 25. Qxb6 Nxd1
        26. h3 Rxa2 27. Kh2 Nxf2 28. Re1 Rxe1 29. Qd8+ Bf8 30. Nxe1 Bd5 31. Nf3 Ne4
        32. Qb8 b5 33. h4 h5 34. Ne5 Kg7 35. Kg1 Bc5+ 36. Kf1 Ng3+ 37. Ke1 Bb4+
        38. Kd1 Bb3+ 39. Kc1 Ne2+ 40. Kb1 Nc3+ 41. Kc1 Rc2# 0-1
        """.trimIndent()

    /**
     * Kasparov vs Topalov, Wijk aan Zee 1999 (Kasparov's Immortal) — 87 plies. The rook
     * sacrifice 24. Rxd4 launches a king hunt from b8 all the way to e1; Black resigned
     * after 44. Qa7 (no mate on the board).
     */
    val KASPAROV_IMMORTAL =
        """
        [Event "Hoogovens Group A"]
        [Site "Wijk aan Zee NED"]
        [Date "1999.01.20"]
        [White "Garry Kasparov"]
        [Black "Veselin Topalov"]
        [Result "1-0"]

        1. e4 d6 2. d4 Nf6 3. Nc3 g6 4. Be3 Bg7 5. Qd2 c6 6. f3 b5 7. Nge2 Nbd7
        8. Bh6 Bxh6 9. Qxh6 Bb7 10. a3 e5 11. O-O-O Qe7 12. Kb1 a6 13. Nc1 O-O-O
        14. Nb3 exd4 15. Rxd4 c5 16. Rd1 Nb6 17. g3 Kb8 18. Na5 Ba8 19. Bh3 d5
        20. Qf4+ Ka7 21. Rhe1 d4 22. Nd5 Nbxd5 23. exd5 Qd6 24. Rxd4 cxd4 25. Re7+ Kb6
        26. Qxd4+ Kxa5 27. b4+ Ka4 28. Qc3 Qxd5 29. Ra7 Bb7 30. Rxb7 Qc4 31. Qxf6 Kxa3
        32. Qxa6+ Kxb4 33. c3+ Kxc3 34. Qa1+ Kd2 35. Qb2+ Kd1 36. Bf1 Rd2 37. Rd7 Rxd7
        38. Bxc4 bxc4 39. Qxh8 Rd3 40. Qa8 c3 41. Qa4+ Ke1 42. f4 f5 43. Kc1 Rd2
        44. Qa7 1-0
        """.trimIndent()

    /**
     * Judit Polgar vs Garry Kasparov, Moscow 2002 (Russia vs the Rest of the World, rapid) —
     * 84 plies. Berlin-endgame grind and Polgar's first win over Kasparov; resignation after
     * 42... Kc8. Heavy on rook-pair disambiguation (`Rexd6+`, `R2d5`, `R2h3+`, `Rcc7`).
     */
    val POLGAR_KASPAROV =
        """
        [Event "Russia - The Rest of the World"]
        [Site "Moscow RUS"]
        [Date "2002.09.09"]
        [White "Judit Polgar"]
        [Black "Garry Kasparov"]
        [Result "1-0"]

        1. e4 e5 2. Nf3 Nc6 3. Bb5 Nf6 4. O-O Nxe4 5. d4 Nd6 6. Bxc6 dxc6 7. dxe5 Nf5
        8. Qxd8+ Kxd8 9. Nc3 h6 10. Rd1+ Ke8 11. h3 Be7 12. Nce2 Nh4 13. Nxh4 Bxh4
        14. Be3 Bf5 15. Nd4 Bh7 16. g4 Be7 17. Kg2 h5 18. Nf5 Bf8 19. Kf3 Bg6 20. Rd2 hxg4+
        21. hxg4 Rh3+ 22. Kg2 Rh7 23. Kg3 f6 24. Bf4 Bxf5 25. gxf5 fxe5 26. Re1 Bd6
        27. Bxe5 Kd7 28. c4 c5 29. Bxd6 cxd6 30. Re6 Rah8 31. Rexd6+ Kc8 32. R2d5 Rh3+
        33. Kg2 Rh2+ 34. Kf3 R2h3+ 35. Ke4 b6 36. Rc6+ Kb8 37. Rd7 Rh2 38. Ke3 Rf8
        39. Rcc7 Rxf5 40. Rb7+ Kc8 41. Rdc7+ Kd8 42. Rxg7 Kc8 1-0
        """.trimIndent()

    /**
     * Deep Blue vs Kasparov, New York 1997, game 6 — 37 plies. The decisive machine win: the
     * Caro-Kann knight sacrifice 8. Nxe6 and resignation after 19. c4. Exercises rank
     * disambiguation (`N1f3`).
     */
    val DEEP_BLUE_KASPAROV =
        """
        [Event "IBM Man-Machine Match"]
        [Site "New York, NY USA"]
        [Date "1997.05.11"]
        [White "Deep Blue"]
        [Black "Garry Kasparov"]
        [Result "1-0"]

        1. e4 c6 2. d4 d5 3. Nc3 dxe4 4. Nxe4 Nd7 5. Ng5 Ngf6 6. Bd3 e6 7. N1f3 h6
        8. Nxe6 Qe7 9. O-O fxe6 10. Bg6+ Kd8 11. Bf4 b5 12. a4 Bb7 13. Re1 Nd5 14. Bg3 Kc8
        15. axb5 cxb5 16. Qd3 Bc6 17. Bf5 exf5 18. Rxe7 Bxe7 19. c4 1-0
        """.trimIndent()

    /**
     * Levitsky vs Marshall, Breslau 1912 (the "gold coins" game) — 46 plies. Ends on
     * Marshall's 23... Qg3, the queen left en prise three ways; White resigned facing it
     * (no mate on the board).
     */
    val GOLD_COINS_GAME =
        """
        [Event "18th DSB Congress"]
        [Site "Breslau GER"]
        [Date "1912.07.20"]
        [White "Stepan Levitsky"]
        [Black "Frank Marshall"]
        [Result "0-1"]

        1. d4 e6 2. e4 d5 3. Nc3 c5 4. Nf3 Nc6 5. exd5 exd5 6. Be2 Nf6 7. O-O Be7
        8. Bg5 O-O 9. dxc5 Be6 10. Nd4 Bxc5 11. Nxe6 fxe6 12. Bg4 Qd6 13. Bh3 Rae8
        14. Qd2 Bb4 15. Bxf6 Rxf6 16. Rad1 Qc5 17. Qe2 Bxc3 18. bxc3 Qxc3 19. Rxd5 Nd4
        20. Qh5 Ref8 21. Re5 Rh6 22. Qg5 Rxh3 23. Rc5 Qg3 0-1
        """.trimIndent()
}
