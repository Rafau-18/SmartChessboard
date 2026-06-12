package org.rurbaniak.smartchessboard.domain.games

interface GamesRepository {
    /** The caller never passes a user id — row scoping is the backend's (RLS) responsibility. */
    suspend fun listMyGames(): List<GameSummary>

    /** The caller never passes a user id — row scoping is the backend's (RLS) responsibility. */
    suspend fun getGame(id: String): GameRecord

    /**
     * Creates a digital in-progress game with an empty PGN (contract §3.2; `user_id` is defaulted
     * server-side). Returns the created row — `id` feeds navigation, the server `created_at`
     * feeds the PGN `[Date]` tag. Requires connectivity; failures propagate to the caller.
     */
    suspend fun createGame(
        whiteLabel: String,
        blackLabel: String,
    ): GameRecord

    /** Auto-save (contract §3.2): replaces the stored PGN; status/result are untouched in S-04. */
    suspend fun updatePgn(
        id: String,
        pgn: String,
    )
}
