package org.rurbaniak.smartchessboard.domain.chess

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttacksTest {
    @Test
    fun whitePawnAttacksOnlyItsForwardDiagonals() {
        // The asymmetry under test: e5 is the pawn's *move* square, never an attacked square.
        val position = positionOf(squareOf(4, 3) to white(PieceType.PAWN))
        assertTrue(isSquareAttacked(position, squareOf(3, 4), Color.WHITE))
        assertTrue(isSquareAttacked(position, squareOf(5, 4), Color.WHITE))
        assertFalse(isSquareAttacked(position, squareOf(4, 4), Color.WHITE))
        assertFalse(isSquareAttacked(position, squareOf(3, 2), Color.WHITE))
        assertFalse(isSquareAttacked(position, squareOf(5, 2), Color.WHITE))
    }

    @Test
    fun blackPawnAttacksTowardRankZero() {
        val position = positionOf(squareOf(4, 4) to black(PieceType.PAWN))
        assertTrue(isSquareAttacked(position, squareOf(3, 3), Color.BLACK))
        assertTrue(isSquareAttacked(position, squareOf(5, 3), Color.BLACK))
        assertFalse(isSquareAttacked(position, squareOf(4, 3), Color.BLACK))
        assertFalse(isSquareAttacked(position, squareOf(3, 5), Color.BLACK))
    }

    @Test
    fun knightAttacksItsJumpSquaresNotAdjacentOnes() {
        val position = positionOf(squareOf(4, 3) to white(PieceType.KNIGHT))
        assertTrue(isSquareAttacked(position, squareOf(3, 5), Color.WHITE))
        assertTrue(isSquareAttacked(position, squareOf(6, 2), Color.WHITE))
        assertFalse(isSquareAttacked(position, squareOf(4, 4), Color.WHITE))
        assertFalse(isSquareAttacked(position, squareOf(5, 4), Color.WHITE))
    }

    @Test
    fun rookAttackStopsAtTheFirstOccupiedSquare() {
        val e1 = squareOf(4, 0)
        val e4 = squareOf(4, 3)
        val position = positionOf(e1 to white(PieceType.ROOK), e4 to black(PieceType.PAWN))
        assertTrue(isSquareAttacked(position, squareOf(4, 1), Color.WHITE))
        // The blocker's own square is attacked; squares beyond it are not.
        assertTrue(isSquareAttacked(position, e4, Color.WHITE))
        assertFalse(isSquareAttacked(position, squareOf(4, 4), Color.WHITE))
    }

    @Test
    fun bishopAttacksDiagonalsOnlyQueenAttacksBoth() {
        val e4 = squareOf(4, 3)
        val bishop = positionOf(e4 to white(PieceType.BISHOP))
        assertTrue(isSquareAttacked(bishop, squareOf(7, 6), Color.WHITE))
        assertFalse(isSquareAttacked(bishop, squareOf(4, 6), Color.WHITE))
        val queen = positionOf(e4 to white(PieceType.QUEEN))
        assertTrue(isSquareAttacked(queen, squareOf(7, 6), Color.WHITE))
        assertTrue(isSquareAttacked(queen, squareOf(4, 6), Color.WHITE))
    }

    @Test
    fun kingAttacksAdjacentSquaresOnly() {
        val position = positionOf(squareOf(4, 3) to white(PieceType.KING))
        assertTrue(isSquareAttacked(position, squareOf(4, 4), Color.WHITE))
        assertTrue(isSquareAttacked(position, squareOf(3, 2), Color.WHITE))
        assertFalse(isSquareAttacked(position, squareOf(4, 5), Color.WHITE))
    }

    @Test
    fun attackQueriesAreColorSpecific() {
        val position = positionOf(squareOf(4, 3) to white(PieceType.ROOK))
        assertTrue(isSquareAttacked(position, squareOf(4, 0), Color.WHITE))
        assertFalse(isSquareAttacked(position, squareOf(4, 0), Color.BLACK))
    }

    @Test
    fun isInCheckSeesAnOpenFileRookAndRespectsBlockers() {
        val e1 = squareOf(4, 0)
        val e8 = squareOf(4, 7)
        val open = positionOf(e1 to white(PieceType.KING), e8 to black(PieceType.ROOK))
        assertTrue(isInCheck(open, Color.WHITE))
        assertFalse(
            isInCheck(
                open.copy(
                    board =
                        open.board.toMutableList().also {
                            it[squareOf(4, 1)] =
                                white(PieceType.PAWN)
                        },
                ),
                Color.WHITE,
            ),
        )
    }

    @Test
    fun isInCheckIsPerColor() {
        val position =
            positionOf(
                squareOf(4, 0) to white(PieceType.KING),
                squareOf(4, 7) to black(PieceType.KING),
                squareOf(0, 7) to white(PieceType.ROOK),
            )
        assertTrue(isInCheck(position, Color.BLACK))
        assertFalse(isInCheck(position, Color.WHITE))
    }

    @Test
    fun isInCheckRequiresTheKingOnTheBoard() {
        assertFailsWith<IllegalArgumentException> { isInCheck(positionOf(), Color.WHITE) }
    }
}
