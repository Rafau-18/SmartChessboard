package org.rurbaniak.smartchessboard.domain.games

/**
 * One domain entry point for permanently deleting a game: the cloud row first, then the local
 * write-ahead-journal entry. Modeled on [GameAutoSaver] (same dependency pair — a [GamesRepository]
 * and a [GameJournal]) so no caller can forget the journal half and leave a resurrection-shaped
 * leftover.
 *
 * Ordering is load-bearing: [journal] is cleared **only after** the cloud
 * [GamesRepository.deleteGame] succeeds. If the delete throws, the exception propagates and the
 * journal is left untouched — an in-progress game's dirty journal entry is its durability
 * guarantee, and a failed delete must not degrade it. Never clear optimistically.
 */
class GameDeleter(
    private val gamesRepository: GamesRepository,
    private val journal: GameJournal,
) {
    suspend fun delete(gameId: String) {
        gamesRepository.deleteGame(gameId)
        journal.clear(gameId)
    }
}
