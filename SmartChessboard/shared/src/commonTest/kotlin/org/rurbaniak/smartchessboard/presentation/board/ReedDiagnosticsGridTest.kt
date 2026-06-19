package org.rurbaniak.smartchessboard.presentation.board

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the diagnostics grid's bit math — especially the h8 corner (square 63 is the sign bit), the
 * documented occupancy footgun (`Occupancy.kt`). Headless, so it proves the math on every target
 * (a signed `> 0` regression would misread exactly h8 and never surface on the JVM host alone).
 */
class ReedDiagnosticsGridTest {
    @Test
    fun `isOccupied reads a1 (bit 0) and h8 (bit 63, the sign bit)`() {
        assertTrue(isOccupied(1L shl 0, square = 0)) // a1 occupied
        assertFalse(isOccupied(1L shl 0, square = 1))
        // 1L shl 63 == Long.MIN_VALUE; a signed comparison would call this "empty".
        assertTrue(isOccupied(1L shl 63, square = 63)) // h8 occupied
        assertFalse(isOccupied(1L shl 63, square = 62))
        assertTrue(isOccupied(-1L, square = 63)) // an all-occupied board includes h8
        assertFalse(isOccupied(0L, square = 63))
    }

    @Test
    fun `occupancyDiffers is null-safe and isolates the differing square`() {
        // No expected position yet (before the first snapshot) → highlight nothing.
        assertFalse(occupancyDiffers(observed = 0L, expected = null, square = 0))
        assertFalse(occupancyDiffers(observed = 1L shl 63, expected = null, square = 63))
        // Identical boards differ nowhere, h8 included.
        assertFalse(occupancyDiffers(observed = -1L, expected = -1L, square = 63))
        // A single-square mismatch on h8 is detected; an agreeing square is not.
        assertTrue(occupancyDiffers(observed = 1L shl 63, expected = 0L, square = 63))
        assertFalse(occupancyDiffers(observed = 1L shl 63, expected = 0L, square = 0))
        // A single-square mismatch on a1.
        assertTrue(occupancyDiffers(observed = 1L, expected = 0L, square = 0))
        assertFalse(occupancyDiffers(observed = 1L, expected = 0L, square = 63))
    }
}
