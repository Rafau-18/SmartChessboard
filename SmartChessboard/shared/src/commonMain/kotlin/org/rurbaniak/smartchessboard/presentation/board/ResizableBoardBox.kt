package org.rurbaniak.smartchessboard.presentation.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.rurbaniak.smartchessboard.domain.preferences.clampBoardSize

/** At and above this window width a board screen counts as "wide" — it auto-fits and gets a resize handle. */
val WIDE_SCREEN_MIN_WIDTH = 840.dp

/** Vertical room reserved for chrome above/below the board (top bar, status line, controls) when height-bounding. */
private val VERTICAL_CHROME = 140.dp

/** The board never shrinks below this, even on a very short window, so it stays usable. */
private val MIN_BOARD_SIDE = 200.dp

/** Absolute cap on the board side, so on a very large monitor it stays a natural size instead of huge. */
private val BOARD_MAX_SIDE = 640.dp

/** The corner drag handle's touch target. */
private val HANDLE_SIZE = 28.dp

/**
 * True when the current window is wide enough to auto-fit + resize the board. Reads the window size
 * directly (not the local layout constraints) so Play/PhysicalPlay — which have no breakpoint
 * `BoxWithConstraints` of their own — share Replay's two-pane threshold.
 */
@Composable
fun rememberIsWideScreen(): Boolean {
    val widthPx = LocalWindowInfo.current.containerSize.width
    val width = with(LocalDensity.current) { widthPx.toDp() }
    return width >= WIDE_SCREEN_MIN_WIDTH
}

/**
 * Sizes a chessboard consistently across the board screens. On a phone ([isWide] = false) the board
 * is full-width auto-fit (bounded by the viewport height); on a wide screen it is
 * `min(availableWidth × size, viewportHeight)` with a corner drag handle that updates the persisted
 * [size] (a fraction of the pane width). [content] receives a square `Modifier` to apply to the board.
 *
 * The board lives inside a vertically scrolling column, so its incoming `maxHeight` is unbounded —
 * the height bound therefore comes from [LocalWindowInfo]'s container size, not the layout
 * constraints, so the board fits the viewport instead of forcing a scroll.
 */
@Composable
fun ResizableBoardBox(
    isWide: Boolean,
    size: Float,
    onSizeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    reservedWidth: Dp = 0.dp,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val viewportHeightPx = LocalWindowInfo.current.containerSize.height
        val viewportHeight = if (viewportHeightPx > 0) with(density) { viewportHeightPx.toDp() } else Dp.Unspecified
        val side =
            boardSide(
                isWide = isWide,
                availableWidth = maxWidth,
                reservedWidth = reservedWidth,
                viewportHeight = viewportHeight,
                size = size,
            )
        // Drag maps to the board's own width budget (pane minus the reserved adjacent element), so the
        // grip tracks the cursor even when an eval bar sits beside the board.
        val budgetWidthPx = (constraints.maxWidth.toFloat() - with(density) { reservedWidth.toPx() }).coerceAtLeast(1f)

        Box(modifier = Modifier.align(Alignment.TopCenter)) {
            content(Modifier.size(side))
            if (isWide) {
                // Overlay exactly the board's size so the handle pins to the *board's* bottom-end corner
                // — with an adjacent eval bar it no longer lands on the bar (and its numeric label).
                Box(modifier = Modifier.size(side), contentAlignment = Alignment.BottomEnd) {
                    ResizeHandle(
                        sizeFraction = size,
                        availableWidthPx = budgetWidthPx,
                        onSizeChange = onSizeChange,
                    )
                }
            }
        }
    }
}

/**
 * The square board side: bounded by the usable width (the pane less [reservedWidth] for an adjacent
 * element such as the eval bar, scaled by [size] only on a wide screen), by an absolute [BOARD_MAX_SIDE]
 * cap, and by the viewport height (less [VERTICAL_CHROME]); floored at [MIN_BOARD_SIDE] (but never above
 * the usable width). [viewportHeight] is [Dp.Unspecified] when the window size isn't known yet (early
 * frame / preview), in which case only the width/cap bounds apply.
 */
private fun boardSide(
    isWide: Boolean,
    availableWidth: Dp,
    reservedWidth: Dp,
    viewportHeight: Dp,
    size: Float,
): Dp {
    val usableWidth = (availableWidth - reservedWidth).coerceAtLeast(0.dp)
    val widthBound = if (isWide) usableWidth * clampBoardSize(size) else usableWidth
    val capped = minOf(widthBound, BOARD_MAX_SIDE)
    val bounded =
        if (viewportHeight != Dp.Unspecified) {
            minOf(capped, (viewportHeight - VERTICAL_CHROME).coerceAtLeast(MIN_BOARD_SIDE))
        } else {
            capped
        }
    return bounded.coerceAtLeast(minOf(usableWidth, MIN_BOARD_SIDE))
}

/**
 * A corner grip the user drags to resize the board. A horizontal drag of `dx` px changes the stored
 * fraction by `dx / paneWidth`, so dragging right by N px widens the board by ≈N px. The current
 * [sizeFraction] / [availableWidthPx] / [onSizeChange] are read through [rememberUpdatedState] so the
 * gesture always sees the latest values without restarting on every recomposition.
 */
@Composable
private fun ResizeHandle(
    sizeFraction: Float,
    availableWidthPx: Float,
    onSizeChange: (Float) -> Unit,
) {
    val currentSize by rememberUpdatedState(sizeFraction)
    val widthPx by rememberUpdatedState(availableWidthPx)
    val onChange by rememberUpdatedState(onSizeChange)
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier =
            Modifier
                .padding(2.dp)
                .size(HANDLE_SIZE)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onChange(clampBoardSize(currentSize + dragAmount.x / widthPx))
                    }
                },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            val pad = 3.dp.toPx()
            // Two short diagonal grip lines tucked into the bottom-right corner.
            for (fraction in listOf(0.45f, 0.72f)) {
                drawLine(
                    color = color,
                    start = Offset(size.width * fraction, size.height - pad),
                    end = Offset(size.width - pad, size.height * fraction),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
