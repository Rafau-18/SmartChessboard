package org.rurbaniak.smartchessboard.presentation.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class BoardScreenScaffoldTest {
    // sidePanelWidth: leftover-after-board, clamped to [SIDE_PANEL_MIN_WIDTH, SECTION_MAX_WIDTH],
    // never crushing the board below MIN_BOARD_SIDE. The 12 dp in expectations is the pane gap.

    @Test
    fun leftoverWithinRangePassesThrough() {
        // iPhone-15-Pro-shaped: row 820, board wants 361 → the panel gets the 447 leftover.
        assertEquals(447.dp, sidePanelWidth(rowWidth = 820.dp, boardTarget = 361.dp))
    }

    @Test
    fun wideLeftoverCapsAtSectionMax() {
        // Pixel-8-shaped: leftover 539 exceeds the 480 section cap.
        assertEquals(480.dp, sidePanelWidth(rowWidth = 882.dp, boardTarget = 331.dp))
    }

    @Test
    fun smallLeftoverFloorsAtPanelMin() {
        // 640x360-class window: leftover 316 → floored to 340; the board yields width instead.
        assertEquals(340.dp, sidePanelWidth(rowWidth = 608.dp, boardTarget = 280.dp))
    }

    @Test
    fun panelMinNeverCrushesBoardBelowMinSide() {
        // Split-screen-narrow: the 340 floor would leave the board under 200 dp, so the guard wins.
        assertEquals(116.dp, sidePanelWidth(rowWidth = 328.dp, boardTarget = 268.dp))
    }

    @Test
    fun degenerateWidthResolvesToZero() {
        assertEquals(0.dp, sidePanelWidth(rowWidth = 180.dp, boardTarget = 148.dp))
    }

    @Test
    fun tallExpandedWindowFloorsAtPanelMin() {
        // Width-expanded but very tall (portrait tablet): the board target exceeds the row, and the
        // panel still gets its usable minimum.
        assertEquals(340.dp, sidePanelWidth(rowWidth = 868.dp, boardTarget = 1904.dp))
    }

    @Test
    fun compactFloorPreservesFullHeightBoardOnNarrowPhone() {
        // 640×360-class landscape phone with the compact floor: the 268 leftover passes through
        // (the regular 340 floor would steal 72 dp from the height-filling board's square).
        assertEquals(268.dp, sidePanelWidth(rowWidth = 608.dp, boardTarget = 328.dp, panelMin = 260.dp))
    }

    @Test
    fun compactFloorStillWinsOverTinyLeftover() {
        // A board target that eats nearly the whole row: the panel still floors at the compact
        // minimum, and the board (rowWidth − 260 − gap = 428) stays above MIN_BOARD_SIDE.
        assertEquals(260.dp, sidePanelWidth(rowWidth = 700.dp, boardTarget = 600.dp, panelMin = 260.dp))
    }
}
