package org.rurbaniak.smartchessboard.presentation.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.rurbaniak.smartchessboard.domain.chess.Piece
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.fileOf
import org.rurbaniak.smartchessboard.domain.chess.rankOf
import org.rurbaniak.smartchessboard.domain.chess.squareOf
import smartchessboard.shared.generated.resources.Res
import smartchessboard.shared.generated.resources.piece_bb
import smartchessboard.shared.generated.resources.piece_bk
import smartchessboard.shared.generated.resources.piece_bn
import smartchessboard.shared.generated.resources.piece_bp
import smartchessboard.shared.generated.resources.piece_bq
import smartchessboard.shared.generated.resources.piece_br
import smartchessboard.shared.generated.resources.piece_wb
import smartchessboard.shared.generated.resources.piece_wk
import smartchessboard.shared.generated.resources.piece_wn
import smartchessboard.shared.generated.resources.piece_wp
import smartchessboard.shared.generated.resources.piece_wq
import smartchessboard.shared.generated.resources.piece_wr
import org.rurbaniak.smartchessboard.domain.chess.Color as PieceColor

private val LIGHT_SQUARE = Color(0xFFF0D9B5)
private val DARK_SQUARE = Color(0xFFB58863)
private val ARROW_COLOR = Color(0xCC2E7D32)
private val SELECTED_TINT = Color(0x80FBE34D)
private val TARGET_MARK = Color(0x662E7D32)

/** A from→to square pair (Square.kt indexing) drawn as an arrow above the pieces. */
data class BoardArrow(
    val from: Int,
    val to: Int,
)

/**
 * Optional play-mode wiring for [ChessBoardView]. When non-null the board becomes tappable and
 * renders selection + legal-target highlights; when null the board is display-only (Replay
 * renders exactly as before). [targetSquares] are the legal destinations of [selectedSquare] in
 * Square.kt indexing — empty targets get a dot, occupied (capture) targets a ring.
 */
data class BoardInteraction(
    val selectedSquare: Int?,
    val targetSquares: Set<Int>,
    val onSquareTap: (Int) -> Unit,
)

/**
 * Square pair of a UCI move ("e2e4"; promotion "e7e8q" parses with the suffix ignored), or null
 * when the string is not a well-formed UCI move.
 */
fun parseUciArrow(uci: String): BoardArrow? {
    if (uci.length < 4) return null
    val from = squareOrNull(uci[0], uci[1]) ?: return null
    val to = squareOrNull(uci[2], uci[3]) ?: return null
    return BoardArrow(from = from, to = to)
}

private fun squareOrNull(
    fileChar: Char,
    rankChar: Char,
): Int? {
    val file = fileChar - 'a'
    val rank = rankChar - '1'
    return if (file in 0..7 && rank in 0..7) squareOf(file, rank) else null
}

/**
 * Stateless 8×8 board rendering a [position] — it holds no game/navigation state; the host owns
 * selection and turn logic. [orientation] is the color rendered at the bottom (default white).
 * Passing a non-null [interaction] turns the board into a tappable play surface with selection +
 * legal-target highlights; leaving it null keeps the board display-only, so Replay call sites
 * render exactly as before. The board fills whatever box the caller's [modifier] provides, kept
 * square via `aspectRatio(1f)` — no hardcoded sizes, so the reuse contract stays size-agnostic.
 * [bestMoveArrow] is a render-only overlay (analysis); existing call sites are unaffected by its
 * default.
 */
