package org.rurbaniak.smartchessboard.domain.preferences

import kotlin.test.Test
import kotlin.test.assertEquals

class MoveListModeTest {
    @Test
    fun nullOverrideDefaultsToTableInSidePanel() {
        assertEquals(MoveListMode.TABLE, effectiveMoveListMode(override = null, inSidePanel = true))
    }

    @Test
    fun nullOverrideDefaultsToInlineInColumn() {
        assertEquals(MoveListMode.INLINE, effectiveMoveListMode(override = null, inSidePanel = false))
    }

    @Test
    fun explicitOverrideWinsInBothContainers() {
        assertEquals(MoveListMode.INLINE, effectiveMoveListMode(override = MoveListMode.INLINE, inSidePanel = true))
        assertEquals(MoveListMode.TABLE, effectiveMoveListMode(override = MoveListMode.TABLE, inSidePanel = false))
    }
}
