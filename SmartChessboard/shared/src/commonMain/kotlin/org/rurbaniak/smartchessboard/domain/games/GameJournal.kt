package org.rurbaniak.smartchessboard.domain.games

/**
 * Latest locally journaled PGN for a game. [dirty] = not yet confirmed written to the cloud.
 * [result] is non-null once the journaled PGN is a *finished* one (S-05) — it tells the flush which
 * verb to use (`finishGame` vs `updatePgn`) and that cleanup should remove, not just mark-synced,
 * the entry.
 */
data class JournalEntry(
    val pgn: String,
    val dirty: Boolean,
    val result: GameResult? = null,
)

/**
 * Durable, synchronous local store of the latest PGN per in-progress game — the write-ahead half
 * of the §6.2 invariant ("every accepted move is durably stored locally before the next move is
 * accepted"). [save] must persist across process death before returning. A finished entry carries
 * its [JournalEntry.result]; [clear] removes an entry once a finished flush is confirmed in the
 * cloud.
 */
interface GameJournal {
    fun load(gameId: String): JournalEntry?

    fun save(
        gameId: String,
        pgn: String,
        dirty: Boolean,
        result: GameResult? = null,
    )

    fun markSynced(gameId: String)

    /** Removes the entry entirely — used after a confirmed finished flush (S-05). */
    fun clear(gameId: String)
}
