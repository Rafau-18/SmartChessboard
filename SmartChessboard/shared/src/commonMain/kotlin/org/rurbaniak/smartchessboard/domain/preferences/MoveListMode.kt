package org.rurbaniak.smartchessboard.domain.preferences

/**
 * How the move list is laid out. [INLINE] is the compact `1. e4 e5 2. Nf3 …` flow (good on phones);
 * [TABLE] is the lichess-style two-column grid (white | black, one full move per row) that reads
 * better on wide screens.
 */
enum class MoveListMode {
    INLINE,
    TABLE,
}

/**
 * The move-list layout to actually render: an explicit user [override] always wins; with no override
 * the default follows the screen ([isWide] → [MoveListMode.TABLE], else [MoveListMode.INLINE]). Pure
 * so it can be unit-tested on every target (Native engine differs from JVM — `lessons.md`).
 */
fun effectiveMoveListMode(
    override: MoveListMode?,
    isWide: Boolean,
): MoveListMode = override ?: if (isWide) MoveListMode.TABLE else MoveListMode.INLINE
