package org.rurbaniak.smartchessboard.data.games

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.games.GameStatus
import org.rurbaniak.smartchessboard.domain.games.GameSummary
import org.rurbaniak.smartchessboard.domain.games.GamesRepository

@Serializable
private data class GameRowDto(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val mode: String,
    val status: String,
    val result: String? = null,
    @SerialName("white_label") val whiteLabel: String,
    @SerialName("black_label") val blackLabel: String,
)

@Serializable
private data class GameRecordDto(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val mode: String,
    val status: String,
    val result: String? = null,
    @SerialName("white_label") val whiteLabel: String,
    @SerialName("black_label") val blackLabel: String,
    val pgn: String,
)

// No user_id field — the column default (auth.uid()) owns it server-side (contract §2.2/§3.2).
@Serializable
private data class NewGameDto(
    val mode: String,
    val status: String,
    val pgn: String,
    @SerialName("white_label") val whiteLabel: String,
    @SerialName("black_label") val blackLabel: String,
)

class SupabaseGamesRepository(
    private val client: SupabaseClient,
) : GamesRepository {
    // replay = 0 + a one-slot buffer so tryEmit never suspends; History subscribes for the whole
    // session (it is the back-stack root), so emissions are always delivered to it.
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    // No user_id filter — RLS scopes rows to the authenticated user (contract §3.2).
    override suspend fun listMyGames(): List<GameSummary> =
        client
            .from("games")
            .select(
                Columns.list("id", "created_at", "mode", "status", "result", "white_label", "black_label"),
            ) {
                order("created_at", Order.DESCENDING)
            }.decodeList<GameRowDto>()
            .map { it.toDomain() }

    // Missing/foreign id (RLS-filtered) yields zero rows — decodeSingle throws, the
    // ViewModel maps it to its Error state (same failure convention as listMyGames).
    override suspend fun getGame(id: String): GameRecord =
        client
            .from("games")
            .select(
                Columns.list("id", "created_at", "mode", "status", "result", "white_label", "black_label", "pgn"),
            ) {
                filter { eq("id", id) }
            }.decodeSingle<GameRecordDto>()
            .toDomain()

    override suspend fun createGame(
        whiteLabel: String,
        blackLabel: String,
        mode: GameMode,
    ): GameRecord =
        client
            .from("games")
            .insert(
                NewGameDto(
                    mode = mode.toModeColumn(),
                    status = "in_progress",
                    pgn = "",
                    whiteLabel = whiteLabel,
                    blackLabel = blackLabel,
                ),
            ) {
                select(
                    Columns.list("id", "created_at", "mode", "status", "result", "white_label", "black_label", "pgn"),
                )
            }.decodeSingle<GameRecordDto>()
            .toDomain()
            .also { _changes.tryEmit(Unit) }

    override suspend fun updatePgn(
        id: String,
        pgn: String,
    ) {
        client
            .from("games")
            .update({ set("pgn", pgn) }) {
                filter { eq("id", id) }
            }
    }

    // One atomic UPDATE: status, result token, and final PGN land together (contract §3.2, S-05).
    override suspend fun finishGame(
        id: String,
        result: GameResult,
        pgn: String,
    ) {
        client
            .from("games")
            .update(
                {
                    set("status", "finished")
                    set("result", result.toResultColumn())
                    set("pgn", pgn)
                },
            ) {
                filter { eq("id", id) }
            }
        _changes.tryEmit(Unit)
    }
}

private fun GameRowDto.toDomain(): GameSummary =
    GameSummary(
        id = id,
        createdAt = createdAt,
        mode = parseMode(mode),
        status = parseStatus(status),
        result = parseResult(result),
        whiteLabel = whiteLabel,
        blackLabel = blackLabel,
    )

private fun GameRecordDto.toDomain(): GameRecord =
    GameRecord(
        id = id,
        createdAt = createdAt,
        mode = parseMode(mode),
        status = parseStatus(status),
        result = parseResult(result),
        whiteLabel = whiteLabel,
        blackLabel = blackLabel,
        pgn = pgn,
    )

internal fun parseMode(mode: String): GameMode =
    when (mode) {
        "digital" -> GameMode.DIGITAL
        "physical" -> GameMode.PHYSICAL
        else -> error("Unknown game mode: $mode")
    }

// Inverse of parseMode — the column token written on create (mirrors the §3.2 mode CHECK domain).
internal fun GameMode.toModeColumn(): String =
    when (this) {
        GameMode.DIGITAL -> "digital"
        GameMode.PHYSICAL -> "physical"
    }

private fun parseStatus(status: String): GameStatus =
    when (status) {
        "in_progress" -> GameStatus.IN_PROGRESS
        "finished" -> GameStatus.FINISHED
        else -> error("Unknown game status: $status")
    }

private fun parseResult(result: String?): GameResult? =
    when (result) {
        null -> null
        "white" -> GameResult.WHITE
        "black" -> GameResult.BLACK
        "draw" -> GameResult.DRAW
        else -> error("Unknown game result: $result")
    }

// Inverse of parseResult — the column token written on finish (mirrors the §3.2 CHECK domain).
private fun GameResult.toResultColumn(): String =
    when (this) {
        GameResult.WHITE -> "white"
        GameResult.BLACK -> "black"
        GameResult.DRAW -> "draw"
    }
