package org.rurbaniak.smartchessboard.domain.chess

/**
 * Terminal/ongoing classification of a position (FR-007: checkmate and stalemate only; draws by
 * rule are marked manually per FR-018). Returned by the status function added in a later phase;
 * the type is defined here so the public contract is complete up front.
 */
sealed interface GameStatus {
    data object Ongoing : GameStatus

    data object Check : GameStatus

    data object Checkmate : GameStatus

    data object Stalemate : GameStatus
}
