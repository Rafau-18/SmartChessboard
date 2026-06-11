package org.rurbaniak.smartchessboard.domain.chess

/**
 * A move attempt, expressed in square indices per the convention in Square.kt.
 *
 * A pawn move onto the last rank with [promoteTo] == null is an *incomplete* promotion and is
 * rejected by validation (FR-006, contract §1.5) — the engine never auto-promotes; the player
 * must choose the piece before the move is saved.
 */
data class Move(
    val from: Int,
    val to: Int,
    val promoteTo: PieceType? = null,
)

/** The engine's structured answer to applying a [Move] to a [Position]. */
sealed interface MoveOutcome {
    data class Legal(
        val position: Position,
    ) : MoveOutcome

    data class Illegal(
        val reason: IllegalReason,
    ) : MoveOutcome
}

/**
 * Only the two causes the legal-set-membership check can produce. Finer-grained rejection
 * reasons (not-your-piece, blocked, leaves-king-in-check, …) require a separate
 * classify-why-rejected pass and are deferred to S-07 diagnostics.
 */
enum class IllegalReason {
    /** A pawn move onto the last rank was attempted without choosing the promotion piece. */
    PROMOTION_PIECE_REQUIRED,

    /** The attempt is not in the position's legal move set. */
    NO_SUCH_MOVE,
}
