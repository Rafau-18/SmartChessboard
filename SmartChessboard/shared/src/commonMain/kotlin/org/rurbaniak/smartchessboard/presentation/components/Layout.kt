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
