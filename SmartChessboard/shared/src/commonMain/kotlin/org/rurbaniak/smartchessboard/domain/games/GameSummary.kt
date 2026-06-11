package org.rurbaniak.smartchessboard.domain.games

enum class GameMode { DIGITAL, PHYSICAL }

enum class GameStatus { IN_PROGRESS, FINISHED }

enum class GameResult { WHITE, BLACK, DRAW }

data class GameSummary(
    val id: String,
    /** ISO-8601 timestamptz as delivered by the backend; sortable lexicographically. */
    val createdAt: String,
    val mode: GameMode,
    val status: GameStatus,
    /** Null while the game is in progress. */
    val result: GameResult?,
    val whiteLabel: String,
    val blackLabel: String,
)
