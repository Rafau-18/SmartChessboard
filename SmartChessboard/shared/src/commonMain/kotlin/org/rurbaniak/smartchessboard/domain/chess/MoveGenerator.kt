package org.rurbaniak.smartchessboard.domain.chess

// Direction tables in (fileDelta, rankDelta) space, derived from the Square.kt convention
// (white pawns advance rank +1, black rank -1). Shared with Attacks.kt, which mirrors the same
// geometry from the attacked square outward.
internal val ORTHOGONAL_DIRECTIONS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
internal val DIAGONAL_DIRECTIONS = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
internal val ALL_DIRECTIONS = ORTHOGONAL_DIRECTIONS + DIAGONAL_DIRECTIONS
internal val KNIGHT_JUMPS =
    listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)

/** Promotion expansion order; a pawn reaching the last rank yields one Move per entry. */
private val PROMOTION_EXPANSION =
    listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)

/**
 * Every move the side to move could make ignoring king safety. The legality filter (pins, check
 * evasion, castling through attacked squares) is applied on top of this in a later phase.
 * Castling candidates here are gated only on castling rights + empty intervening squares; the
 * three attack conditions are the filter's job.
 */
internal fun pseudoLegalMoves(position: Position): List<Move> {
    val moves = mutableListOf<Move>()
    val mover = position.sideToMove
    for (from in 0 until SQUARE_COUNT) {
        val piece = position.pieceAt(from) ?: continue
        if (piece.color != mover) continue
        when (piece.type) {
            PieceType.PAWN -> {
                pawnMoves(position, from, mover, moves)
            }

            PieceType.KNIGHT -> {
                stepMoves(position, from, mover, KNIGHT_JUMPS, moves)
            }

            PieceType.BISHOP -> {
                slidingMoves(position, from, mover, DIAGONAL_DIRECTIONS, moves)
            }

            PieceType.ROOK -> {
                slidingMoves(position, from, mover, ORTHOGONAL_DIRECTIONS, moves)
            }

            PieceType.QUEEN -> {
                slidingMoves(position, from, mover, ALL_DIRECTIONS, moves)
            }

            PieceType.KING -> {
                stepMoves(position, from, mover, ALL_DIRECTIONS, moves)
                castlingCandidates(position, from, mover, moves)
            }
        }
    }
    return moves
}

private fun pawnMoves(
    position: Position,
    from: Int,
    mover: Color,
    moves: MutableList<Move>,
) {
    val forward = if (mover == Color.WHITE) 1 else -1
    val homeRank = if (mover == Color.WHITE) 1 else 6

    val oneAhead = offsetOrNull(from, 0, forward)
    if (oneAhead != null && position.pieceAt(oneAhead) == null) {
        addPawnMove(from, oneAhead, mover, moves)
        if (rankOf(from) == homeRank) {
            val twoAhead = offsetOrNull(from, 0, 2 * forward)
            if (twoAhead != null && position.pieceAt(twoAhead) == null) {
                moves += Move(from, twoAhead)
            }
        }
    }

    for (fileDelta in intArrayOf(-1, 1)) {
        val target = offsetOrNull(from, fileDelta, forward) ?: continue
        val occupant = position.pieceAt(target)
        if (occupant != null && occupant.color != mover) {
            addPawnMove(from, target, mover, moves)
        } else if (occupant == null && target == position.enPassantTarget) {
            moves += Move(from, target)
        }
    }
}

private fun addPawnMove(
    from: Int,
    to: Int,
    mover: Color,
    moves: MutableList<Move>,
) {
    val promotionRank = if (mover == Color.WHITE) 7 else 0
    if (rankOf(to) == promotionRank) {
        for (promoteTo in PROMOTION_EXPANSION) {
            moves += Move(from, to, promoteTo)
        }
    } else {
        moves += Move(from, to)
    }
}

private fun stepMoves(
    position: Position,
    from: Int,
    mover: Color,
    steps: List<Pair<Int, Int>>,
    moves: MutableList<Move>,
) {
    for ((fileDelta, rankDelta) in steps) {
        val to = offsetOrNull(from, fileDelta, rankDelta) ?: continue
        val occupant = position.pieceAt(to)
        if (occupant == null || occupant.color != mover) {
            moves += Move(from, to)
        }
    }
}

private fun slidingMoves(
    position: Position,
    from: Int,
    mover: Color,
    directions: List<Pair<Int, Int>>,
    moves: MutableList<Move>,
) {
    for ((fileDelta, rankDelta) in directions) {
        var to = offsetOrNull(from, fileDelta, rankDelta)
        while (to != null) {
            val occupant = position.pieceAt(to)
            if (occupant == null) {
                moves += Move(from, to)
            } else {
                if (occupant.color != mover) {
                    moves += Move(from, to)
                }
                break
            }
            to = offsetOrNull(to, fileDelta, rankDelta)
        }
    }
}

private fun castlingCandidates(
    position: Position,
    from: Int,
    mover: Color,
    moves: MutableList<Move>,
) {
    val homeRank = if (mover == Color.WHITE) 0 else 7
    if (from != squareOf(4, homeRank)) return
    val rights = position.castlingRights
    val kingSide = if (mover == Color.WHITE) rights.whiteKingSide else rights.blackKingSide
    val queenSide = if (mover == Color.WHITE) rights.whiteQueenSide else rights.blackQueenSide
    if (kingSide &&
        position.pieceAt(squareOf(5, homeRank)) == null &&
        position.pieceAt(squareOf(6, homeRank)) == null
    ) {
        moves += Move(from, squareOf(6, homeRank))
    }
    if (queenSide &&
        position.pieceAt(squareOf(1, homeRank)) == null &&
        position.pieceAt(squareOf(2, homeRank)) == null &&
        position.pieceAt(squareOf(3, homeRank)) == null
    ) {
        moves += Move(from, squareOf(2, homeRank))
    }
}
