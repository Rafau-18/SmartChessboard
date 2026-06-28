package org.rurbaniak.smartchessboard.presentation.board

import org.rurbaniak.smartchessboard.domain.chess.Piece
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.fileOf
import org.rurbaniak.smartchessboard.domain.chess.rankOf
import kotlin.math.abs

/** One piece sliding from [from] to [to]; [piece] is its appearance *during* the slide. */
internal data class SlidingPiece(
    val piece: Piece,
    val from: Int,
    val to: Int,
)

/**
 * A single board move resolved from a before/after position diff, in just enough detail to drive the
 * slide overlay. [moves] is one entry for a plain move and two for castling (king + rook). A captured
 * piece (if any) fades at [capturedSquare] — the destination for a normal capture, a third square for
 * en passant. [promoted] is true when the mover changed type; the overlay shows the pre-move glyph and
 * the static grid reveals the promoted piece when the slide ends.
 */
internal data class BoardMoveAnimation(
    val moves: List<SlidingPiece>,
    val capturedPiece: Piece? = null,
    val capturedSquare: Int? = null,
    val promoted: Boolean = false,
)

/**
 * Resolve the board delta between [prev] and [next] into the one move that produced it, when that
 * delta matches exactly one move shape — a quiet move, a capture, en passant, castling, or a promotion
 * variant of those. Returns null for anything else (no change, a multi-ply jump, a load, or any delta
 * that doesn't fit a single move), so the caller falls back to an instant render.
 *
 * This is *observational*: it reads both boards and classifies what actually changed rather than
 * predicting geometry from a [org.rurbaniak.smartchessboard.domain.chess.Move]. So — unlike
 * `SequenceInterpreter.footprintOf`, which derives a footprint from a move and must stay in lock-step
 * with `ChessRules.applyMove` — this needs no SYNC pairing: if applyMove's castling / en-passant
 * geometry ever changed, the resulting boards would still diff correctly here.
 */
internal fun diffSingleMove(
    prev: Position,
    next: Position,
): BoardMoveAnimation? {
    if (prev.board.size != next.board.size) return null
    val emptied = ArrayList<Int>(2) // had a piece, now empty
    val filled = ArrayList<Int>(2) // was empty, now a piece
    val replaced = ArrayList<Int>(1) // a piece, now a different piece (capture landing)
    for (sq in prev.board.indices) {
        val before = prev.board[sq]
        val after = next.board[sq]
        when {
            before == after -> {}

            before != null && after == null -> {
                emptied += sq
            }

            before == null && after != null -> {
                filled += sq
            }

            else -> {
                replaced += sq
            }
        }
    }
    return when {
        emptied.size == 1 && filled.size == 1 && replaced.isEmpty() -> {
            plainMove(prev, next, from = emptied[0], to = filled[0], captured = null)
        }

        emptied.size == 1 && filled.isEmpty() && replaced.size == 1 -> {
            plainMove(prev, next, from = emptied[0], to = replaced[0], captured = prev.board[replaced[0]])
        }

        emptied.size == 2 && filled.size == 1 && replaced.isEmpty() -> {
            enPassant(prev, next, emptied, to = filled[0])
        }

        emptied.size == 2 && filled.size == 2 && replaced.isEmpty() -> {
            castling(prev, next, emptied, filled)
        }

        else -> {
            null
        }
    }
}

/** Quiet move or capture (each possibly a promotion): one piece leaves [from] and lands on [to]. */
private fun plainMove(
    prev: Position,
    next: Position,
    from: Int,
    to: Int,
    captured: Piece?,
): BoardMoveAnimation? {
    val mover = prev.board[from] ?: return null
    val landed = next.board[to] ?: return null
    if (mover.color != landed.color) return null
    if (captured != null && captured.color == mover.color) return null
    return BoardMoveAnimation(
        moves = listOf(SlidingPiece(mover, from, to)),
        capturedPiece = captured,
        capturedSquare = if (captured != null) to else null,
        promoted = mover.type != landed.type,
    )
}

/** En passant: the mover and the captured pawn both vacate; the (empty) destination fills. */
private fun enPassant(
    prev: Position,
    next: Position,
    emptied: List<Int>,
    to: Int,
): BoardMoveAnimation? {
    val mover = next.board[to] ?: return null
    if (mover.type != PieceType.PAWN) return null
    val from =
        emptied.firstOrNull {
            val p = prev.board[it]
            p != null && p.color == mover.color && p.type == PieceType.PAWN
        } ?: return null
    val capturedSquare = emptied.firstOrNull { it != from } ?: return null
    val captured = prev.board[capturedSquare] ?: return null
    if (captured.type != PieceType.PAWN || captured.color == mover.color) return null
    // The captured pawn shares the destination file and sits on the mover's departure rank.
    if (fileOf(capturedSquare) != fileOf(to) || rankOf(capturedSquare) != rankOf(from)) return null
    if (fileOf(from) == fileOf(to)) return null
    return BoardMoveAnimation(
        moves = listOf(SlidingPiece(prev.board[from]!!, from, to)),
        capturedPiece = captured,
        capturedSquare = capturedSquare,
    )
}

/** Castling: king and rook both vacate and both fill; the king steps exactly two files. */
private fun castling(
    prev: Position,
    next: Position,
    emptied: List<Int>,
    filled: List<Int>,
): BoardMoveAnimation? {
    val kingFrom = emptied.firstOrNull { prev.board[it]?.type == PieceType.KING } ?: return null
    val rookFrom = emptied.firstOrNull { prev.board[it]?.type == PieceType.ROOK } ?: return null
    if (kingFrom == rookFrom) return null
    val kingTo = filled.firstOrNull { next.board[it]?.type == PieceType.KING } ?: return null
    val rookTo = filled.firstOrNull { next.board[it]?.type == PieceType.ROOK } ?: return null
    val king = prev.board[kingFrom]!!
    val rook = prev.board[rookFrom]!!
    if (king.color != rook.color) return null
    if (next.board[kingTo]!!.color != king.color || next.board[rookTo]!!.color != king.color) return null
    if (abs(fileOf(kingTo) - fileOf(kingFrom)) != 2) return null
    if (rankOf(kingFrom) != rankOf(kingTo)) return null
    if (rankOf(rookFrom) != rankOf(rookTo)) return null
    if (rankOf(kingFrom) != rankOf(rookFrom)) return null
    return BoardMoveAnimation(
        moves =
            listOf(
                SlidingPiece(king, kingFrom, kingTo),
                SlidingPiece(rook, rookFrom, rookTo),
            ),
    )
}
