package org.rurbaniak.smartchessboard.presentation.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.rurbaniak.smartchessboard.presentation.theme.LocalChessColors
import org.rurbaniak.smartchessboard.domain.chess.Color as PieceColor

/**
 * A live 8×8 reed-switch readout (S-07, FR-011) that highlights which squares disagree with the
 * expected position, so a player recovering from a paused game can see exactly which fields to fix.
 *
 * Separate from [ChessBoardView] on purpose: that view is coupled to a [org.rurbaniak.smartchessboard.domain.chess.Position]
 * and renders pieces, whereas a reed board senses only occupancy — a 64-bit bitfield with no piece
 * identity. [observed] is the board's current occupancy (typically `state.latestOccupancy`), [expected]
 * is the position the board should match (`positions.last().toOccupancy()`); a square is tinted when
 * the two disagree there, and `null` [expected] tints nothing (no snapshot to compare yet).
 *
 * Geometry is shared with the main board via [squareAt]/[isDarkSquare], so the grid sits under
 * [ChessBoardView] in the same orientation and the player maps physical squares 1:1. Occupancy and
 * diff are read with the h8-safe bit test (square 63 is the sign bit — see [isOccupied]). Display-only.
 */
@Composable
fun ReedDiagnosticsGrid(
    observed: Long,
    expected: Long?,
    modifier: Modifier = Modifier,
    orientation: PieceColor = PieceColor.WHITE,
) {
    Box(modifier = modifier.aspectRatio(1f)) {
        Column(modifier = Modifier.fillMaxSize()) {
            for (rowFromTop in 0..7) {
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    for (column in 0..7) {
                        val square = squareAt(column, rowFromTop, orientation)
                        DiagnosticsCell(
                            occupied = isOccupied(observed, square),
                            differs = occupancyDiffers(observed, expected, square),
                            dark = isDarkSquare(square),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One reed cell: a neutral checker background, tinted to the error container when it disagrees with
 * the expected position, with a centered dot when a magnet is currently sensed. The four readings:
 * tinted + dot = an unexpected piece; tinted, no dot = a missing piece; plain + dot = a piece in its
 * right place; plain, no dot = an empty square that should be empty.
 */
@Composable
private fun DiagnosticsCell(
    occupied: Boolean,
    differs: Boolean,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val chess = LocalChessColors.current
    // A diagnostics view is deliberately *not* the wood board — neutral cells (mode-aware tokens) so
    // the reed dots and the mismatch tint read as a sensor readout, not a game position.
    val background =
        when {
            differs -> MaterialTheme.colorScheme.errorContainer
            dark -> chess.diagDarkCell
            else -> chess.diagLightCell
        }
    Box(
        modifier = modifier.background(background).padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (occupied) {
            val dotColor =
                if (differs) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier =
                    Modifier
                        .fillMaxSize(0.5f)
                        .clip(CircleShape)
                        .background(dotColor),
            )
        }
    }
}

/**
 * Whether [square] (0..63) is occupied in the [bits] occupancy bitfield, using the canonical a1 = bit 0
 * convention. Square 63 (h8) is the sign bit (`1L shl 63` == `Long.MIN_VALUE`), so the test is the
 * h8-safe `(bits and (1L shl n)) != 0L` — never a signed `> 0`, which silently misreads exactly h8
 * (matches [org.rurbaniak.smartchessboard.domain.board.BoardEvent.BoardSnapshot.isOccupied] semantics).
 */
internal fun isOccupied(
    bits: Long,
    square: Int,
): Boolean = (bits and (1L shl square)) != 0L

/**
 * Whether [square]'s occupancy in [observed] disagrees with [expected]; always `false` when [expected]
 * is `null` (no position to compare against yet). The XOR isolates the differing bit and is tested
 * h8-safe (`!= 0L`), so the corner sign-bit square is highlighted correctly.
 */
internal fun occupancyDiffers(
    observed: Long,
    expected: Long?,
    square: Int,
): Boolean = expected != null && ((observed xor expected) and (1L shl square)) != 0L
