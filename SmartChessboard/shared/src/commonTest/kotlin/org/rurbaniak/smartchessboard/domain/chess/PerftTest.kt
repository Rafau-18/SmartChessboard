package org.rurbaniak.smartchessboard.domain.chess

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Reference node counts from the Chess Programming Wiki, "Perft Results"
// (https://www.chessprogramming.org/Perft_Results).

private const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

/** Kiwipete deliberately stresses castling, en passant, and pins together. */
private const val KIWIPETE_FEN = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -"

private val START_PERFT = listOf(20L, 400L, 8_902L, 197_281L)
private val KIWIPETE_PERFT = listOf(48L, 2_039L, 97_862L)

class PerftTest {
    // --- fen helper sanity, so the reference positions cannot silently lie ---

    @Test
    fun fenOfTheStartPositionEqualsPositionStart() {
        assertEquals(Position.start(), fen(START_FEN))
    }

    @Test
    fun fenRoundTripsSideCastlingAndEnPassantAfterOneE4() {
        val afterE4 = applyMove(Position.start(), Move(squareOf(4, 1), squareOf(4, 3)))
        assertEquals(afterE4, fen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"))
    }

    @Test
    fun fenSpotChecksOnKiwipete() {
        val position = fen(KIWIPETE_FEN)
        assertEquals(Color.WHITE, position.sideToMove)
        assertEquals(CastlingRights.ALL, position.castlingRights)
        assertNull(position.enPassantTarget)
        assertEquals(white(PieceType.KING), position.pieceAt(squareOf(4, 0)))
        assertEquals(white(PieceType.KNIGHT), position.pieceAt(squareOf(4, 4)))
        assertEquals(black(PieceType.QUEEN), position.pieceAt(squareOf(4, 6)))
        assertEquals(black(PieceType.ROOK), position.pieceAt(squareOf(0, 7)))
    }

    // --- perft against published reference counts ---

    @Test
    fun startPositionPerftMatchesPublishedCounts() {
        for (depth in 1..startPositionPerftDepth) {
            assertEquals(START_PERFT[depth - 1], perft(Position.start(), depth), "start position perft($depth)")
        }
    }

    @Test
    fun kiwipetePerftMatchesPublishedCounts() {
        val kiwipete = fen(KIWIPETE_FEN)
        for (depth in 1..kiwipetePerftDepth) {
            assertEquals(KIWIPETE_PERFT[depth - 1], perft(kiwipete, depth), "Kiwipete perft($depth)")
        }
    }

    /** Opt-in deep run (plan Phase 5 §2): never in the default suite; remove @Ignore to run manually. */
    @Ignore
    @Test
    fun startPositionPerft5ManualOptIn() {
        assertEquals(4_865_609L, perft(Position.start(), 5))
    }
}
