package org.rurbaniak.smartchessboard.domain.chess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PositionTest {
    private val start = Position.start()

    @Test
    fun startPositionPlacesWhiteBackRankOnRank0() {
        val expected =
            listOf(
                PieceType.ROOK,
                PieceType.KNIGHT,
                PieceType.BISHOP,
                PieceType.QUEEN,
                PieceType.KING,
                PieceType.BISHOP,
                PieceType.KNIGHT,
                PieceType.ROOK,
            )
        for (file in 0..7) {
            assertEquals(Piece(Color.WHITE, expected[file]), start.pieceAt(squareOf(file, 0)))
            assertEquals(Piece(Color.BLACK, expected[file]), start.pieceAt(squareOf(file, 7)))
        }
    }

    @Test
    fun startPositionPlacesPawnsOnRanks1And6() {
        for (file in 0..7) {
            assertEquals(Piece(Color.WHITE, PieceType.PAWN), start.pieceAt(squareOf(file, 1)))
            assertEquals(Piece(Color.BLACK, PieceType.PAWN), start.pieceAt(squareOf(file, 6)))
        }
    }

    @Test
    fun startPositionMiddleRanksAreEmpty() {
        for (rank in 2..5) {
            for (file in 0..7) {
                assertNull(start.pieceAt(squareOf(file, rank)))
            }
        }
    }

    @Test
    fun startPositionKingsAndQueensAreOnTheirContractSquares() {
        // e1 = 4, d1 = 3, e8 = 60, d8 = 59 — spot-checks pinning the a1 = 0 convention.
        assertEquals(Piece(Color.WHITE, PieceType.KING), start.pieceAt(4))
        assertEquals(Piece(Color.WHITE, PieceType.QUEEN), start.pieceAt(3))
        assertEquals(Piece(Color.BLACK, PieceType.KING), start.pieceAt(60))
        assertEquals(Piece(Color.BLACK, PieceType.QUEEN), start.pieceAt(59))
    }

    @Test
    fun startPositionWhiteToMoveWithFullRightsAndFreshCounters() {
        assertEquals(Color.WHITE, start.sideToMove)
        assertEquals(CastlingRights.ALL, start.castlingRights)
        assertNull(start.enPassantTarget)
        assertEquals(0, start.halfmoveClock)
        assertEquals(1, start.fullmoveNumber)
    }

    @Test
    fun positionRejectsWrongBoardSize() {
        assertFailsWith<IllegalArgumentException> {
            start.copy(board = start.board.dropLast(1))
        }
    }

    @Test
    fun positionRejectsInvalidEnPassantTarget() {
        assertFailsWith<IllegalArgumentException> {
            start.copy(enPassantTarget = 64)
        }
    }

    @Test
    fun colorOppositeFlipsBothWays() {
        assertEquals(Color.BLACK, Color.WHITE.opposite)
        assertEquals(Color.WHITE, Color.BLACK.opposite)
    }

    @Test
    fun promotionTargetsAreTheFourNonPawnNonKingTypes() {
        assertEquals(
            setOf(PieceType.KNIGHT, PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN),
            PROMOTION_TARGETS,
        )
    }
}
