package org.rurbaniak.smartchessboard.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Numbered move pairs shared by Replay and Play. The move that produced [currentPly] is
 * highlighted. When [onJump] is non-null (Replay) each move is tappable and jumps to the position
 * right after it (`onJump(plyIndex + 1)`); when null (Play) the list is display-only — Play always
 * sits at the live position and has no scrubbing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoveList(
    sanMoves: List<String>,
    currentPly: Int,
    modifier: Modifier = Modifier,
    onJump: ((Int) -> Unit)? = null,
) {
    if (sanMoves.isEmpty()) return
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
            val isCurrent = plyIndex + 1 == currentPly
            Text(
                text = san,
                modifier =
                    Modifier
                        .let { base -> if (onJump != null) base.clickable { onJump(plyIndex + 1) } else base }
                        .padding(horizontal = 4.dp),
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
    }
}
