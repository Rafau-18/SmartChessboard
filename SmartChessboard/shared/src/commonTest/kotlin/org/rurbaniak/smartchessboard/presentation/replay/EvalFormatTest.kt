package org.rurbaniak.smartchessboard.presentation.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EvalFormatTest {
    @Test
    fun formatsCentipawnsAsSignedPawns() {
        assertEquals("+0.22", formatEvalScore(evalCp = 22, mate = null))
        assertEquals("-1.50", formatEvalScore(evalCp = -150, mate = null))
        assertEquals("0.00", formatEvalScore(evalCp = 0, mate = null))
        assertEquals("+12.34", formatEvalScore(evalCp = 1234, mate = null))
        assertEquals("-0.05", formatEvalScore(evalCp = -5, mate = null))
    }

    @Test
    fun formatsMateWithWhitePovSign() {
        assertEquals("M3", formatEvalScore(evalCp = null, mate = 3))
        assertEquals("-M2", formatEvalScore(evalCp = null, mate = -2))
    }

    @Test
    fun mateWinsOverCentipawnsWhenBothPresent() {
        assertEquals("M1", formatEvalScore(evalCp = 10000, mate = 1))
    }

    @Test
    fun missingScoreRendersDash() {
        assertEquals("—", formatEvalScore(evalCp = null, mate = null))
    }

    @Test
    fun formatsBestMoveAsArrowText() {
        assertEquals("e2→e4", formatBestMove("e2e4"))
        assertEquals("e7→e8", formatBestMove("e7e8q"))
        assertNull(formatBestMove("e2"))
    }

    @Test
    fun barFractionIsWhitePovClampedLinear() {
        assertEquals(0.5f, whiteBarFraction(evalCp = 0, mate = null))
        assertEquals(0.75f, whiteBarFraction(evalCp = 500, mate = null))
        assertEquals(1f, whiteBarFraction(evalCp = 2500, mate = null))
        assertEquals(0f, whiteBarFraction(evalCp = -2500, mate = null))
        assertEquals(1f, whiteBarFraction(evalCp = null, mate = 5))
        assertEquals(0f, whiteBarFraction(evalCp = null, mate = -1))
        assertEquals(0.5f, whiteBarFraction(evalCp = null, mate = null))
    }
}
