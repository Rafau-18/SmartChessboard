package org.rurbaniak.smartchessboard.presentation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.games.GameSummary
import org.rurbaniak.smartchessboard.domain.games.GamesRepository

class FakeGamesRepository : GamesRepository {
    var games: List<GameSummary> = emptyList()
    var records: Map<String, GameRecord> = emptyMap()
    var shouldFail = false

    // What a shouldFail call throws — default an Exception; set to a non-Exception Throwable (e.g.
    // Error) to simulate the wasm Ktor JsError that a `catch (Exception)` would silently miss.
    var failure: Throwable = IllegalStateException("network down")
    var listCalls = 0
    var getCalls = 0
    var createdGame: GameRecord? = null
    var createCalls = 0
    val createLabels = mutableListOf<Pair<String, String>>()
    val createModes = mutableListOf<GameMode>()
    val updatePgnCalls = mutableListOf<Pair<String, String>>()
    var updatePgnFailures = 0
    var onUpdatePgn: ((String, String) -> Unit)? = null
    val finishGameCalls = mutableListOf<Triple<String, GameResult, String>>()
    var finishGameFailures = 0

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    override suspend fun listMyGames(): List<GameSummary> {
        listCalls++
        if (shouldFail) throw failure
        return games
    }

    override suspend fun getGame(id: String): GameRecord {
        getCalls++
        if (shouldFail) throw failure
        return records[id] ?: throw IllegalStateException("no game with id $id")
    }

    override suspend fun createGame(
        whiteLabel: String,
        blackLabel: String,
        mode: GameMode,
    ): GameRecord {
        createCalls++
        createLabels += whiteLabel to blackLabel
        createModes += mode
        if (shouldFail) throw failure
        // Echo the requested mode so callers see the created record carry the mode they asked for,
        // even when the stubbed record was built with a different one.
        val created = (createdGame ?: throw IllegalStateException("no createdGame stubbed")).copy(mode = mode)
        _changes.tryEmit(Unit)
        return created
    }

    // Records the attempt before failing so retry tests can count attempts.
    override suspend fun updatePgn(
        id: String,
        pgn: String,
    ) {
        updatePgnCalls += id to pgn
        if (shouldFail) throw failure
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
        if (shouldFail) throw failure
        if (finishGameFailures > 0) {
            finishGameFailures--
            throw IllegalStateException("finish failed")
        }
        _changes.tryEmit(Unit)
    }
}
