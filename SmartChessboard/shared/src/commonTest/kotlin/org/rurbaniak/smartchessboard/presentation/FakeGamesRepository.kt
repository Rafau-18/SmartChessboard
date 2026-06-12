package org.rurbaniak.smartchessboard.presentation

import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.domain.games.GameSummary
import org.rurbaniak.smartchessboard.domain.games.GamesRepository

class FakeGamesRepository : GamesRepository {
    var games: List<GameSummary> = emptyList()
    var records: Map<String, GameRecord> = emptyMap()
    var shouldFail = false
    var listCalls = 0
    var getCalls = 0

    override suspend fun listMyGames(): List<GameSummary> {
        listCalls++
        if (shouldFail) throw IllegalStateException("network down")
        return games
    }

    override suspend fun getGame(id: String): GameRecord {
        getCalls++
        if (shouldFail) throw IllegalStateException("network down")
        return records[id] ?: throw IllegalStateException("no game with id $id")
    }
}
