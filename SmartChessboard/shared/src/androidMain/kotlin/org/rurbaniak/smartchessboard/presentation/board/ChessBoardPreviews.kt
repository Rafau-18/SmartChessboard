package org.rurbaniak.smartchessboard.presentation.board

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.legalMoves
import org.rurbaniak.smartchessboard.domain.chess.squareOf

@Preview(showBackground = true)
@Composable
private fun ChessBoardStartPositionPreview() {
    ChessBoardView(position = Position.start(), modifier = Modifier.fillMaxWidth())
}

@Preview(showBackground = true)
@Composable
private fun ChessBoardSelectionPreview() {
    // e2 selected; its legal pushes (e3, e4) render as target dots.
    val e2 = squareOf(file = 4, rank = 1)
    ChessBoardView(
        position = Position.start(),
        modifier = Modifier.fillMaxWidth(),
        interaction =
            BoardInteraction(
                selectedSquare = e2,
                targetSquares = legalMoves(Position.start()).filter { it.from == e2 }.map { it.to }.toSet(),
                onSquareTap = {},
            ),
    )
}

@Preview(showBackground = true)
@Composable
private fun ChessBoardFlippedPreview() {
    ChessBoardView(
        position = Position.start(),
        modifier = Modifier.fillMaxWidth(),
        orientation = Color.BLACK,
    )
}

@Preview(showBackground = true)
@Composable
private fun PromotionPickerWhitePreview() {
    PromotionPickerSurface(color = Color.WHITE, onPick = {})
}

@Preview(showBackground = true)
@Composable
private fun PromotionPickerBlackPreview() {
    PromotionPickerSurface(color = Color.BLACK, onPick = {})
}
