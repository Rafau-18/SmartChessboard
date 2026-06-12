package org.rurbaniak.smartchessboard.data.journal

import com.russhwolf.settings.Settings
import org.rurbaniak.smartchessboard.domain.games.GameJournal
import org.rurbaniak.smartchessboard.domain.games.JournalEntry

/**
 * [GameJournal] over multiplatform-settings (SharedPreferences with synchronous commit /
 * NSUserDefaults / localStorage). Two keys per game. The dirty flag is written before the PGN:
 * a crash between the two writes can then only re-sync an already-synced document — never mark
 * an unsynced (or half-written) one clean.
 */
class SettingsGameJournal(
    private val settings: Settings,
) : GameJournal {
    override fun load(gameId: String): JournalEntry? {
        val pgn = settings.getStringOrNull(pgnKey(gameId)) ?: return null
        return JournalEntry(pgn = pgn, dirty = settings.getBoolean(dirtyKey(gameId), defaultValue = false))
    }

    override fun save(
        gameId: String,
        pgn: String,
        dirty: Boolean,
    ) {
        settings.putBoolean(dirtyKey(gameId), dirty)
        settings.putString(pgnKey(gameId), pgn)
    }

    override fun markSynced(gameId: String) {
        settings.putBoolean(dirtyKey(gameId), false)
    }

    private fun pgnKey(gameId: String) = "journal.$gameId.pgn"

    private fun dirtyKey(gameId: String) = "journal.$gameId.dirty"
}
