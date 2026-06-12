package org.rurbaniak.smartchessboard.domain.games

/** Latest locally journaled PGN for a game. [dirty] = not yet confirmed written to the cloud. */
data class JournalEntry(
    val pgn: String,
    val dirty: Boolean,
)

/**
 * Durable, synchronous local store of the latest PGN per in-progress game — the write-ahead half
 * of the §6.2 invariant ("every accepted move is durably stored locally before the next move is
 * accepted"). [save] must persist across process death before returning. Entries are kept until
 * S-05 owns cleanup on game finish.
 */
interface GameJournal {
    fun load(gameId: String): JournalEntry?

    fun save(
        gameId: String,
        pgn: String,
        dirty: Boolean,
    )

    fun markSynced(gameId: String)
}
