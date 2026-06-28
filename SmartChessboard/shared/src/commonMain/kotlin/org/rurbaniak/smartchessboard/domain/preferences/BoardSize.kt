package org.rurbaniak.smartchessboard.domain.preferences

/**
 * The wide-screen board size, expressed as a **fraction of the available pane width** (lichess-style
 * resize). It only takes effect on wide screens; phones always render the board full-width auto-fit
 * and ignore the stored value. The fraction lives in [BOARD_SIZE_MIN]..[BOARD_SIZE_MAX]; an unset or
 * out-of-range stored value resolves to [BOARD_SIZE_DEFAULT].
 *
 * These constants and [clampBoardSize] live in `domain/` (not in the presentation `ResizableBoardBox`)
 * so both the data layer's total-reads guarantee and the UI's drag clamp share one definition — the
 * presentation layer must not be a dependency of the data layer.
 */
const val BOARD_SIZE_MIN: Float = 0.4f
const val BOARD_SIZE_MAX: Float = 1.0f
const val BOARD_SIZE_DEFAULT: Float = 0.7f

/**
 * Clamp a raw board-size fraction into [min]..[max]. A non-finite [raw] (NaN / ±Infinity — e.g. a
 * corrupt stored value or a divide-by-zero in the drag math) resolves to [min] so reads stay total.
 */
fun clampBoardSize(
    raw: Float,
    min: Float = BOARD_SIZE_MIN,
    max: Float = BOARD_SIZE_MAX,
): Float = if (raw.isNaN() || raw.isInfinite()) min else raw.coerceIn(min, max)