@Composable
fun ChessBoardView(
    position: Position,
    modifier: Modifier = Modifier,
    orientation: PieceColor = PieceColor.WHITE,
    interaction: BoardInteraction? = null,
    bestMoveArrow: BoardArrow? = null,
) {
    Box(modifier = modifier.aspectRatio(1f)) {
        Column(modifier = Modifier.fillMaxSize()) {
            for (rowFromTop in 0..7) {
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    for (column in 0..7) {
                        val square = squareAt(column, rowFromTop, orientation)
                        val piece = position.pieceAt(square)
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(if (isDarkSquare(square)) DARK_SQUARE else LIGHT_SQUARE)
                                    .let { base ->
                                        if (interaction != null) {
                                            base.clickable { interaction.onSquareTap(square) }
                                        } else {
                                            base
                                        }
                                    },
                        ) {
                            if (interaction?.selectedSquare == square) {
                                Box(Modifier.matchParentSize().background(SELECTED_TINT))
                            }
                            piece?.let {
                                Image(
                                    painter = painterResource(pieceDrawable(it)),
                                    contentDescription = pieceDescription(it),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            if (interaction != null && square in interaction.targetSquares) {
                                Canvas(modifier = Modifier.matchParentSize()) {
                                    drawTargetMark(occupied = piece != null)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (bestMoveArrow != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawBestMoveArrow(bestMoveArrow, orientation)
            }
        }
    }
}

/** An empty legal target gets a centered dot; a capture target gets a ring around the piece. */
private fun DrawScope.drawTargetMark(occupied: Boolean) {
    val extent = size.minDimension
    if (occupied) {
        drawCircle(
            color = TARGET_MARK,
            radius = extent * 0.42f,
            style = Stroke(width = extent * 0.08f),
        )
    } else {
        drawCircle(color = TARGET_MARK, radius = extent * 0.16f)
    }
}

private fun DrawScope.drawBestMoveArrow(
    arrow: BoardArrow,
    orientation: PieceColor,
) {
    val cell = size.width / 8f

    fun center(square: Int): Offset {
        val column = if (orientation == PieceColor.WHITE) fileOf(square) else 7 - fileOf(square)
        val rowFromTop = if (orientation == PieceColor.WHITE) 7 - rankOf(square) else rankOf(square)
        return Offset(
            x = (column + 0.5f) * cell,
            y = (rowFromTop + 0.5f) * cell,
        )
    }

    val from = center(arrow.from)
    val to = center(arrow.to)
    val direction = to - from
    val length = direction.getDistance()
    if (length == 0f) return
    val unit = direction / length
    val headLength = cell * 0.45f
    val headBase = to - unit * headLength
    drawLine(
        color = ARROW_COLOR,
        start = from,
        end = headBase,
        strokeWidth = cell * 0.18f,
        cap = StrokeCap.Round,
    )
    val perpendicular = Offset(-unit.y, unit.x)
    val halfWidth = cell * 0.2f
    val left = headBase + perpendicular * halfWidth
    val right = headBase - perpendicular * halfWidth
    val head =
        Path().apply {
            moveTo(to.x, to.y)
            lineTo(left.x, left.y)
            lineTo(right.x, right.y)
            close()
        }
    drawPath(head, ARROW_COLOR)
}

/**
 * Square index rendered at grid cell ([column] from the left, [rowFromTop] from the top) for the
 * given [orientation] — the color shown at the bottom. White-bottom puts rank 8 on the top row
 * and the a-file on the left; black-bottom is the 180° rotation (rank 1 on top, h-file on the
 * left). This mapping is the single authority for tap→square under both orientations.
 */
internal fun squareAt(
    column: Int,
    rowFromTop: Int,
    orientation: PieceColor = PieceColor.WHITE,
): Int =
    when (orientation) {
        PieceColor.WHITE -> squareOf(file = column, rank = 7 - rowFromTop)
        PieceColor.BLACK -> squareOf(file = 7 - column, rank = rowFromTop)
    }

/** a1-dark convention: squares whose file+rank parity is even are dark. */
internal fun isDarkSquare(square: Int): Boolean = (fileOf(square) + rankOf(square)) % 2 == 0

internal fun pieceDrawable(piece: Piece): DrawableResource =
    when (piece.color) {
        PieceColor.WHITE -> {
            when (piece.type) {
                PieceType.KING -> Res.drawable.piece_wk
                PieceType.QUEEN -> Res.drawable.piece_wq
                PieceType.ROOK -> Res.drawable.piece_wr
                PieceType.BISHOP -> Res.drawable.piece_wb
                PieceType.KNIGHT -> Res.drawable.piece_wn
                PieceType.PAWN -> Res.drawable.piece_wp
            }
        }

        PieceColor.BLACK -> {
            when (piece.type) {
                PieceType.KING -> Res.drawable.piece_bk
                PieceType.QUEEN -> Res.drawable.piece_bq
                PieceType.ROOK -> Res.drawable.piece_br
                PieceType.BISHOP -> Res.drawable.piece_bb
                PieceType.KNIGHT -> Res.drawable.piece_bn
                PieceType.PAWN -> Res.drawable.piece_bp
            }
        }
    }

private fun pieceDescription(piece: Piece): String {
    val color = if (piece.color == PieceColor.WHITE) "White" else "Black"
    val type =
        when (piece.type) {
            PieceType.KING -> "king"
            PieceType.QUEEN -> "queen"
            PieceType.ROOK -> "rook"
            PieceType.BISHOP -> "bishop"
            PieceType.KNIGHT -> "knight"
            PieceType.PAWN -> "pawn"
        }
    return "$color $type"
}
