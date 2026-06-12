package org.rurbaniak.smartchessboard.presentation.replay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.rurbaniak.smartchessboard.domain.chess.GameStatus
import kotlin.math.abs

private val BAR_WHITE = Color(0xFFF5F5F5)
private val BAR_BLACK = Color(0xFF424242)

/**
 * At-a-glance advantage bar, vertical alongside the board: White-POV fill from the bottom (the
 * White side of the board). Clamped ±1000 cp linear; a forced mate pins the bar to the winning
 * side. Neutral (50/50) until an evaluation is present. The caller provides the height (typically
 * matching the board); width is fixed here.
 */
@Composable
internal fun EvalBar(
    eval: PlyEvalState?,
    modifier: Modifier = Modifier,
) {
    val fraction =
        when (eval) {
            is PlyEvalState.Evaluated -> whiteBarFraction(eval.evalCp, eval.mate)
            else -> 0.5f
        }
    Box(
        modifier =
            modifier
                .width(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(BAR_BLACK),
    ) {
        if (fraction > 0f) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(fraction)
                        .background(BAR_WHITE),
            )
        }
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
