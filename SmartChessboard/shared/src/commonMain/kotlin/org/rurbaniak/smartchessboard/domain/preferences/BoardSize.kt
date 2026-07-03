package org.rurbaniak.smartchessboard.domain.preferences

/**
 * The resizable-board size, expressed as a **fraction of the available pane width** (lichess-style
 * resize). It only takes effect where `boardResizeEnabled(windowSizeClass)` holds — width-expanded
 * windows that are not height-compact (tablet / desktop / wide web). Everywhere else — portrait
 * phones and any height-compact window such as a landscape phone — the board renders auto-fit and
 * ignores the stored value. The fraction lives in [BOARD_SIZE_MIN]..[BOARD_SIZE_MAX]; an unset or
 * out-of-range stored value resolves to [BOARD_SIZE_DEFAULT].
 *
 * These constants and [clampBoardSize] live in `domain/` (not in the presentation `ResizableBoardBox`)
 * so both the data layer's total-reads guarantee and the UI's drag clamp share one definition — the
 * presentation layer must not be a dependency of the data layer.
 */
const val BOARD_SIZE_MIN: Float = 0.4f
const val BOARD_SIZE_MAX: Float = 1.0f

/**
 * Default fraction of the pane width on a wide screen. Deliberately below 1.0 so the board doesn't
 * fill the whole pane out of the box (which looked stretched); an absolute cap also applies in the UI.
 */
const val BOARD_SIZE_DEFAULT: Float = 0.6f

/**
 * Clamp a raw board-size fraction into [min]..[max]. A non-finite [raw] (NaN / ±Infinity — e.g. a
 * corrupt stored value or a divide-by-zero in the drag math) resolves to [min] so reads stay total.
 */
fun clampBoardSize(
    raw: Float,
    min: Float = BOARD_SIZE_MIN,
    max: Float = BOARD_SIZE_MAX,
): Float = if (raw.isNaN() || raw.isInfinite()) min else raw.coerceIn(min, max)
