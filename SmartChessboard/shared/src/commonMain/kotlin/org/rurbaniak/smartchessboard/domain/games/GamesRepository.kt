package org.rurbaniak.smartchessboard.domain.games

interface GamesRepository {
    /** The caller never passes a user id — row scoping is the backend's (RLS) responsibility. */
    suspend fun listMyGames(): List<GameSummary>
}
