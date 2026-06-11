package org.rurbaniak.smartchessboard.domain.chess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SquareTest {
    @Test
    fun cornerSquaresMatchContractSection1_3() {
        // a1 = 0, h1 = 7, a8 = 56, h8 = 63 per docs/reference/contract-surfaces.md §1.3.
        assertEquals(0, squareOf(0, 0))
        assertEquals(7, squareOf(7, 0))
        assertEquals(56, squareOf(0, 7))
        assertEquals(63, squareOf(7, 7))
    }

    @Test
    fun indexToFileRankToIndexRoundTripsForAllSquares() {
        for (square in 0 until SQUARE_COUNT) {
            assertEquals(square, squareOf(fileOf(square), rankOf(square)))
        }
    }

    @Test
    fun fileRankToIndexToFileRankRoundTripsForAllPairs() {
        for (file in 0..7) {
            for (rank in 0..7) {
                val square = squareOf(file, rank)
                assertEquals(file, fileOf(square))
                assertEquals(rank, rankOf(square))
            }
        }
    }

    @Test
    fun squareOfRejectsOutOfRangeFileOrRank() {
        assertFailsWith<IllegalArgumentException> { squareOf(-1, 0) }
        assertFailsWith<IllegalArgumentException> { squareOf(8, 0) }
        assertFailsWith<IllegalArgumentException> { squareOf(0, -1) }
        assertFailsWith<IllegalArgumentException> { squareOf(0, 8) }
    }

    @Test
    fun isValidSquareAcceptsExactlyTheBoardRange() {
        assertTrue(isValidSquare(0))
        assertTrue(isValidSquare(63))
        assertFalse(isValidSquare(-1))
        assertFalse(isValidSquare(64))
    }

    @Test
    fun offsetStaysOnBoardWithinBounds() {
        // e4 = (4, 3); one step north-east is f5 = (5, 4).
        assertEquals(squareOf(5, 4), offsetOrNull(squareOf(4, 3), 1, 1))
    }

    @Test
    fun offsetDoesNotWrapBetweenFiles() {
        // h4 + one file east must be null, not a4 on the next rank.
        assertNull(offsetOrNull(squareOf(7, 3), 1, 0))
        // a4 - one file west must be null, not h3 on the previous rank.
        assertNull(offsetOrNull(squareOf(0, 3), -1, 0))
    }

    @Test
    fun offsetReturnsNullPastTopAndBottomRanks() {
        assertNull(offsetOrNull(squareOf(4, 7), 0, 1))
        assertNull(offsetOrNull(squareOf(4, 0), 0, -1))
    }
}
