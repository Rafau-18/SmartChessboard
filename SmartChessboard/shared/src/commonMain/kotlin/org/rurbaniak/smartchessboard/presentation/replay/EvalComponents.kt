package org.rurbaniak.smartchessboard.presentation.replay

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.rurbaniak.smartchessboard.domain.chess.GameStatus
import org.rurbaniak.smartchessboard.presentation.theme.LocalChessColors
import kotlin.math.abs

/** Fixed bar thickness — wide enough for a `±d.dd` / `M#` label at [MaterialTheme.typography]'s 11 sp. */
private val EvalBarWidth = 32.dp

/**
 * The bar's held display: the last shown White-POV fill [fraction] (0..1, from the bottom) and the
 * [score] string drawn inside the bar. Advanced only by a resolved [PlyEvalState.Evaluated]
 * (see [evalBarDisplay]), so the bar never snaps to centre while the next position's eval loads.
 */
internal data class EvalBarDisplay(
    val fraction: Float,
    val score: String,
) {
    companion object {
        /** Centred bar, em-dash score — shown until the first evaluation lands (or after a reset). */
        val Neutral = EvalBarDisplay(fraction = 0.5f, score = "—")
    }
}

/**
 * Hold-last: only an [PlyEvalState.Evaluated] advances the shown fraction/score. Every other state
 * (Loading, null, NoEval, Unavailable, Terminal) keeps [last], so stepping to a ply whose eval is
 * still fetching holds the previous position's bar instead of resetting to the centre default.
 */
internal fun evalBarDisplay(
    eval: PlyEvalState?,
    last: EvalBarDisplay,
): EvalBarDisplay =
    when (eval) {
        is PlyEvalState.Evaluated -> {
            EvalBarDisplay(
                fraction = whiteBarFraction(eval.evalCp, eval.mate),
                score = formatEvalScore(eval.evalCp, eval.mate),
            )
        }

        else -> {
            last
        }
    }

/**
 * At-a-glance advantage bar, vertical alongside the board: White-POV fill from the bottom (the
 * White side of the board). Clamped ±1000 cp linear; a forced mate pins the bar to the winning
 * side. Holds the last resolved evaluation while the next one loads (no snap to centre), animates
 * the fill to each new value, and draws a fixed numeric label at the bottom-centre anchor — the
 * label never tracks the moving fill boundary. The caller provides the height (typically matching
 * the board); width is fixed here. `remember` resets to neutral when the bar leaves composition
 * (game/screen change, analysis toggled off).
 */
@Composable
internal fun EvalBar(
    eval: PlyEvalState?,
    modifier: Modifier = Modifier,
) {
    val chess = LocalChessColors.current

    // Hold-last: render the value computed this frame and persist it so a later Loading/absent ply
    // holds it. The write only fires on an Evaluated transition (otherwise display == lastShown), so
    // it converges in one recomposition.
    var lastShown by remember { mutableStateOf(EvalBarDisplay.Neutral) }
    val display = evalBarDisplay(eval, lastShown)
    if (display != lastShown) {
        lastShown = display
    }

    val animatedFraction by animateFloatAsState(
        targetValue = display.fraction,
        animationSpec = tween(durationMillis = 300),
        label = "evalBarFraction",
    )

    // Loading affordance: while the viewed ply's eval is still fetching, the held label pulses to
    // signal "this is the previous position's evaluation."
    val loading = eval == null || eval is PlyEvalState.Loading
    val pulse = rememberInfiniteTransition(label = "evalBarPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 750), repeatMode = RepeatMode.Reverse),
        label = "evalBarPulseAlpha",
    )
    val labelAlpha = if (loading) pulseAlpha else 1f

    // The label sits over the light "white-advantage" fill for essentially the whole range, so keep
    // it dark. The exception is a strong black advantage / forced mate for Black, where the fill has
    // receded below the label and the dark track shows through — use the light fill colour there.
    val labelColor = if (animatedFraction <= 0.08f) chess.evalBarFill else chess.evalBarLabel

    Box(modifier = modifier.width(EvalBarWidth)) {
        // Track + fill: rounded and clipped. The label overlays this on the (unclipped) parent Box so
        // it is never clipped by the rounded corners.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp))
                    .background(chess.evalBarTrack),
        ) {
            if (animatedFraction > 0f) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(animatedFraction)
                            .background(chess.evalBarFill),
                )
            }
        }
        Text(
            text = display.score,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .alpha(labelAlpha),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** White's share of the bar: 0.5 + cp/2000 clamped to ±1000 cp; forced mate = full bar. */
internal fun whiteBarFraction(
    evalCp: Int?,
    mate: Int?,
): Float =
    when {
        mate != null -> if (mate > 0) 1f else 0f
        evalCp != null -> 0.5f + evalCp.coerceIn(-1000, 1000) / 2000f
        else -> 0.5f
    }

/**
 * Score in pawns (`+0.22`) or mate distance (`M3` / `-M2`, White-POV sign). Mate wins over cp
 * when both are present; em dash when neither is.
 */
internal fun formatEvalScore(
    evalCp: Int?,
    mate: Int?,
): String =
    when {
        mate != null -> {
            if (mate < 0) "-M${-mate}" else "M$mate"
        }

        evalCp != null -> {
            val sign =
                if (evalCp > 0) {
                    "+"
                } else if (evalCp < 0) {
                    "-"
                } else {
                    ""
                }
            val magnitude = abs(evalCp)
            "$sign${magnitude / 100}.${(magnitude % 100).toString().padStart(2, '0')}"
        }

        else -> {
            "—"
        }
    }

/** `"e2e4"` → `"e2→e4"`; a promotion suffix is dropped. Null for a malformed UCI string. */
internal fun formatBestMove(uci: String): String? {
    if (uci.length < 4) return null
    return "${uci.substring(0, 2)}→${uci.substring(2, 4)}"
}

/**
 * The precision half of the analysis display: score + best move when evaluated, and a distinct
 * affordance per non-evaluated state — inline progress, a stable "no evaluation" answer (no
 * retry), a retryable outage, or the local terminal label. Null [eval] renders as Loading: the
 * toggle fires the fetch in the same frame, so null is only ever a transient.
 */
@Composable
internal fun EvalPanel(
    eval: PlyEvalState?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            when (eval) {
                null, PlyEvalState.Loading -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Analyzing…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is PlyEvalState.Evaluated -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            formatEvalScore(eval.evalCp, eval.mate),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        eval.bestMoveUci?.let(::formatBestMove)?.let { move ->
                            Text("Best: $move", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            listOfNotNull(
                                eval.source,
                                eval.depth?.let { "depth $it" },
                                "cached".takeIf { eval.cached },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                PlyEvalState.NoEval -> {
                    Text("No evaluation for this position", style = MaterialTheme.typography.bodyMedium)
                }

                is PlyEvalState.Unavailable -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Analysis temporarily unavailable", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onRetry) {
                            Text("Retry")
                        }
                    }
                }

                is PlyEvalState.Terminal -> {
                    Text(
                        if (eval.status == GameStatus.Checkmate) "Checkmate" else "Stalemate",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
