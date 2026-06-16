package org.rurbaniak.smartchessboard.data.journal

import com.russhwolf.settings.Settings
import org.rurbaniak.smartchessboard.domain.games.GameJournal
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.games.JournalEntry

/**
 * [GameJournal] over multiplatform-settings (SharedPreferences with synchronous commit /
 * NSUserDefaults / localStorage). Three keys per game. Writes are ordered dirty → result → pgn so
 * the PGN (the presence gate [load] reads first) lands last: a crash mid-save can then only re-flush
 * the prior document, never mark an unsynced (or half-written) one clean. The finished result is
 * stored as the [GameResult] name and is a local-only signal — the authoritative result token lives
 * in the cloud row and the PGN's `[Result]` tag.
 */
class SettingsGameJournal(
    private val settings: Settings,
) : GameJournal {
    override fun load(gameId: String): JournalEntry? {
        val pgn = settings.getStringOrNull(pgnKey(gameId)) ?: return null
        return JournalEntry(
            pgn = pgn,
            dirty = settings.getBoolean(dirtyKey(gameId), defaultValue = false),
            result = decodeResult(settings.getStringOrNull(resultKey(gameId))),
        )
    }

    override fun save(
        gameId: String,
        pgn: String,
        dirty: Boolean,
        result: GameResult?,
    ) {
        settings.putBoolean(dirtyKey(gameId), dirty)
        if (result != null) {
            settings.putString(resultKey(gameId), result.name)
        } else {
            settings.remove(resultKey(gameId))
        }
        settings.putString(pgnKey(gameId), pgn)
    }

    override fun markSynced(gameId: String) {
        settings.putBoolean(dirtyKey(gameId), false)
    }

    override fun clear(gameId: String) {
        settings.remove(pgnKey(gameId))
        settings.remove(dirtyKey(gameId))
        settings.remove(resultKey(gameId))
    }

    private fun decodeResult(name: String?): GameResult? =
        name?.let { stored -> GameResult.entries.firstOrNull { it.name == stored } }

    private fun pgnKey(gameId: String) = "journal.$gameId.pgn"

    private fun dirtyKey(gameId: String) = "journal.$gameId.dirty"

    private fun resultKey(gameId: String) = "journal.$gameId.result"
}
