package org.rurbaniak.smartchessboard.presentation.board

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.jetbrains.compose.resources.painterResource
import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.chess.PROMOTION_TARGETS
import org.rurbaniak.smartchessboard.domain.chess.Piece
import org.rurbaniak.smartchessboard.domain.chess.PieceType

/**
 * Promotion targets in the order the picker shows them (strongest first). Kept in sync with the
 * engine's [PROMOTION_TARGETS] — the same four pieces, just ordered for the UI.
 */
internal val PROMOTION_ORDER: List<PieceType> =
    listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)

/**
 * Modal piece-picker shown when an accepted pawn move reaches the last rank (FR-006). Tapping a
 * piece completes the move via [onPick]; dismissing (back / scrim tap) cancels it via [onDismiss]
 * so no move is saved. Renders the four promotion pieces of the moving [color] using the board's
 * existing vector assets.
 */
@Composable
fun PromotionPicker(
    color: Color,
    onPick: (PieceType) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        // Animate the surface in (fade + slight scale-up). The Dialog window itself can't be tweened,
        // so the enter transition runs on the content; dismissal removes the dialog wholesale.
        val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn() + scaleIn(initialScale = 0.92f),
        ) {
            PromotionPickerSurface(color = color, onPick = onPick)
        }
    }
}

/** The picker's visible content, split out from [Dialog] so it can be previewed in isolation. */
@Composable
internal fun PromotionPickerSurface(
    color: Color,
    onPick: (PieceType) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Promote to",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PROMOTION_ORDER.forEach { type ->
                    val piece = Piece(color, type)
                    Image(
                        painter = painterResource(pieceDrawable(piece)),
                        contentDescription = promotionPieceLabel(type),
                        modifier =
                            Modifier
                                .size(56.dp)
                                .clickable { onPick(type) },
                    )
                }
            }
        }
    }
}

private fun promotionPieceLabel(type: PieceType): String =
    when (type) {
        PieceType.QUEEN -> "Promote to queen"
        PieceType.ROOK -> "Promote to rook"
        PieceType.BISHOP -> "Promote to bishop"
        PieceType.KNIGHT -> "Promote to knight"
        PieceType.PAWN, PieceType.KING -> "Promote"
    }
