package org.rurbaniak.smartchessboard.presentation.board

import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.chess.Piece
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.squareOf
import smartchessboard.shared.generated.resources.Res
import smartchessboard.shared.generated.resources.piece_bp
import smartchessboard.shared.generated.resources.piece_wk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChessBoardGeometryTest {
    @Test
    fun `corner cells map to the right squares with white at the bottom`() {
        assertEquals(squareOf(0, 7), squareAt(column = 0, rowFromTop = 0)) // a8 top-left
        assertEquals(squareOf(7, 7), squareAt(column = 7, rowFromTop = 0)) // h8 top-right
        assertEquals(squareOf(0, 0), squareAt(column = 0, rowFromTop = 7)) // a1 bottom-left
        assertEquals(squareOf(7, 0), squareAt(column = 7, rowFromTop = 7)) // h1 bottom-right
    }

    @Test
    fun `every grid cell maps to a distinct valid square`() {
        val squares = mutableSetOf<Int>()
        for (rowFromTop in 0..7) {
            for (column in 0..7) {
                squares += squareAt(column, rowFromTop)
            }
        }
        assertEquals((0..63).toSet(), squares)
    }

    @Test
    fun `square coloring follows the a1-dark convention`() {
        assertTrue(isDarkSquare(squareOf(0, 0))) // a1
        assertFalse(isDarkSquare(squareOf(7, 0))) // h1
        assertFalse(isDarkSquare(squareOf(0, 7))) // a8
        assertTrue(isDarkSquare(squareOf(7, 7))) // h8
        assertFalse(isDarkSquare(squareOf(4, 3))) // e4 is a light square
        assertTrue(isDarkSquare(squareOf(3, 3))) // d4 is a dark square
    }

    @Test
    fun `all twelve piece kinds map to distinct drawables`() {
        val drawables =
            Color.entries.flatMap { color ->
                PieceType.entries.map { type -> pieceDrawable(Piece(color, type)) }
            }
        assertEquals(12, drawables.toSet().size)
    }

    @Test
    fun `piece drawable lookup picks the matching asset`() {
        assertEquals(Res.drawable.piece_wk, pieceDrawable(Piece(Color.WHITE, PieceType.KING)))
        assertEquals(Res.drawable.piece_bp, pieceDrawable(Piece(Color.BLACK, PieceType.PAWN)))
    }
}
