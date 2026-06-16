package org.rurbaniak.smartchessboard.domain.chess.pgn

import org.rurbaniak.smartchessboard.domain.chess.GameStatus
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.applyMove
import org.rurbaniak.smartchessboard.domain.chess.legalMoves
import org.rurbaniak.smartchessboard.domain.chess.status
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PgnWriterTest {
    private val meta =
        PgnMeta(
            event = "Smart Chessboard",
            date = "2026.06.12",
            white = "Alice",
            black = "Bob",
            result = "*",
            mode = "digital",
        )

    // --- Document shape (§5.2 / §5.3) ---

    @Test
    fun emitsTheSixTagHeaderAndNumberedMovetext() {
        val expected =
            """
            [Event "Smart Chessboard"]
            [Date "2026.06.12"]
            [White "Alice"]
            [Black "Bob"]
            [Result "*"]
            [Mode "digital"]

            1. e4 e5 2. Nf3 *
            """.trimIndent() + "\n"
        assertEquals(expected, writePgn(meta, listOf("e4", "e5", "Nf3")))
    }

    @Test
    fun emptyMovetextIsJustTheTerminationMarker() {
        val document = writePgn(meta, emptyList())
        assertTrue(document.endsWith("\n\n*\n"), "expected a bare termination marker, got:\n$document")
        val game = parsePgn(document)
        assertNull(game.truncation)
        assertTrue(game.sanMoves.isEmpty())
        assertEquals(listOf(Position.start()), game.positions)
        assertEquals("*", game.headers.result)
    }

    @Test
    fun terminationMarkerEqualsTheResult() {
        val document = writePgn(meta.copy(result = "0-1"), listOf("f3", "e5", "g4", "Qh4#"))
        assertTrue(document.trimEnd().endsWith("1. f3 e5 2. g4 Qh4# 0-1"), "got:\n$document")
    }

    @Test
    fun tagValuesEscapeQuotesAndBackslashes() {
        val tricky = meta.copy(white = "Al \"Rook\" O\\Hara")
        val document = writePgn(tricky, emptyList())
        assertTrue(document.contains("[White \"Al \\\"Rook\\\" O\\\\Hara\"]"), "got:\n$document")
        assertEquals("Al \"Rook\" O\\Hara", parsePgn(document).headers.white)
    }

    // --- Round-trip layer (a): corpus parse → write → parse identity ---

    @Test
    fun corpusGamesRoundTripThroughWriteAndParse() {
        for (pgn in listOf(PgnFixtures.OPERA_GAME, PgnFixtures.IMMORTAL_GAME)) {
            val original = parsePgn(pgn)
            assertNull(original.truncation)
            val rewrittenMeta =
                PgnMeta(
                    event = original.headers.tags.getValue("Event"),
                    date = original.headers.date!!,
                    white = original.headers.white!!,
                    black = original.headers.black!!,
                    result = original.headers.result!!,
                    mode = "digital",
                )
            val rewritten = parsePgn(writePgn(rewrittenMeta, original.sanMoves))
            assertNull(rewritten.truncation)
            assertEquals(original.sanMoves, rewritten.sanMoves)
            assertEquals(original.positions, rewritten.positions)
            assertEquals(original.headers.result, rewritten.headers.result)
        }
    }

    // --- Round-trip layer (b): seeded-random legal playouts write → parse identity ---

    @Test
    fun seededRandomPlayoutsRoundTripThroughWriteAndParse() {
        for (seed in 0..4) {
            val random = Random(seed)
            val positions = mutableListOf(Position.start())
            val sans = mutableListOf<String>()
            // 37 + seed target plies vary the final parity across seeds (odd ply counts included).
            while (sans.size < 37 + seed) {
                val moves = legalMoves(positions.last())
                if (moves.isEmpty()) break
                val played = moves[random.nextInt(moves.size)]
                sans += sanForMove(positions.last(), played)
                positions += applyMove(positions.last(), played)
            }
            val game = parsePgn(writePgn(meta, sans))
            assertNull(game.truncation, "seed $seed truncated: ${game.truncation}")
            assertEquals(sans, game.sanMoves, "seed $seed")
            assertEquals(positions, game.positions, "seed $seed")
        }
    }

    // --- Finished-result serialisation (S-05): a finished PGN round-trips with its result token ---

    @Test
    fun finishedMateGamesRoundTripPreservingResultAndMateSuffix() {
        // Each is a real mate: the final SAN carries '#', the engine classifies it Checkmate, and
        // the result token survives write → parse together with the moves and positions.
        for ((document, token) in listOf(SCHOLARS_MATE to "1-0", FOOLS_MATE to "0-1")) {
            val original = parsePgn(document)
            assertNull(original.truncation)
            assertEquals(token, original.headers.result)
            assertEquals(GameStatus.Checkmate, status(original.positions.last()))
            assertTrue(original.sanMoves.last().endsWith("#"), "expected '#' on ${original.sanMoves.last()}")

            val rewritten = parsePgn(writePgn(meta.copy(result = token), original.sanMoves))
            assertNull(rewritten.truncation)
            assertEquals(token, rewritten.headers.result)
            assertEquals(original.sanMoves, rewritten.sanMoves)
            assertEquals(original.positions, rewritten.positions)
        }
    }

    @Test
    fun finishedStalemateGameRoundTripsAsADrawWithNoCheckSuffix() {
        val original = parsePgn(STALEMATE_GAME)
        assertNull(original.truncation)
        assertEquals("1/2-1/2", original.headers.result)
        assertEquals(GameStatus.Stalemate, status(original.positions.last()))
        val finalSan = original.sanMoves.last()
        assertFalse(finalSan.endsWith("+") || finalSan.endsWith("#"), "stalemate move must carry no suffix: $finalSan")

        val rewritten = parsePgn(writePgn(meta.copy(result = "1/2-1/2"), original.sanMoves))
        assertNull(rewritten.truncation)
        assertEquals("1/2-1/2", rewritten.headers.result)
        assertEquals(original.sanMoves, rewritten.sanMoves)
        assertEquals(original.positions, rewritten.positions)
    }

    @Test
    fun emptyMovetextManualDrawSerialisesABareDrawTerminator() {
        // A manual draw recorded with no moves played (e.g. an immediate agreed draw / resignation).
        val document = writePgn(meta.copy(result = "1/2-1/2"), emptyList())
        assertTrue(document.contains("[Result \"1/2-1/2\"]"), "got:\n$document")
        assertTrue(document.endsWith("\n\n1/2-1/2\n"), "expected a bare draw terminator, got:\n$document")
        val game = parsePgn(document)
        assertNull(game.truncation)
        assertTrue(game.sanMoves.isEmpty())
        assertEquals(listOf(Position.start()), game.positions)
        assertEquals("1/2-1/2", game.headers.result)
    }

    private companion object {
        // Scholar's mate — White delivers mate, [Result "1-0"], final SAN "Qxf7#".
        val SCHOLARS_MATE =
            """
            [Event "Smart Chessboard"]
            [Date "2026.06.13"]
            [White "Alice"]
            [Black "Bob"]
            [Result "1-0"]
            [Mode "digital"]

            1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0
            """.trimIndent()

        // Fool's mate — Black delivers mate, [Result "0-1"], final SAN "Qh4#".
        val FOOLS_MATE =
            """
            [Event "Smart Chessboard"]
            [Date "2026.06.13"]
            [White "Alice"]
            [Black "Bob"]
            [Result "0-1"]
            [Mode "digital"]

            1. f3 e5 2. g4 Qh4# 0-1
            """.trimIndent()

        // Fastest stalemate (19 plies) — White stalemates Black, [Result "1/2-1/2"], final SAN "Qe6"
        // (no suffix). The f6 pawn is pinned along the 6th rank by Qe6 → Kg6, so Black has no move.
        val STALEMATE_GAME =
            """
            [Event "Smart Chessboard"]
            [Date "2026.06.13"]
            [White "Alice"]
            [Black "Bob"]
            [Result "1/2-1/2"]
            [Mode "digital"]

            1. e3 a5 2. Qh5 Ra6 3. Qxa5 h5 4. Qxc7 Rah6 5. h4 f6 6. Qxd7+ Kf7
            7. Qxb7 Qd3 8. Qxb8 Qh7 9. Qxc8 Kg6 10. Qe6 1/2-1/2
            """.trimIndent()
    }
}
