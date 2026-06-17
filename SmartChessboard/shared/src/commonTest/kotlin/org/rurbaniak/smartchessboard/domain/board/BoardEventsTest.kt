package org.rurbaniak.smartchessboard.domain.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Construction guards on the public board event types (the seam S-06 consumes and S-09 re-implements
// over BLE). The square index is the one field a hand-constructing consumer can get wrong, so both
// SquareEvent and BoardSnapshot.isOccupied reject out-of-range indices at the domain boundary rather
// than letting a bad value reach the wire codec and silently corrupt a frame.
class BoardEventsTest {
    @Test
    fun squareEventAcceptsTheBoardCorners() {
        assertEquals(0, BoardEvent.SquareEvent(0, SquareEventType.LIFT).square)
        assertEquals(63, BoardEvent.SquareEvent(63, SquareEventType.PLACE).square)
    }

    @Test
    fun squareEventRejectsOutOfRangeSquares() {
        assertFailsWith<IllegalArgumentException> { BoardEvent.SquareEvent(-1, SquareEventType.LIFT) }
        assertFailsWith<IllegalArgumentException> { BoardEvent.SquareEvent(64, SquareEventType.PLACE) }
    }

    @Test
    fun boardSnapshotIsOccupiedRejectsOutOfRangeSquares() {
        val snapshot = BoardEvent.BoardSnapshot(0L)
        assertFailsWith<IllegalArgumentException> { snapshot.isOccupied(-1) }
        assertFailsWith<IllegalArgumentException> { snapshot.isOccupied(64) }
    }
}
