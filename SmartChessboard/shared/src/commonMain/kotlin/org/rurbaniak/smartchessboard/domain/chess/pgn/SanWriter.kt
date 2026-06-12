package org.rurbaniak.smartchessboard.domain.chess.pgn

import org.rurbaniak.smartchessboard.domain.chess.GameStatus
import org.rurbaniak.smartchessboard.domain.chess.Move
import org.rurbaniak.smartchessboard.domain.chess.MoveOutcome
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.fileOf
import org.rurbaniak.smartchessboard.domain.chess.legalMoves
import org.rurbaniak.smartchessboard.domain.chess.rankOf
import org.rurbaniak.smartchessboard.domain.chess.status
import org.rurbaniak.smartchessboard.domain.chess.validate
import kotlin.math.abs

/**
 * The SAN token for [move] in [position] — the writer half of the PGN pipeline (contract §5.3) and
 * the inverse of the parser's SAN resolution. Castling is `O-O`/`O-O-O`; piece letters omit pawns;
 * disambiguation is the minimal one computed against the legal set (file first, then rank, then
 * both); captures take `x` with pawn captures prefixed by the from-file (en passant included);
 * promotions append `=Q|R|B|N`; `+`/`#` derive from the resulting position's [status].
 *
 * Precondition: [move] is legal in [position] — the play flow generates SAN only after the engine
 * accepted the move. An illegal move throws [IllegalArgumentException].
 */
fun sanForMove(
    position: Position,
    move: Move,
): String {
    val next =
        when (val outcome = validate(position, move)) {
            is MoveOutcome.Legal -> {
                outcome.position
            }

            is MoveOutcome.Illegal -> {
                throw IllegalArgumentException("cannot write SAN for illegal move $move: ${outcome.reason}")
            }
        }
    val suffix =
        when (status(next)) {
            GameStatus.Check -> "+"
            GameStatus.Checkmate -> "#"
            else -> ""
        }
    return sanCore(position, move) + suffix
}

/** The token without check/mate suffix: castling, or letter + disambiguation + capture + target (+ promotion). */
private fun sanCore(
    position: Position,
    move: Move,
): String {
    val piece = requireNotNull(position.pieceAt(move.from)) { "no piece on square ${move.from}" }
    if (piece.type == PieceType.KING && abs(fileOf(move.to) - fileOf(move.from)) == 2) {
        return if (fileOf(move.to) > fileOf(move.from)) "O-O" else "O-O-O"
    }
    // Within the legal set a pawn's file change is exactly its capture set (en passant included).
    val isCapture =
        position.pieceAt(move.to) != null ||
            (piece.type == PieceType.PAWN && fileOf(move.from) != fileOf(move.to))
    if (piece.type == PieceType.PAWN) {
        val prefix = if (isCapture) "${'a' + fileOf(move.from)}x" else ""
        val promotion = move.promoteTo?.let { "=${pieceLetter(it)}" }.orEmpty()
        return prefix + squareName(move.to) + promotion
    }
    val capture = if (isCapture) "x" else ""
    return pieceLetter(piece.type) + disambiguation(position, move, piece.type) + capture + squareName(move.to)
}

/**
 * Minimal SAN disambiguation against the other legal moves of the same piece type onto the same
 * target square: empty when unique, the from-file when that distinguishes, else the from-rank,
 * else both. Computed from [legalMoves], so a pinned same-type piece is not a rival.
 */
private fun disambiguation(
    position: Position,
    move: Move,
    pieceType: PieceType,
): String {
    val rivals =
        legalMoves(position).filter { rival ->
            rival.from != move.from &&
                rival.to == move.to &&
                position.pieceAt(rival.from)?.type == pieceType
        }
    return when {
        rivals.isEmpty() -> ""
        rivals.none { fileOf(it.from) == fileOf(move.from) } -> "${'a' + fileOf(move.from)}"
        rivals.none { rankOf(it.from) == rankOf(move.from) } -> "${'1' + rankOf(move.from)}"
        else -> squareName(move.from)
    }
}

private fun squareName(square: Int): String = "${'a' + fileOf(square)}${'1' + rankOf(square)}"

/** SAN piece letter; pawns have none. */
private fun pieceLetter(type: PieceType): String =
    when (type) {
        PieceType.KING -> "K"
        PieceType.QUEEN -> "Q"
        PieceType.ROOK -> "R"
        PieceType.BISHOP -> "B"
        PieceType.KNIGHT -> "N"
        PieceType.PAWN -> ""
    }
