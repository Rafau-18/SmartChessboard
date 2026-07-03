package org.rurbaniak.smartchessboard.presentation.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Max width of a board screen's content (Replay / Play / PhysicalPlay). Above it the content is
 * centred and side margins appear, so the layout never stretches edge-to-edge on a wide window.
 */
val CONTENT_MAX_WIDTH: Dp = 1200.dp

/**
 * Min width of a board screen's side panel (`BoardScreenScaffold`) — below this the move list and
 * controls stop being usable, so the panel claims it even from the board (which keeps its own
 * [MIN_BOARD_SIDE] floor). The panel's max is the shared [SECTION_MAX_WIDTH] cap.
 */
val SIDE_PANEL_MIN_WIDTH: Dp = 340.dp

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
 * paddings; the controls sit beside the board, not above/below it. Consumed by `BoardScreenScaffold`
 * when splitting the row: the board's height budget is the pane height less this reservation.
 */
val BOARD_CHROME_SIDE_PANE: Dp = 32.dp

/**
 * The board never shrinks below this, even in a very tight window, so it stays usable. Shared by the
 * board's own sizing (`ResizableBoardBox`) and the side-pane split (`sidePanelWidth`), which must not
 * hand the panel width the board needs to stay above this floor.
 */
val MIN_BOARD_SIDE: Dp = 200.dp

/**
 * Fixed height of the reserved banner slot on board screens (`BoardScreenScaffold`). The slot is
 * always laid out — empty when no banner is showing — so a banner appearing or disappearing never
 * moves or resizes the chessboard (the no-jump invariant). Content larger than the slot must bound
 * itself (internal scroll / ellipsis) rather than grow it; a screen with taller banners passes its
 * own slot height instead.
 */
val BANNER_SLOT_HEIGHT: Dp = 56.dp
