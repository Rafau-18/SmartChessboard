package org.rurbaniak.smartchessboard.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.legalMoves
import org.rurbaniak.smartchessboard.domain.chess.pgn.PgnFixtures
import org.rurbaniak.smartchessboard.domain.chess.pgn.parsePgn
import org.rurbaniak.smartchessboard.domain.chess.squareOf
import org.rurbaniak.smartchessboard.presentation.board.BoardInteraction
import org.rurbaniak.smartchessboard.presentation.board.ChessBoardView

/**
 * First golden pipeline proof: the highest-value component rendered end to end — compose-resource
 * piece vectors, wood palette, selection chrome — under Robolectric NATIVE graphics. The full
 * board-state matrix lands in Phase 2; these two shots pin the pipeline itself.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xhdpi")
class ChessBoardViewScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun boardStartLight() {
        compose.golden(name = "board_start_light", dark = false) {
            ChessBoardView(
                position = Position.start(),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    @Test
    fun boardSelectionLight() {
        // Opera Game after 3...Bg4 (ply 6), White to move: the d4 pawn has both an empty push (d5,
        // dot) and a capture (e5, ring), so one shot freezes selection tint + both target marks.
        val position = parsePgn(PgnFixtures.OPERA_GAME).positions[6]
        val selected = squareOf(file = 3, rank = 3)
        val targets =
            legalMoves(position)
                .filter { it.from == selected }
                .mapTo(mutableSetOf()) { it.to }
        compose.golden(name = "board_selection_light", dark = false) {
            ChessBoardView(
                position = position,
                modifier = Modifier.fillMaxSize(),
                interaction =
                    BoardInteraction(
                        selectedSquare = selected,
                        targetSquares = targets,
                        onSquareTap = {},
                    ),
            )
        }
    }
}
