package org.rurbaniak.smartchessboard.presentation.board

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the diagnostics grid's bit math — especially the h8 corner (square 63 is the sign bit), the
 * documented occupancy footgun (`Occupancy.kt`). Headless, so it proves the math on every target
 * (a signed `> 0` regression would misread exactly h8 and never surface on the JVM host alone).
 * Also covers `diagnosticsGridSide`, the pure half of the grid's height-bounding.
 */
class ReedDiagnosticsGridTest {
    @Test
    fun `isOccupied reads a1 bit 0 and the h8 sign bit`() {
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

    @Test
    fun `grid side is width-bound with an unknown or generous height budget`() {
        // Early frame / preview: no window size yet → width-bound, like boardSide().
        assertEquals(328.dp, diagnosticsGridSide(availableWidth = 328.dp, heightBudget = Dp.Unspecified))
        // Tall portrait viewport: the width is the smaller bound.
        assertEquals(328.dp, diagnosticsGridSide(availableWidth = 328.dp, heightBudget = 600.dp))
    }

    @Test
    fun `grid side is height-bound in a short window`() {
        // Landscape-phone side panel (393-high window less the pane chrome): budget below width wins.
        assertEquals(213.dp, diagnosticsGridSide(availableWidth = 340.dp, heightBudget = 213.dp))
        // Portrait split-screen: the same bound applies in a scrolling column.
        assertEquals(260.dp, diagnosticsGridSide(availableWidth = 328.dp, heightBudget = 260.dp))
    }

    @Test
    fun `grid side floors at the readability minimum but never above the width`() {
        assertEquals(120.dp, diagnosticsGridSide(availableWidth = 340.dp, heightBudget = 80.dp))
        // A negative budget (viewport smaller than the chrome estimate) still resolves to the floor.
        assertEquals(120.dp, diagnosticsGridSide(availableWidth = 340.dp, heightBudget = (-20).dp))
        // The floor yields to a slot narrower than itself instead of overflowing it.
        assertEquals(100.dp, diagnosticsGridSide(availableWidth = 100.dp, heightBudget = 80.dp))
    }
}
