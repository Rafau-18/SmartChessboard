package org.rurbaniak.smartchessboard.domain.chess

internal val NO_CASTLING = CastlingRights(false, false, false, false)

/** Builds a hand-set position from (square, piece) placements; everything else defaults to inert. */
internal fun positionOf(
    vararg placements: Pair<Int, Piece>,
    sideToMove: Color = Color.WHITE,
    castlingRights: CastlingRights = NO_CASTLING,
    enPassantTarget: Int? = null,
): Position {
    val board = MutableList<Piece?>(SQUARE_COUNT) { null }
    for ((square, piece) in placements) {
        board[square] = piece
    }
    return Position(
        board = board,
        sideToMove = sideToMove,
        castlingRights = castlingRights,
        enPassantTarget = enPassantTarget,
        halfmoveClock = 0,
        fullmoveNumber = 1,
    )
}

internal fun white(type: PieceType) = Piece(Color.WHITE, type)

internal fun black(type: PieceType) = Piece(Color.BLACK, type)
