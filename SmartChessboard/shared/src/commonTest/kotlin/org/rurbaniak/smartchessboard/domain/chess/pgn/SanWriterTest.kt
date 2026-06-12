package org.rurbaniak.smartchessboard.domain.chess.pgn

import org.rurbaniak.smartchessboard.domain.chess.Move
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.applyMove
import org.rurbaniak.smartchessboard.domain.chess.fen
import org.rurbaniak.smartchessboard.domain.chess.legalMoves
import org.rurbaniak.smartchessboard.domain.chess.squareOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SanWriterTest {
    private fun square(name: String): Int = squareOf(name[0] - 'a', name[1] - '1')

    private fun move(
        from: String,
        to: String,
        promoteTo: PieceType? = null,
    ): Move = Move(square(from), square(to), promoteTo)

    private fun positionAfter(pgn: String): Position {
        val game = parsePgn(pgn)
        assertNull(game.truncation, "setup PGN must parse completely, got ${game.truncation}")
        return game.positions.last()
    }

    // --- Plain moves and captures ---

    @Test
    fun pawnPushIsTheBareTargetSquare() {
        assertEquals("e4", sanForMove(Position.start(), move("e2", "e4")))
    }

    @Test
    fun pieceMoveCarriesItsLetter() {
        assertEquals("Nf3", sanForMove(Position.start(), move("g1", "f3")))
    }

    @Test
    fun pawnCaptureIsPrefixedWithTheFromFile() {
        val position = positionAfter("1. e4 d5")
        assertEquals("exd5", sanForMove(position, move("e4", "d5")))
    }

    @Test
    fun enPassantCaptureIsWrittenAsAPlainPawnCapture() {
        val position = positionAfter("1. e4 Nf6 2. e5 d5")
        assertEquals("exd6", sanForMove(position, move("e5", "d6")))
    }

    @Test
    fun pieceCaptureTakesX() {
        val position = positionAfter("1. e4 d5 2. exd5")
        assertEquals("Qxd5", sanForMove(position, move("d8", "d5")))
    }

    // --- Minimal disambiguation (file first, then rank, then both) ---

    @Test
    fun fileDisambiguationWhenTwoKnightsReachTheSquare() {
        val position = positionAfter("1. d4 d5 2. Nf3 Nf6")
        assertEquals("Nbd2", sanForMove(position, move("b1", "d2")))
        assertEquals("Nfd2", sanForMove(position, move("f3", "d2")))
    }

    @Test
    fun rankDisambiguationWhenRivalsShareTheFile() {
        val position = fen("7k/8/8/R7/8/8/8/R3K3 w - -")
        assertEquals("R1a3", sanForMove(position, move("a1", "a3")))
        assertEquals("R5a3", sanForMove(position, move("a5", "a3")))
    }

    @Test
    fun fullSquareDisambiguationWhenFileAndRankAreBothShared() {
        // Queens on e4, h4, and h1 all reach e1: e4 disambiguates by file, h1 by rank, h4 needs both.
        val position = fen("2k5/8/8/8/4Q2Q/8/8/1K5Q w - -")
        assertEquals("Qee1", sanForMove(position, move("e4", "e1")))
        assertEquals("Qh4e1", sanForMove(position, move("h4", "e1")))
        assertEquals("Q1e1", sanForMove(position, move("h1", "e1")))
    }

    @Test
    fun pinnedRivalNeedsNoDisambiguation() {
        // The d2 knight also reaches c3 geometrically but is pinned to the king by the d8 rook —
        // the legal set excludes it, so minimal SAN is the bare move.
        val position = fen("3r3k/8/8/8/8/8/3N4/1N1K4 w - -")
        assertEquals("Nc3", sanForMove(position, move("b1", "c3")))
    }

    // --- Castling ---

    @Test
    fun kingsideCastlingIsOO() {
        val position = positionAfter("1. e4 e5 2. Nf3 Nf6 3. Bc4 Bc5")
        assertEquals("O-O", sanForMove(position, move("e1", "g1")))
    }

    @Test
    fun queensideCastlingIsOOO() {
        val position = positionAfter("1. d4 d5 2. Nc3 Nc6 3. Bf4 Bf5 4. Qd2 Qd7")
        assertEquals("O-O-O", sanForMove(position, move("e1", "c1")))
    }

    @Test
    fun castlingWithCheckTakesTheSuffix() {
        // After O-O the f1 rook checks the f8 king.
        val position = fen("5k2/8/8/8/8/8/8/4K2R w K -")
        assertEquals("O-O+", sanForMove(position, move("e1", "g1")))
    }

    // --- Promotions ---

    @Test
    fun promotionAppendsTheChosenPiece() {
        val position = positionAfter("1. e4 d5 2. exd5 c6 3. dxc6 e5 4. cxb7 e4")
        assertEquals("bxa8=Q", sanForMove(position, move("b7", "a8", PieceType.QUEEN)))
        assertEquals("bxa8=N", sanForMove(position, move("b7", "a8", PieceType.KNIGHT)))
    }

    @Test
    fun promotionWithCheckTakesTheSuffix() {
        // The new b8 queen checks the b5 king down the freshly vacated b-file.
        val position = fen("8/1P6/8/1k6/8/8/8/7K w - -")
        assertEquals("b8=Q+", sanForMove(position, move("b7", "b8", PieceType.QUEEN)))
    }

    @Test
    fun promotionWithMateTakesTheMateSuffix() {
        // f8=Q checks along the eighth rank; the g6 king covers every flight square.
        val position = fen("7k/5P2/6K1/8/8/8/8/8 w - -")
        assertEquals("f8=Q#", sanForMove(position, move("f7", "f8", PieceType.QUEEN)))
    }

    // --- Check and mate suffixes ---

    @Test
    fun mateTakesTheHashSuffix() {
        val position = positionAfter("1. f3 e5 2. g4")
        assertEquals("Qh4#", sanForMove(position, move("d8", "h4")))
    }

    // --- Corpus regeneration: the writer reproduces the historical tokens exactly ---

    @Test
    fun corpusSanTokensAreReproducedExactly() {
        for (pgn in listOf(PgnFixtures.OPERA_GAME, PgnFixtures.IMMORTAL_GAME)) {
            val game = parsePgn(pgn)
            assertNull(game.truncation)
            for (ply in game.sanMoves.indices) {
                val before = game.positions[ply]
                val played = legalMoves(before).single { applyMove(before, it) == game.positions[ply + 1] }
                assertEquals(game.sanMoves[ply], sanForMove(before, played), "ply $ply")
            }
        }
    }

    // --- Illegal input contract ---

    @Test
    fun illegalMoveThrows() {
        assertFailsWith<IllegalArgumentException> {
            sanForMove(Position.start(), move("e2", "e5"))
        }
    }

    @Test
    fun promotionWithoutPieceChoiceThrows() {
        val position = positionAfter("1. e4 d5 2. exd5 c6 3. dxc6 e5 4. cxb7 e4")
        assertFailsWith<IllegalArgumentException> {
            sanForMove(position, move("b7", "a8"))
        }
    }
}
