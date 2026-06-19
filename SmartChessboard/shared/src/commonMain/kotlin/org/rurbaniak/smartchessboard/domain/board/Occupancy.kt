package org.rurbaniak.smartchessboard.domain.board

import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.SQUARE_COUNT

/**
 * The board's occupancy bitmap for this [Position]: bit N is set iff square N holds a piece, using
 * the a1 = 0 convention (Square.kt). This is the inverse of [BoardEvent.BoardSnapshot.isOccupied] —
 * a snapshot built from `toOccupancy()` reports `isOccupied(n) == (pieceAt(n) != null)` for every
 * square — so the opening position (and any future on-connect snapshot) can be checked against what
 * the board senses without a FEN parser (no FEN reader ships in production; the expected position is
 * always the in-memory `positions.last()`).
 *
 * Piece colour and type are intentionally discarded: a reed-switch board senses only magnet
 * presence, never which piece sits on a square, so occupancy is the only thing a snapshot can prove.
 */
fun Position.toOccupancy(): Long {
    var bits = 0L
    for (square in 0 until SQUARE_COUNT) {
        if (pieceAt(square) != null) bits = bits or (1L shl square)
    }
    return bits
}
