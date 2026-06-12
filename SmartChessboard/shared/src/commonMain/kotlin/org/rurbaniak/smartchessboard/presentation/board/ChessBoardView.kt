package org.rurbaniak.smartchessboard.presentation.board

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/**
 * Stateless 8×8 board rendering a [position] — knows nothing about games, navigation, or input
 * (gestures arrive with play mode). Orientation is fixed white-at-bottom. The board fills
 * whatever box the caller's [modifier] provides, kept square via `aspectRatio(1f)` — no
 * hardcoded sizes, so the reuse contract stays size-agnostic.
 */
@Composable
fun ChessBoardView(
    position: Position,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.aspectRatio(1f)) {
        for (rowFromTop in 0..7) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                for (column in 0..7) {
                    val square = squareAt(column, rowFromTop)
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(if (isDarkSquare(square)) DARK_SQUARE else LIGHT_SQUARE),
                    ) {
                        position.pieceAt(square)?.let { piece ->
                            Image(
                                painter = painterResource(pieceDrawable(piece)),
                                contentDescription = pieceDescription(piece),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Square index rendered at grid cell ([column] from the left, [rowFromTop] from the top) with
 * white at the bottom: the top row is rank 8, the bottom row rank 1.
 */
internal fun squareAt(
    column: Int,
    rowFromTop: Int,
): Int = squareOf(file = column, rank = 7 - rowFromTop)

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
