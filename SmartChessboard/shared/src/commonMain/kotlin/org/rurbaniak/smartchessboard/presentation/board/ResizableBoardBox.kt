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
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val viewportHeightPx = LocalWindowInfo.current.containerSize.height
        val viewportHeight = if (viewportHeightPx > 0) with(density) { viewportHeightPx.toDp() } else Dp.Unspecified
        val side = boardSide(isWide = isWide, availableWidth = maxWidth, viewportHeight = viewportHeight, size = size)
        val availableWidthPx = constraints.maxWidth.toFloat()

        // The board square is centred; the handle pins to its bottom-end corner (the content's
        // corner — for Replay with the eval bar visible that is the bar's corner, which is acceptable).
        Box(
            modifier = Modifier.align(Alignment.TopCenter),
            contentAlignment = Alignment.BottomEnd,
        ) {
            content(Modifier.size(side))
            if (isWide && availableWidthPx > 0f) {
                ResizeHandle(
                    sizeFraction = size,
                    availableWidthPx = availableWidthPx,
                    onSizeChange = onSizeChange,
                )
            }
        }
    }
}

/**
 * The square board side: bounded by width (scaled by [size] only on a wide screen) and by the
 * viewport height (less [VERTICAL_CHROME]), floored at [MIN_BOARD_SIDE] (but never above the pane
 * width). [viewportHeight] is [Dp.Unspecified] when the window size isn't known yet (early frame /
 * preview), in which case only the width bound applies.
 */
private fun boardSide(
    isWide: Boolean,
    availableWidth: Dp,
    viewportHeight: Dp,
    size: Float,
): Dp {
    val widthBound = if (isWide) availableWidth * clampBoardSize(size) else availableWidth
    val bounded =
        if (viewportHeight != Dp.Unspecified) {
            minOf(widthBound, (viewportHeight - VERTICAL_CHROME).coerceAtLeast(MIN_BOARD_SIDE))
        } else {
            widthBound
        }
    return bounded.coerceAtLeast(minOf(availableWidth, MIN_BOARD_SIDE))
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
