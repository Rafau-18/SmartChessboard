package org.rurbaniak.smartchessboard.presentation.board

import org.rurbaniak.smartchessboard.domain.chess.squareOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UciArrowTest {
    @Test
    fun parsesPlainMove() {
        assertEquals(BoardArrow(from = 12, to = 28), parseUciArrow("e2e4"))
    }

    @Test
    fun parsesPromotionIgnoringSuffix() {
        assertEquals(
            BoardArrow(from = squareOf(4, 6), to = squareOf(4, 7)),
            parseUciArrow("e7e8q"),
        )
    }

    @Test
    fun parsesCornerToCorner() {
        assertEquals(BoardArrow(from = 0, to = 63), parseUciArrow("a1h8"))
    }

    @Test
    fun rejectsMalformedStrings() {
        assertNull(parseUciArrow(""))
        assertNull(parseUciArrow("e2"))
        assertNull(parseUciArrow("z2e4"))
        assertNull(parseUciArrow("e9e4"))
        assertNull(parseUciArrow("e2e9"))
        assertNull(parseUciArrow("12e4"))
    }
}
