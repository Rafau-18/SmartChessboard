package org.rurbaniak.smartchessboard.domain.chess

enum class Color {
    WHITE,
    BLACK,
    ;

    val opposite: Color
        get() = if (this == WHITE) BLACK else WHITE
}

enum class PieceType { PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING }

/** The four piece types a pawn may promote to (FR-006). */
val PROMOTION_TARGETS: Set<PieceType> =
    setOf(PieceType.KNIGHT, PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN)

data class Piece(
    val color: Color,
    val type: PieceType,
)
