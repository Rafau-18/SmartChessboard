package org.rurbaniak.smartchessboard.domain.chess.pgn

import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.applyMove
import org.rurbaniak.smartchessboard.domain.chess.legalMoves
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
