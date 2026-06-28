package org.rurbaniak.smartchessboard.domain.preferences

import kotlin.test.Test
import kotlin.test.assertEquals

class MoveListModeTest {
    @Test
    fun nullOverrideDefaultsToTableOnWide() {
        assertEquals(MoveListMode.TABLE, effectiveMoveListMode(override = null, isWide = true))
    }

    @Test
    fun nullOverrideDefaultsToInlineOnNarrow() {
        assertEquals(MoveListMode.INLINE, effectiveMoveListMode(override = null, isWide = false))
    }

    @Test
    fun explicitOverrideWinsOverScreenWidth() {
        assertEquals(MoveListMode.INLINE, effectiveMoveListMode(override = MoveListMode.INLINE, isWide = true))
        assertEquals(MoveListMode.TABLE, effectiveMoveListMode(override = MoveListMode.TABLE, isWide = false))
    }
}
