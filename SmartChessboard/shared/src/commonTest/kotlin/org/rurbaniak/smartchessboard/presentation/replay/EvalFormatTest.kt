package org.rurbaniak.smartchessboard.presentation.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    fun formatsBestMoveAsHyphenatedLongAlgebraic() {
        // A hyphen, not an arrow — the WasmJS font has no `→` glyph (renders as tofu on web).
        assertEquals("e2-e4", formatBestMove("e2e4"))
        assertEquals("e7-e8", formatBestMove("e7e8q"))
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

    @Test
    fun neutralDisplayIsCentredDash() {
        assertEquals(0.5f, EvalBarDisplay.Neutral.fraction)
        assertEquals("—", EvalBarDisplay.Neutral.score)
    }

    @Test
    fun evaluatedAdvancesDisplayFractionAndScore() {
        val display =
            evalBarDisplay(
                eval = evaluated(evalCp = 120, mate = null),
                last = EvalBarDisplay.Neutral,
            )
        assertEquals(whiteBarFraction(120, null), display.fraction)
        assertEquals("+1.20", display.score)
    }

    @Test
    fun evaluatedAdvancesToForcedMate() {
        val display =
            evalBarDisplay(
                eval = evaluated(evalCp = null, mate = 3),
                last = EvalBarDisplay(fraction = 0.25f, score = "-1.00"),
            )
        assertEquals(1f, display.fraction)
        assertEquals("M3", display.score)
    }

    @Test
    fun loadingHoldsPriorDisplayInsteadOfSnappingToCentre() {
        val prior = evalBarDisplay(eval = evaluated(evalCp = 500, mate = null), last = EvalBarDisplay.Neutral)
        val held = evalBarDisplay(eval = PlyEvalState.Loading, last = prior)
        assertEquals(prior, held)
        assertNotEquals(EvalBarDisplay.Neutral.fraction, held.fraction)
    }

    @Test
    fun absentAndNonResolvedStatesHoldPriorDisplay() {
        val prior = EvalBarDisplay(fraction = 0.75f, score = "+0.50")
        assertEquals(prior, evalBarDisplay(eval = null, last = prior))
        assertEquals(prior, evalBarDisplay(eval = PlyEvalState.NoEval, last = prior))
        assertEquals(prior, evalBarDisplay(eval = PlyEvalState.Unavailable(retryAfterSeconds = null), last = prior))
    }

    private fun evaluated(
        evalCp: Int?,
        mate: Int?,
    ) = PlyEvalState.Evaluated(
        evalCp = evalCp,
        mate = mate,
        bestMoveUci = null,
        source = "test",
        depth = null,
    )
}
