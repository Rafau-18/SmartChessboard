package org.rurbaniak.smartchessboard.domain.games

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the accept-move persistence sequence (contract §6.2): a move is "accepted" only after the
 * synchronous [acceptMove] journal write returns; cloud sync is best-effort, off the acceptance
 * path, and never the only copy of a move. One instance per active game screen — [syncPending]
 * tracks the single game that screen drives.
 */
class GameAutoSaver(
    private val gamesRepository: GamesRepository,
    private val journal: GameJournal,
    private val retryDelaysMs: List<Long> = listOf(1_000, 2_000, 4_000),
) {
    private val mutableSyncPending = MutableStateFlow(false)

    /** True while the journal holds moves the cloud has not confirmed — drives the UI indicator. */
    val syncPending: StateFlow<Boolean> = mutableSyncPending.asStateFlow()

    /**
     * Durably journals [pgn] — the §6.2 acceptance gate. The caller updates UI state only after
     * this returns, then launches [sync] off the acceptance path.
     */
    fun acceptMove(
        gameId: String,
        pgn: String,
    ) {
        journal.save(gameId, pgn, dirty = true)
        mutableSyncPending.value = true
    }

    /**
     * Durably journals a *finished* [pgn] + [result] — the offline-safe finish gate (S-05, extends
     * §6.2). Synchronous like [acceptMove]: the caller freezes the UI only after this returns, then
     * launches [sync]. The flush targets [GamesRepository.finishGame] and the entry is cleared only
     * after the cloud confirms, so an offline finish survives a crash and re-flushes via [reconcile].
     */
    fun finishGame(
        gameId: String,
        result: GameResult,
        pgn: String,
    ) {
        journal.save(gameId, pgn, dirty = true, result = result)
        mutableSyncPending.value = true
    }

    /**
     * Best-effort cloud flush of the journaled PGN with bounded retry. Leaves the entry dirty
     * (and [syncPending] raised) when every attempt fails; the next accepted move's sync or an
     * explicit retry re-enters.
     */
    suspend fun sync(gameId: String) {
        var failures = 0
        while (true) {
            val entry = journal.load(gameId) ?: return
            if (!entry.dirty) {
                mutableSyncPending.value = false
                return
            }
            // Raised here (not only in acceptMove) so a dirty entry found on load — reconcile's
            // journal-ahead path — also drives the indicator while the flush is unconfirmed.
            mutableSyncPending.value = true
            try {
                if (entry.result != null) {
                    gamesRepository.finishGame(gameId, entry.result, entry.pgn)
                } else {
                    gamesRepository.updatePgn(gameId, entry.pgn)
                }
                // A move accepted mid-flight supersedes this upload; leave it dirty for its own sync.
                // (A finished game accepts no further moves, so its entry always clears here.)
                if (journal.load(gameId)?.pgn == entry.pgn) {
                    if (entry.result != null) journal.clear(gameId) else journal.markSynced(gameId)
                    mutableSyncPending.value = false
                }
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (failures == retryDelaysMs.size) return
                delay(retryDelaysMs[failures])
                failures++
            }
        }
    }

    /**
     * Resolves journal vs cloud on game load and returns the PGN to play from. Journal strictly
     * ahead → flush it best-effort and play from it; anything else (no journal, cloud ahead,
     * diverged, or clean) → cloud wins and the journal is overwritten clean (LWW per §3.4). The
     * flush goes through [sync], so a journaled-but-unsynced *finished* entry re-closes the cloud
     * copy here (an offline finish that crashed before flush still lands on the next load).
     */
    suspend fun reconcile(game: GameRecord): String {
        val entry = journal.load(game.id)
        return if (entry != null && entry.dirty && isAhead(entry.pgn, game.pgn)) {
            sync(game.id)
            entry.pgn
        } else {
            journal.save(game.id, game.pgn, dirty = false)
            mutableSyncPending.value = false
            game.pgn
        }
    }

    /**
     * Both documents come from the same writer, so "journal ahead" reduces to: the cloud movetext
     * is a proper prefix of the journal movetext (token-aligned). Headers are excluded — they are
     * constant per game.
     */
    private fun isAhead(
        journalPgn: String,
        cloudPgn: String,
    ): Boolean {
        val journalMoves = movetext(journalPgn)
        val cloudMoves = movetext(cloudPgn)
        return journalMoves != cloudMoves &&
            (cloudMoves.isEmpty() || journalMoves.startsWith("$cloudMoves "))
    }

    /** Movetext without the termination marker; tolerates the empty document a fresh row holds. */
    private fun movetext(pgn: String): String {
        val body = if (pgn.startsWith("[")) pgn.substringAfter("\n\n", "") else pgn
        return body.trim().removeSuffix("*").trim()
    }
}
