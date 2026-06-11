package org.rurbaniak.smartchessboard.data.games

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.rurbaniak.smartchessboard.domain.games.GameMode
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

class SupabaseGamesRepository(
    private val client: SupabaseClient,
) : GamesRepository {
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
}

private fun GameRowDto.toDomain(): GameSummary =
    GameSummary(
        id = id,
        createdAt = createdAt,
        mode =
            when (mode) {
                "digital" -> GameMode.DIGITAL
                "physical" -> GameMode.PHYSICAL
                else -> error("Unknown game mode: $mode")
            },
        status =
            when (status) {
                "in_progress" -> GameStatus.IN_PROGRESS
                "finished" -> GameStatus.FINISHED
                else -> error("Unknown game status: $status")
            },
        result =
            when (result) {
                null -> null
                "white" -> GameResult.WHITE
                "black" -> GameResult.BLACK
                "draw" -> GameResult.DRAW
                else -> error("Unknown game result: $result")
            },
        whiteLabel = whiteLabel,
        blackLabel = blackLabel,
    )
