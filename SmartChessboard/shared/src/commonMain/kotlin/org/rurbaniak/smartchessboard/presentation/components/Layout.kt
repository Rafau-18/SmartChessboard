package org.rurbaniak.smartchessboard.presentation.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Max width of a board screen's content (Replay / Play / PhysicalPlay). Above it the content is
 * centred and side margins appear, so the layout never stretches edge-to-edge on a wide window.
 */
val CONTENT_MAX_WIDTH: Dp = 1200.dp

/** Max width of Replay's right column (eval panel + move list) in the two-pane layout. */
val SIDE_PANEL_MAX_WIDTH: Dp = 340.dp

/** Max width of the History games list — a list reads better narrow than stretched across a monitor. */
val LIST_MAX_WIDTH: Dp = 720.dp

/**
 * Max width of a screen's non-board sections (status, controls, move list, forms, device list) so
 * they don't stretch edge-to-edge on wide windows. One token for every screen — Play / PhysicalPlay /
 * Replay sections, the NewGame form, and the Connection content share it.
 */
val SECTION_MAX_WIDTH: Dp = 480.dp

/**
 * Vertical room reserved around the board when height-bounding it in a portrait column arrangement
 * (top bar + status line + controls stacked above/below the board).
 */
val BOARD_CHROME_COLUMN: Dp = 140.dp

/**
 * Vertical room reserved around the board in a side-pane arrangement — only the screen's own vertical
 * paddings; the controls sit beside the board, not above/below it. Consumed from Phase 4 on
 * (`BoardScreenScaffold`).
 */
val BOARD_CHROME_SIDE_PANE: Dp = 32.dp
