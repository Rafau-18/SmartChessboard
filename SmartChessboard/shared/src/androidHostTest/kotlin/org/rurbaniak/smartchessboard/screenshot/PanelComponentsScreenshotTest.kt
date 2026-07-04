package org.rurbaniak.smartchessboard.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.rurbaniak.smartchessboard.domain.chess.pgn.PgnFixtures
import org.rurbaniak.smartchessboard.domain.chess.pgn.parsePgn
import org.rurbaniak.smartchessboard.presentation.components.MoveList
import org.rurbaniak.smartchessboard.presentation.components.SyncIndicator
import org.rurbaniak.smartchessboard.presentation.replay.EvalBar
import org.rurbaniak.smartchessboard.presentation.replay.EvalPanel
import org.rurbaniak.smartchessboard.presentation.replay.PlyEvalState

private val EVAL_SHOT = DpSize(360.dp, 176.dp)
private val MOVELIST_SHOT = DpSize(320.dp, 360.dp)
private val SYNC_SHOT = DpSize(240.dp, 36.dp)

/**
 * Freezes the analysis/game-panel components: the EvalBar + EvalPanel pair in its three resolved
 * shapes (even, White advantage, forced mate), MoveList in both modes over the same 33-ply Opera
 * fixture (long enough to exercise inline wrapping and the table's zebra rows against the fixed
 * shot height), and the SyncIndicator's two slot states (hint visible after its show delay via the
 * test clock; idle = the reserved empty slot, the no-jump invariant made visible).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xhdpi")
class PanelComponentsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    // --- EvalComponents -------------------------------------------------------------------------

    private val evenEval =
        PlyEvalState.Evaluated(evalCp = 0, mate = null, bestMoveUci = "e2e4", source = "lichess", depth = 40)
    private val whiteAdvantageEval =
        PlyEvalState.Evaluated(evalCp = 235, mate = null, bestMoveUci = "d1h5", source = "chess-api", depth = 18)
    private val mateEval =
        PlyEvalState.Evaluated(
            evalCp = null,
            mate = 3,
            bestMoveUci = "d1d8",
            source = "lichess",
            depth = 99,
            cached = true,
        )

    private fun evalGolden(
        name: String,
        dark: Boolean,
        eval: PlyEvalState,
    ) {
        compose.golden(name = name, dark = dark, size = EVAL_SHOT) {
            EvalPair(eval)
        }
    }

    @Test
    fun evalEvenLight() = evalGolden("eval_even_light", dark = false, eval = evenEval)

    @Test
    fun evalEvenDark() = evalGolden("eval_even_dark", dark = true, eval = evenEval)

    @Test
    fun evalWhiteAdvantageLight() = evalGolden("eval_white_advantage_light", dark = false, eval = whiteAdvantageEval)

    @Test
    fun evalWhiteAdvantageDark() = evalGolden("eval_white_advantage_dark", dark = true, eval = whiteAdvantageEval)

    @Test
    fun evalMateLight() = evalGolden("eval_mate_light", dark = false, eval = mateEval)

    @Test
    fun evalMateDark() = evalGolden("eval_mate_dark", dark = true, eval = mateEval)

    // --- MoveList -------------------------------------------------------------------------------

    private fun moveListGolden(
        name: String,
        dark: Boolean,
        tableMode: Boolean,
    ) {
        val sanMoves = parsePgn(PgnFixtures.OPERA_GAME).sanMoves
        compose.golden(name = name, dark = dark, size = MOVELIST_SHOT) {
            MoveList(
                sanMoves = sanMoves,
                currentPly = 17,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                tableMode = tableMode,
            )
        }
    }

    @Test
    fun moveListInlineLight() = moveListGolden("movelist_inline_light", dark = false, tableMode = false)

    @Test
    fun moveListInlineDark() = moveListGolden("movelist_inline_dark", dark = true, tableMode = false)

    @Test
    fun moveListTableLight() = moveListGolden("movelist_table_light", dark = false, tableMode = true)

    @Test
    fun moveListTableDark() = moveListGolden("movelist_table_dark", dark = true, tableMode = true)

    // --- SyncIndicator --------------------------------------------------------------------------

    private fun syncGolden(
        name: String,
        dark: Boolean,
        pending: Boolean,
    ) {
        compose.golden(
            name = name,
            dark = dark,
            size = SYNC_SHOT,
            // The hint shows only after 600 ms of continuous pending — push the test clock past the
            // delay plus the fade-in so the pending shot is the settled visible state.
            prepare = { if (pending) mainClock.advanceTimeBy(2_000) },
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                SyncIndicator(syncPending = pending)
            }
        }
    }

    @Test
    fun syncSavingLight() = syncGolden("sync_saving_light", dark = false, pending = true)

    @Test
    fun syncSavingDark() = syncGolden("sync_saving_dark", dark = true, pending = true)

    @Test
    fun syncIdleLight() = syncGolden("sync_idle_light", dark = false, pending = false)

    @Test
    fun syncIdleDark() = syncGolden("sync_idle_dark", dark = true, pending = false)
}

/** The Replay analysis pair as composed on screen: vertical bar beside the precision panel. */
@Composable
private fun EvalPair(eval: PlyEvalState) {
    Row(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        EvalBar(eval = eval, modifier = Modifier.fillMaxHeight())
        Spacer(modifier = Modifier.width(12.dp))
        EvalPanel(eval = eval, onRetry = {}, modifier = Modifier.fillMaxWidth())
    }
}
