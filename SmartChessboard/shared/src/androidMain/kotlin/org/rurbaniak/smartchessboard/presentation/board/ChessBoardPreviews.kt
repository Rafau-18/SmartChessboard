package org.rurbaniak.smartchessboard.presentation.board

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.rurbaniak.smartchessboard.domain.chess.Position

@Preview(showBackground = true)
@Composable
private fun ChessBoardStartPositionPreview() {
    ChessBoardView(position = Position.start(), modifier = Modifier.fillMaxWidth())
}
