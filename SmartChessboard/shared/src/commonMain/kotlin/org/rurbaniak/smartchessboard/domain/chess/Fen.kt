package org.rurbaniak.smartchessboard.domain.chess

/**
 * Serializes this position to its FEN string — all six fields, faithful to the stored state.
 * Counters are emitted as stored; normalizing them for eval caching is the Edge Function's job
 * alone (contract §5.4).
 *
 * The one deviation from naive emission: the en passant field names the target square only when
 * an enemy pawn can pseudo-legally capture onto it, else `-`. [applyMove] records the target
 * after every double push, but strict FEN consumers (Chess-API.com, contract §5.4) reject a FEN
 * naming a non-capturable square.
 */
fun Position.toFen(): String =
    buildString {
        appendPiecePlacement(this@toFen)
        append(' ')
        append(if (sideToMove == Color.WHITE) 'w' else 'b')
        append(' ')
        append(castlingField(castlingRights))
        append(' ')
        append(enPassantField())
        append(' ')
        append(halfmoveClock)
        append(' ')
        append(fullmoveNumber)
    }

private fun StringBuilder.appendPiecePlacement(position: Position) {
    for (rank in 7 downTo 0) {
        var emptyRun = 0
        for (file in 0..7) {
            val piece = position.pieceAt(squareOf(file, rank))
            if (piece == null) {
                emptyRun++
            } else {
                if (emptyRun > 0) {
                    append(emptyRun)
                    emptyRun = 0
                }
                append(fenChar(piece))
            }
        }
        if (emptyRun > 0) append(emptyRun)
        if (rank > 0) append('/')
    }
}

private fun fenChar(piece: Piece): Char {
    val letter =
        when (piece.type) {
            PieceType.PAWN -> 'p'
            PieceType.KNIGHT -> 'n'
            PieceType.BISHOP -> 'b'
            PieceType.ROOK -> 'r'
            PieceType.QUEEN -> 'q'
            PieceType.KING -> 'k'
        }
    return if (piece.color == Color.WHITE) letter.uppercaseChar() else letter
}

private fun castlingField(rights: CastlingRights): String =
    buildString {
        if (rights.whiteKingSide) append('K')
        if (rights.whiteQueenSide) append('Q')
        if (rights.blackKingSide) append('k')
        if (rights.blackQueenSide) append('q')
    }.ifEmpty { "-" }

private fun Position.enPassantField(): String {
    val target = enPassantTarget ?: return "-"
    return if (isEnPassantCapturable(target)) "${'a' + fileOf(target)}${'1' + rankOf(target)}" else "-"
}

/** True when a [sideToMove] pawn stands one rank short of [target] on an adjacent file. */
private fun Position.isEnPassantCapturable(target: Int): Boolean {
    val capturerRankStep = if (sideToMove == Color.WHITE) -1 else 1
    return intArrayOf(-1, 1).any { fileStep ->
        offsetOrNull(target, fileStep, capturerRankStep)
            ?.let { pieceAt(it) == Piece(sideToMove, PieceType.PAWN) } == true
    }
}
