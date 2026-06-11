package org.rurbaniak.smartchessboard.domain.chess

import kotlin.math.abs

/**
 * Every legal move for the side to move: pseudo-legal moves minus any that leave the mover's own
 * king attacked. The single trial-board filter handles pins, check evasion, and the
 * castling-destination rule uniformly; castling adds two explicit checks of its own (king not
 * currently in check, transit square not attacked) — together with the trial board that is the
 * full three-square castling rule (FR-005).
 */
fun legalMoves(position: Position): List<Move> {
    val mover = position.sideToMove
    return pseudoLegalMoves(position).filter { move ->
        castlingSquaresSafe(position, move, mover) && !isInCheck(applyMove(position, move), mover)
    }
}

/**
 * Resolves a move attempt against the legal set (FR-005). A pawn push onto the last rank without
 * [Move.promoteTo] is reported as [IllegalReason.PROMOTION_PIECE_REQUIRED] — the engine never
 * auto-promotes (FR-006, contract §1.5) — but only when completing the promotion *would* be
 * legal; otherwise the attempt is simply [IllegalReason.NO_SUCH_MOVE].
 */
fun validate(
    position: Position,
    move: Move,
): MoveOutcome {
    val legal = legalMoves(position)
    return when {
        move in legal -> {
            MoveOutcome.Legal(applyMove(position, move))
        }

        move.promoteTo == null &&
            legal.any { it.from == move.from && it.to == move.to && it.promoteTo != null } -> {
            MoveOutcome.Illegal(IllegalReason.PROMOTION_PIECE_REQUIRED)
        }

        else -> {
            MoveOutcome.Illegal(IllegalReason.NO_SUCH_MOVE)
        }
    }
}

/**
 * Terminal/ongoing classification of [position] (FR-007): with no legal moves the side to move is
 * checkmated if in check, stalemated otherwise; with moves available the position is plain check
 * or ongoing. Draws by rule (repetition, 50-move, insufficient material) are never auto-detected —
 * they are marked manually per FR-018. Pure function of the position, so it classifies a freshly
 * applied move and a position loaded from elsewhere identically.
 */
fun status(position: Position): GameStatus {
    val inCheck = isInCheck(position, position.sideToMove)
    return when {
        legalMoves(position).isNotEmpty() -> if (inCheck) GameStatus.Check else GameStatus.Ongoing
        inCheck -> GameStatus.Checkmate
        else -> GameStatus.Stalemate
    }
}

/**
 * The next [Position] after playing [move], updating every FEN-relevant field: piece relocation,
 * capture removal (en passant captures on a different square than [Move.to]), rook relocation on
 * castling, promotion replacement, side-to-move flip, castling-rights revocation, en-passant
 * lifecycle, and both move counters. Assumes a legal (or at least pseudo-legal) move — the public
 * path is [validate].
 */
internal fun applyMove(
    position: Position,
    move: Move,
): Position {
    val board = position.board.toMutableList()
    val piece = requireNotNull(board[move.from]) { "no piece on square ${move.from}" }
    val mover = piece.color
    val captured = board[move.to]
    val isPawn = piece.type == PieceType.PAWN
    // A pawn landing diagonally on an empty square can only be the en-passant capture.
    val isEnPassant = isPawn && captured == null && fileOf(move.to) != fileOf(move.from)

    board[move.from] = null
    board[move.to] = if (move.promoteTo != null) Piece(mover, move.promoteTo) else piece
    if (isEnPassant) {
        // The captured pawn sits beside the destination: same file, the mover's departure rank.
        board[squareOf(fileOf(move.to), rankOf(move.from))] = null
    }
    if (piece.type == PieceType.KING && abs(fileOf(move.to) - fileOf(move.from)) == 2) {
        val rank = rankOf(move.from)
        if (fileOf(move.to) == 6) {
            board[squareOf(5, rank)] = board[squareOf(7, rank)]
            board[squareOf(7, rank)] = null
        } else {
            board[squareOf(3, rank)] = board[squareOf(0, rank)]
            board[squareOf(0, rank)] = null
        }
    }

    var rights = position.castlingRights
    if (piece.type == PieceType.KING) {
        rights =
            if (mover == Color.WHITE) {
                rights.copy(whiteKingSide = false, whiteQueenSide = false)
            } else {
                rights.copy(blackKingSide = false, blackQueenSide = false)
            }
    }
    // A rook moving off — or any piece capturing on — a rook home square revokes that right.
    rights = revokeRightOf(rights, move.from)
    rights = revokeRightOf(rights, move.to)

    val isDoublePush = isPawn && abs(rankOf(move.to) - rankOf(move.from)) == 2
    return position.copy(
        board = board,
        sideToMove = mover.opposite,
        castlingRights = rights,
        // Set only by a double push and valid for exactly one ply — every other move clears it.
        enPassantTarget =
            if (isDoublePush) {
                squareOf(fileOf(move.from), (rankOf(move.from) + rankOf(move.to)) / 2)
            } else {
                null
            },
        halfmoveClock = if (isPawn || captured != null || isEnPassant) 0 else position.halfmoveClock + 1,
        fullmoveNumber = if (mover == Color.BLACK) position.fullmoveNumber + 1 else position.fullmoveNumber,
    )
}

/**
 * Castling-specific attack conditions: the king may not castle out of check nor through an
 * attacked transit square. The third condition — not castling *into* an attacked square — is the
 * destination square, which the trial-board king-safety filter in [legalMoves] already covers.
 */
private fun castlingSquaresSafe(
    position: Position,
    move: Move,
    mover: Color,
): Boolean {
    val piece = position.pieceAt(move.from) ?: return true
    if (piece.type != PieceType.KING) return true
    val fileDelta = fileOf(move.to) - fileOf(move.from)
    if (abs(fileDelta) != 2) return true
    val opponent = mover.opposite
    val transit = squareOf(fileOf(move.from) + fileDelta / 2, rankOf(move.from))
    return !isSquareAttacked(position, move.from, opponent) &&
        !isSquareAttacked(position, transit, opponent)
}

private fun revokeRightOf(
    rights: CastlingRights,
    square: Int,
): CastlingRights =
    when (square) {
        squareOf(0, 0) -> rights.copy(whiteQueenSide = false)
        squareOf(7, 0) -> rights.copy(whiteKingSide = false)
        squareOf(0, 7) -> rights.copy(blackQueenSide = false)
        squareOf(7, 7) -> rights.copy(blackKingSide = false)
        else -> rights
    }
