package org.rurbaniak.smartchessboard.presentation.board

import org.rurbaniak.smartchessboard.domain.chess.CastlingRights
import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.chess.Move
import org.rurbaniak.smartchessboard.domain.chess.Piece
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.SQUARE_COUNT
import org.rurbaniak.smartchessboard.domain.chess.applyMove
import org.rurbaniak.smartchessboard.domain.chess.squareOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardMoveAnimationTest {
    private fun sq(
        file: Int,
        rank: Int,
    ) = squareOf(file, rank)

    /** Apply a chain of moves from the start position, returning every intermediate position. */
    private fun line(vararg moves: Move): List<Position> {
        val positions = mutableListOf(Position.start())
        for (move in moves) {
            positions += applyMove(positions.last(), move)
        }
        return positions
    }

    private fun sparse(vararg placements: Pair<Int, Piece>): Position {
        val board = MutableList<Piece?>(SQUARE_COUNT) { null }
        for ((square, piece) in placements) board[square] = piece
        return Position(
            board = board,
            sideToMove = Color.WHITE,
            castlingRights = CastlingRights.ALL,
            enPassantTarget = null,
            halfmoveClock = 0,
            fullmoveNumber = 1,
        )
    }

    @Test
    fun `quiet move resolves to a single slide with no capture or promotion`() {
        val (start, afterE4) = line(Move(sq(4, 1), sq(4, 3))) // e2e4
        val anim = diffSingleMove(start, afterE4)
        assertEquals(
            BoardMoveAnimation(
                moves = listOf(SlidingPiece(Piece(Color.WHITE, PieceType.PAWN), sq(4, 1), sq(4, 3))),
            ),
            anim,
        )
    }

    @Test
    fun `capture resolves to a slide with the captured piece at the destination`() {
        // 1. e4 d5 2. exd5
        val positions =
            line(
                Move(sq(4, 1), sq(4, 3)), // e2e4
                Move(sq(3, 6), sq(3, 4)), // d7d5
                Move(sq(4, 3), sq(3, 4)), // e4xd5
            )
        val anim = diffSingleMove(positions[2], positions[3])!!
        assertEquals(1, anim.moves.size)
        assertEquals(SlidingPiece(Piece(Color.WHITE, PieceType.PAWN), sq(4, 3), sq(3, 4)), anim.moves.single())
        assertEquals(Piece(Color.BLACK, PieceType.PAWN), anim.capturedPiece)
        assertEquals(sq(3, 4), anim.capturedSquare) // captured sits on the landing square
        assertEquals(false, anim.promoted)
    }

    @Test
    fun `en passant resolves to a slide and a captured pawn on a third square`() {
        // 1. e4 h6 2. e5 d5 3. exd6 e.p.
        val positions =
            line(
                Move(sq(4, 1), sq(4, 3)), // e2e4
                Move(sq(7, 6), sq(7, 5)), // h7h6
                Move(sq(4, 3), sq(4, 4)), // e4e5
                Move(sq(3, 6), sq(3, 4)), // d7d5 (double push, sets e.p. target d6)
                Move(sq(4, 4), sq(3, 5)), // e5xd6 e.p.
            )
        val anim = diffSingleMove(positions[4], positions[5])!!
        assertEquals(SlidingPiece(Piece(Color.WHITE, PieceType.PAWN), sq(4, 4), sq(3, 5)), anim.moves.single())
        assertEquals(Piece(Color.BLACK, PieceType.PAWN), anim.capturedPiece)
        assertEquals(sq(3, 4), anim.capturedSquare) // the captured pawn is on d5, not on the destination d6
    }

    @Test
    fun `kingside castling resolves to king and rook slides`() {
        // 1. e4 e5 2. Nf3 Nc6 3. Bc4 Nf6 4. O-O
        val positions =
            line(
                Move(sq(4, 1), sq(4, 3)), // e2e4
                Move(sq(4, 6), sq(4, 4)), // e7e5
                Move(sq(6, 0), sq(5, 2)), // Ng1f3
                Move(sq(1, 7), sq(2, 5)), // Nb8c6
                Move(sq(5, 0), sq(2, 3)), // Bf1c4
                Move(sq(6, 7), sq(5, 5)), // Ng8f6
                Move(sq(4, 0), sq(6, 0)), // O-O (e1g1)
            )
        val anim = diffSingleMove(positions[6], positions[7])!!
        assertEquals(2, anim.moves.size)
        assertTrue(SlidingPiece(Piece(Color.WHITE, PieceType.KING), sq(4, 0), sq(6, 0)) in anim.moves) // e1→g1
        assertTrue(SlidingPiece(Piece(Color.WHITE, PieceType.ROOK), sq(7, 0), sq(5, 0)) in anim.moves) // h1→f1
        assertNull(anim.capturedPiece)
    }

    @Test
    fun `quiet promotion swaps the glyph and flags promoted`() {
        val before = sparse(sq(0, 6) to Piece(Color.WHITE, PieceType.PAWN))
        val after = applyMove(before, Move(sq(0, 6), sq(0, 7), promoteTo = PieceType.QUEEN)) // a7a8=Q
        val anim = diffSingleMove(before, after)!!
        // The slide shows the pre-move pawn; the static grid reveals the queen when it ends.
        assertEquals(SlidingPiece(Piece(Color.WHITE, PieceType.PAWN), sq(0, 6), sq(0, 7)), anim.moves.single())
        assertTrue(anim.promoted)
        assertNull(anim.capturedPiece)
    }

    @Test
    fun `capture promotion flags promoted and captures the piece`() {
        val before =
            sparse(
                sq(1, 6) to Piece(Color.WHITE, PieceType.PAWN), // b7
                sq(0, 7) to Piece(Color.BLACK, PieceType.ROOK), // a8
            )
        val after = applyMove(before, Move(sq(1, 6), sq(0, 7), promoteTo = PieceType.QUEEN)) // b7xa8=Q
        val anim = diffSingleMove(before, after)!!
        assertEquals(SlidingPiece(Piece(Color.WHITE, PieceType.PAWN), sq(1, 6), sq(0, 7)), anim.moves.single())
        assertTrue(anim.promoted)
        assertEquals(Piece(Color.BLACK, PieceType.ROOK), anim.capturedPiece)
        assertEquals(sq(0, 7), anim.capturedSquare)
    }

    @Test
    fun `identical positions resolve to null`() {
        val start = Position.start()
        assertNull(diffSingleMove(start, start))
    }

    @Test
    fun `a multi-ply jump resolves to null`() {
        // start → after 1. e4 e5 is two pieces moved: not a single move.
        val positions =
            line(
                Move(sq(4, 1), sq(4, 3)), // e2e4
                Move(sq(4, 6), sq(4, 4)), // e7e5
            )
        assertNull(diffSingleMove(positions[0], positions[2]))
    }
}
