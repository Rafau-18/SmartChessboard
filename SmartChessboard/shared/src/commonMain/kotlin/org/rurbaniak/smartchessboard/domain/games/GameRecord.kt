package org.rurbaniak.smartchessboard.domain.games

/**
 * Full game record for replay: the [GameSummary] fields plus the PGN source of truth.
 * Kept separate from [GameSummary] so the history list stays lightweight (no `pgn` fetched).
 */
data class GameRecord(
    val id: String,
    /** ISO-8601 timestamptz as delivered by the backend; sortable lexicographically. */
    val createdAt: String,
    val mode: GameMode,
    val status: GameStatus,
    /** Null while the game is in progress. */
    val result: GameResult?,
    val whiteLabel: String,
    val blackLabel: String,
    /** May be empty — a freshly created game has no moves yet (contract §2.2 default ''). */
    val pgn: String,
)
