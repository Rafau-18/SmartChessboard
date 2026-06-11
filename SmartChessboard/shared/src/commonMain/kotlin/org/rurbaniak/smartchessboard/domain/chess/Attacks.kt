package org.rurbaniak.smartchessboard.domain.chess

/**
 * Is [square] attacked by any piece of [byColor] in this position? The shared primitive behind
 * check detection, castling legality, and the king-safety filter. Attack geometry mirrors move
 * geometry with one deliberate asymmetry: pawns *attack* only their two forward diagonals — the
 * push squares are moves, never attacks.
 */
fun isSquareAttacked(
    position: Position,
    square: Int,
    byColor: Color,
): Boolean {
    // A byColor pawn attacks this square if it stands one diagonal step on byColor's side of it.
    val pawnForward = if (byColor == Color.WHITE) 1 else -1
    for (fileDelta in intArrayOf(-1, 1)) {
        val from = offsetOrNull(square, fileDelta, -pawnForward)
        if (from != null && position.pieceAt(from) == Piece(byColor, PieceType.PAWN)) return true
    }
    for ((fileDelta, rankDelta) in KNIGHT_JUMPS) {
        val from = offsetOrNull(square, fileDelta, rankDelta)
        if (from != null && position.pieceAt(from) == Piece(byColor, PieceType.KNIGHT)) return true
    }
    for ((fileDelta, rankDelta) in ALL_DIRECTIONS) {
        val from = offsetOrNull(square, fileDelta, rankDelta)
        if (from != null && position.pieceAt(from) == Piece(byColor, PieceType.KING)) return true
    }
    return slidingAttack(position, square, byColor, ORTHOGONAL_DIRECTIONS, PieceType.ROOK) ||
        slidingAttack(position, square, byColor, DIAGONAL_DIRECTIONS, PieceType.BISHOP)
}

/** Is [color]'s king attacked by the opposite color? Requires exactly that king on the board. */
fun isInCheck(
    position: Position,
    color: Color,
): Boolean {
    val king = Piece(color, PieceType.KING)
    val kingSquare = position.board.indexOfFirst { it == king }
    require(kingSquare >= 0) { "no $color king on the board" }
    return isSquareAttacked(position, kingSquare, color.opposite)
}

/** Walks each ray from [square]; the first occupant decides — [sliderType] or queen of [byColor] attacks. */
private fun slidingAttack(
    position: Position,
    square: Int,
    byColor: Color,
    directions: List<Pair<Int, Int>>,
    sliderType: PieceType,
): Boolean {
    for ((fileDelta, rankDelta) in directions) {
        var current = offsetOrNull(square, fileDelta, rankDelta)
        while (current != null) {
            val occupant = position.pieceAt(current)
            if (occupant != null) {
                if (occupant.color == byColor &&
                    (occupant.type == sliderType || occupant.type == PieceType.QUEEN)
                ) {
                    return true
                }
                break
            }
            current = offsetOrNull(current, fileDelta, rankDelta)
        }
    }
    return false
}
