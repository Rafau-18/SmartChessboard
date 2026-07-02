package org.rurbaniak.smartchessboard.domain.games

import kotlinx.coroutines.flow.SharedFlow

interface GamesRepository {
    /**
     * Emits once after any mutation that changes what the games **list** shows — a new game
     * ([createGame]), a game closing ([finishGame]), or a game being removed ([deleteGame]). A
     * retained list screen (History) collects this to re-fetch, instead of depending on UI
     * composition re-entry or a lifecycle resume — both diverge across Android / iOS / web.
     * Deliberately NOT emitted for [updatePgn]: that is movetext-only (the list shows none of it)
     * and fires on every move.
     */
    val changes: SharedFlow<Unit>

    /** The caller never passes a user id — row scoping is the backend's (RLS) responsibility. */
    suspend fun listMyGames(): List<GameSummary>

    /** The caller never passes a user id — row scoping is the backend's (RLS) responsibility. */
    suspend fun getGame(id: String): GameRecord

    /**
     * Creates an in-progress game in the given [mode] with an empty PGN (contract §3.2; `user_id` is
     * defaulted server-side). Returns the created row — `id` feeds navigation, the server `created_at`
     * feeds the PGN `[Date]` tag. Requires connectivity; failures propagate to the caller.
     */
    suspend fun createGame(
        whiteLabel: String,
        blackLabel: String,
        mode: GameMode,
    ): GameRecord

    /** Auto-save (contract §3.2): replaces the stored PGN; status/result are untouched in S-04. */
    suspend fun updatePgn(
        id: String,
        pgn: String,
    )

    /**
     * Closes a game in one round-trip (contract §3.2, widened in S-05): sets `status='finished'`,
     * the [result] token, and the final [pgn] together so the cloud row never sits half-finished
     * (status set but PGN stale, or vice versa). The caller never passes a user id — RLS scopes
     * ownership. Propagates failures like [updatePgn].
     */
    suspend fun finishGame(
        id: String,
        result: GameResult,
        pgn: String,
    )

    /**
     * Permanently deletes one own game — `DELETE FROM games WHERE id = $1` (contract §3.2). The
     * caller never passes a user id; RLS scopes ownership. Deleting an already-gone row (e.g. raced
     * from another device) matches zero rows and is an idempotent success. Emits [changes] on
     * success so the History list re-fetches. Requires connectivity; failures propagate to the
     * caller.
     */
    suspend fun deleteGame(id: String)
}
