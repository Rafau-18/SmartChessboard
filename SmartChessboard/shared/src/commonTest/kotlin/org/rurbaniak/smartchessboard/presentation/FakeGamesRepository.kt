package org.rurbaniak.smartchessboard.presentation

import org.rurbaniak.smartchessboard.domain.games.GameSummary
import org.rurbaniak.smartchessboard.domain.games.GamesRepository

class FakeGamesRepository : GamesRepository {
    var games: List<GameSummary> = emptyList()
    var shouldFail = false
    var listCalls = 0

    override suspend fun listMyGames(): List<GameSummary> {
        listCalls++
        if (shouldFail) throw IllegalStateException("network down")
        return games
    }
}
