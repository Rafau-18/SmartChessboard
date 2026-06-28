package org.rurbaniak.smartchessboard.domain.preferences

import kotlin.test.Test
import kotlin.test.assertEquals

class BoardSizeTest {
    @Test
    fun belowMinClampsToMin() {
        assertEquals(BOARD_SIZE_MIN, clampBoardSize(BOARD_SIZE_MIN - 0.2f))
    }

    @Test
    fun aboveMaxClampsToMax() {
        assertEquals(BOARD_SIZE_MAX, clampBoardSize(BOARD_SIZE_MAX + 0.5f))
    }

    @Test
    fun inRangeIsUnchanged() {
        assertEquals(0.6f, clampBoardSize(0.6f))
    }

    @Test
    fun honorsExplicitBounds() {
        assertEquals(0.5f, clampBoardSize(0.9f, min = 0.2f, max = 0.5f))
        assertEquals(0.2f, clampBoardSize(0.1f, min = 0.2f, max = 0.5f))
    }

    @Test
    fun nonFiniteRawFallsBackToMin() {
        // A corrupt stored value (or a divide-by-zero in the drag math) must not propagate NaN/Infinity.
        assertEquals(BOARD_SIZE_MIN, clampBoardSize(Float.NaN))
        assertEquals(BOARD_SIZE_MIN, clampBoardSize(Float.POSITIVE_INFINITY))
        assertEquals(BOARD_SIZE_MIN, clampBoardSize(Float.NEGATIVE_INFINITY))
    }
}
