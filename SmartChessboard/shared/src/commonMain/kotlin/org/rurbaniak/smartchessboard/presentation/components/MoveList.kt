package org.rurbaniak.smartchessboard.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Numbered moves shared by Replay and Play. The move that produced [currentPly] is highlighted. When
 * [onJump] is non-null (Replay) each move is tappable and jumps to the position right after it
 * (`onJump(plyIndex + 1)`); when null (Play) the list is display-only — Play always sits at the live
 * position and has no scrubbing. [tableMode] picks the lichess-style two-column grid (white | black,
 * one full move per row) over the compact inline flow; the caller derives it from the persisted
 * preference + the list's container (see `effectiveMoveListMode`).
 */
@Composable
internal fun MoveList(
    sanMoves: List<String>,
    currentPly: Int,
    modifier: Modifier = Modifier,
    onJump: ((Int) -> Unit)? = null,
    tableMode: Boolean = false,
) {
    if (sanMoves.isEmpty()) return
    if (tableMode) {
        MoveListTable(sanMoves = sanMoves, currentPly = currentPly, modifier = modifier, onJump = onJump)
    } else {
        MoveListInline(sanMoves = sanMoves, currentPly = currentPly, modifier = modifier, onJump = onJump)
    }
}

/** Compact `1. e4 e5 2. Nf3 Nc6 …` flow — the default on phones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoveListInline(
    sanMoves: List<String>,
    currentPly: Int,
    modifier: Modifier,
    onJump: ((Int) -> Unit)?,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sanMoves.forEachIndexed { plyIndex, san ->
            if (plyIndex % 2 == 0) {
                Text(
                    "${plyIndex / 2 + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MoveText(
                san = san,
                plyIndex = plyIndex,
                currentPly = currentPly,
                onJump = onJump,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** Lichess-style two-column grid: move number, white move, black move — one full move per row. */
@Composable
private fun MoveListTable(
    sanMoves: List<String>,
    currentPly: Int,
    modifier: Modifier,
    onJump: ((Int) -> Unit)?,
) {
    val fullMoves = (sanMoves.size + 1) / 2
    Column(modifier = modifier) {
        for (move in 0 until fullMoves) {
            val whitePly = move * 2
            val blackPly = move * 2 + 1
            val zebra =
                if (move % 2 == 0) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            Row(
                modifier = Modifier.fillMaxWidth().background(zebra),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${move + 1}.",
                    modifier = Modifier.width(36.dp).padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MoveText(
                    san = sanMoves[whitePly],
                    plyIndex = whitePly,
                    currentPly = currentPly,
                    onJump = onJump,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                )
                if (blackPly < sanMoves.size) {
                    MoveText(
                        san = sanMoves[blackPly],
                        plyIndex = blackPly,
                        currentPly = currentPly,
                        onJump = onJump,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** One move cell: highlighted when it produced [currentPly]; tappable (jump) when [onJump] is non-null. */
@Composable
private fun MoveText(
    san: String,
    plyIndex: Int,
    currentPly: Int,
    onJump: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val isCurrent = plyIndex + 1 == currentPly
    Text(
        text = san,
        modifier =
            modifier.let { base -> if (onJump != null) base.clickable { onJump(plyIndex + 1) } else base },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
        color =
            if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
    )
}
