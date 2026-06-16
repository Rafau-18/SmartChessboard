package org.rurbaniak.smartchessboard.presentation

import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.games.GameSummary
import org.rurbaniak.smartchessboard.domain.games.GamesRepository

class FakeGamesRepository : GamesRepository {
    var games: List<GameSummary> = emptyList()
    var records: Map<String, GameRecord> = emptyMap()
    var shouldFail = false
    var listCalls = 0
    var getCalls = 0
    var createdGame: GameRecord? = null
    var createCalls = 0
    val createLabels = mutableListOf<Pair<String, String>>()
    val updatePgnCalls = mutableListOf<Pair<String, String>>()
    var updatePgnFailures = 0
    var onUpdatePgn: ((String, String) -> Unit)? = null
    val finishGameCalls = mutableListOf<Triple<String, GameResult, String>>()
    var finishGameFailures = 0

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

    override suspend fun createGame(
        whiteLabel: String,
        blackLabel: String,
    ): GameRecord {
        createCalls++
        createLabels += whiteLabel to blackLabel
        if (shouldFail) throw IllegalStateException("network down")
        return createdGame ?: throw IllegalStateException("no createdGame stubbed")
    }

    // Records the attempt before failing so retry tests can count attempts.
    override suspend fun updatePgn(
        id: String,
        pgn: String,
    ) {
        updatePgnCalls += id to pgn
        if (shouldFail) throw IllegalStateException("network down")
        if (updatePgnFailures > 0) {
            updatePgnFailures--
            throw IllegalStateException("update failed")
        }
        onUpdatePgn?.invoke(id, pgn)
    }

    // Records the attempt before failing so retry/offline tests can count attempts.
    override suspend fun finishGame(
        id: String,
        result: GameResult,
        pgn: String,
    ) {
        finishGameCalls += Triple(id, result, pgn)
        if (shouldFail) throw IllegalStateException("network down")
        if (finishGameFailures > 0) {
            finishGameFailures--
            throw IllegalStateException("finish failed")
        }
    }
}
