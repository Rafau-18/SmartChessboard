package org.rurbaniak.smartchessboard.domain.chess

/**
 * Counts the leaf nodes of the legal-move tree to [depth] — the classic perft correctness check.
 * Comparing against published reference counts (Chess Programming Wiki, "Perft Results") catches
 * whole classes of generation/legality/applyMove interaction bugs that no single-rule test would.
 * Test-source only; never shipped in main.
 */
internal fun perft(
    position: Position,
    depth: Int,
): Long {
    if (depth == 0) return 1L
    var nodes = 0L
    for (move in legalMoves(position)) {
        nodes += perft(applyMove(position, move), depth - 1)
    }
    return nodes
}

/**
 * Test-only FEN reader so reference positions can be written 1:1 as their canonical strings from
 * the Chess Programming Wiki, removing hand-placed-board transcription error as a source of perft
 * mismatch. Deliberately NOT in main: the shipped engine API has no FEN parsing ("What We're NOT
 * Doing" — FEN serialization lands with the eval/replay slices). Accepts 4-field FENs; the move
 * counters default to 0 / 1 when absent.
 */
internal fun fen(text: String): Position {
    val fields = text.trim().split(" ")
    require(fields.size >= 4) { "FEN needs at least 4 fields, had ${fields.size}: $text" }

    val board = MutableList<Piece?>(SQUARE_COUNT) { null }
    val rankTexts = fields[0].split("/")
    require(rankTexts.size == 8) { "FEN board needs 8 ranks, had ${rankTexts.size}: ${fields[0]}" }
    for ((i, rankText) in rankTexts.withIndex()) {
        // FEN lists rank 8 first; the Square.kt convention has rank 0 = white's first rank.
        val rank = 7 - i
        var file = 0
        for (char in rankText) {
            if (char.isDigit()) {
                file += char.digitToInt()
            } else {
                board[squareOf(file, rank)] = pieceOf(char)
                file++
            }
        }
        require(file == 8) { "FEN rank '$rankText' does not span 8 files" }
    }

    val sideToMove =
        when (fields[1]) {
            "w" -> Color.WHITE
            "b" -> Color.BLACK
            else -> error("unknown FEN side-to-move '${fields[1]}'")
        }
    val castlingRights =
        CastlingRights(
            whiteKingSide = 'K' in fields[2],
            whiteQueenSide = 'Q' in fields[2],
            blackKingSide = 'k' in fields[2],
            blackQueenSide = 'q' in fields[2],
        )
    val enPassantTarget =
        fields[3].takeIf { it != "-" }?.let {
            require(it.length == 2 && it[0] in 'a'..'h' && it[1] in '1'..'8') { "bad en-passant square '$it'" }
            squareOf(it[0] - 'a', it[1] - '1')
        }
    return Position(
        board = board,
        sideToMove = sideToMove,
        castlingRights = castlingRights,
        enPassantTarget = enPassantTarget,
        halfmoveClock = fields.getOrNull(4)?.toInt() ?: 0,
        fullmoveNumber = fields.getOrNull(5)?.toInt() ?: 1,
    )
}

private fun pieceOf(char: Char): Piece {
    val color = if (char.isUpperCase()) Color.WHITE else Color.BLACK
    val type =
        when (char.lowercaseChar()) {
            'p' -> PieceType.PAWN
            'n' -> PieceType.KNIGHT
            'b' -> PieceType.BISHOP
            'r' -> PieceType.ROOK
            'q' -> PieceType.QUEEN
            'k' -> PieceType.KING
            else -> error("unknown FEN piece char '$char'")
        }
    return Piece(color, type)
}
