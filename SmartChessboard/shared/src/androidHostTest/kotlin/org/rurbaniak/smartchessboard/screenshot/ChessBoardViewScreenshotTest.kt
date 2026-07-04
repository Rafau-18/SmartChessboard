package org.rurbaniak.smartchessboard.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.chess.Piece
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.legalMoves
import org.rurbaniak.smartchessboard.domain.chess.pgn.PgnFixtures
import org.rurbaniak.smartchessboard.domain.chess.pgn.parsePgn
import org.rurbaniak.smartchessboard.domain.chess.positionOf
import org.rurbaniak.smartchessboard.domain.chess.squareOf
import org.rurbaniak.smartchessboard.domain.chess.white
import org.rurbaniak.smartchessboard.presentation.board.BoardInteraction
import org.rurbaniak.smartchessboard.presentation.board.ChessBoardView
import org.rurbaniak.smartchessboard.presentation.board.PromotionPickerSurface
import org.rurbaniak.smartchessboard.presentation.board.parseUciArrow

/**
 * The board's full visual vocabulary, one golden per state (Phase 2 matrix). Light theme only:
 * the wood squares and every on-wood overlay are constant across modes (see `ChessColors`), so a
 * dark variant of a pure board shot would be a bit-identical duplicate. The one exception is the
 * promotion shot — the picker surface is Material-themed, so it gets both modes. Check and
 * last-move highlights are not board states (the component has no such API); the physical-mode
 * lift highlight and occupancy dots complete the real vocabulary instead.
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

    @Test
    fun boardStartBlackLight() {
        compose.golden(name = "board_start_black_light", dark = false) {
            ChessBoardView(
                position = Position.start(),
                modifier = Modifier.fillMaxSize(),
                orientation = Color.BLACK,
            )
        }
    }

    @Test
    fun boardLiftHighlightLight() {
        // Physical mode: two lifted pieces (e2, e7) tinted via highlightedSquares — display-only,
        // independent of interaction.
        compose.golden(name = "board_lift_highlight_light", dark = false) {
            ChessBoardView(
                position = Position.start(),
                modifier = Modifier.fillMaxSize(),
                highlightedSquares = setOf(squareOf(4, 1), squareOf(4, 6)),
            )
        }
    }

    @Test
    fun boardOccupancyDotsLight() {
        // Physical live matrix drifted from the shown position: the pawn's piece is still on e2 but
        // the sensed occupancy already moved to e4 — no dot under the e2 piece, a dot on empty e4.
        val startOccupancy = (0xFFFFL shl 48) or 0xFFFFL
        val drifted = (startOccupancy and (1L shl squareOf(4, 1)).inv()) or (1L shl squareOf(4, 3))
        compose.golden(name = "board_occupancy_dots_light", dark = false) {
            ChessBoardView(
                position = Position.start(),
                modifier = Modifier.fillMaxSize(),
                occupancyDots = drifted,
            )
        }
    }

    @Test
    fun boardBestMoveArrowLight() {
        // Opera Game after 16...Nxb8 (ply 32): analysis suggests the famous 17. Rd8# — a full-file
        // arrow d1→d8 over a capture-scarred position.
        compose.golden(name = "board_best_move_arrow_light", dark = false) {
            ChessBoardView(
                position = parsePgn(PgnFixtures.OPERA_GAME).positions[32],
                modifier = Modifier.fillMaxSize(),
                bestMoveArrow = parseUciArrow("d1d8"),
            )
        }
    }

    @Test
    fun boardCaptureMiddlegameLight() {
        // Immortal Game after 18...Bxg1 (ply 36): both sides gutted — the capture-rich vocabulary.
        compose.golden(name = "board_capture_middlegame_light", dark = false) {
            ChessBoardView(
                position = parsePgn(PgnFixtures.IMMORTAL_GAME).positions[36],
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    @Test
    fun boardTerminalMateLight() {
        // Opera Game final position (17. Rd8#) — the frozen display-only terminal board.
        compose.golden(name = "board_terminal_mate_light", dark = false) {
            ChessBoardView(
                position = parsePgn(PgnFixtures.OPERA_GAME).positions.last(),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    @Test
    fun boardPromotionLight() {
        compose.golden(name = "board_promotion_light", dark = false) {
            PromotionBoard()
        }
    }

    @Test
    fun boardPromotionDark() {
        // The one dark board shot: the picker surface is Material-themed even though the wood is not.
        compose.golden(name = "board_promotion_dark", dark = true) {
            PromotionBoard()
        }
    }
}

/**
 * A white pawn one push from promotion with the picker surface overlaid centre — the Dialog wrapper
 * is skipped (a Robolectric golden captures one window) in favor of the split-out picker surface.
 */
@androidx.compose.runtime.Composable
private fun PromotionBoard() {
    val position =
        positionOf(
            squareOf(4, 6) to white(PieceType.PAWN),
            squareOf(6, 0) to white(PieceType.KING),
            squareOf(6, 7) to Piece(Color.BLACK, PieceType.KING),
        )
    Box(modifier = Modifier.fillMaxSize()) {
        ChessBoardView(position = position, modifier = Modifier.fillMaxSize())
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PromotionPickerSurface(color = Color.WHITE, onPick = {})
        }
    }
}
