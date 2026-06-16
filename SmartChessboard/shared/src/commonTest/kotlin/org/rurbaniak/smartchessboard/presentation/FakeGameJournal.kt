package org.rurbaniak.smartchessboard.presentation

import org.rurbaniak.smartchessboard.domain.games.GameJournal
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.games.JournalEntry

class FakeGameJournal : GameJournal {
    val entries = mutableMapOf<String, JournalEntry>()
    val saveLog = mutableListOf<Triple<String, String, Boolean>>()
    val syncedIds = mutableListOf<String>()
    val clearedIds = mutableListOf<String>()

    override fun load(gameId: String): JournalEntry? = entries[gameId]

    override fun save(
        gameId: String,
        pgn: String,
        dirty: Boolean,
        result: GameResult?,
    ) {
        saveLog += Triple(gameId, pgn, dirty)
        entries[gameId] = JournalEntry(pgn = pgn, dirty = dirty, result = result)
    }

    override fun markSynced(gameId: String) {
        syncedIds += gameId
        entries[gameId]?.let { entries[gameId] = it.copy(dirty = false) }
    }

    override fun clear(gameId: String) {
        clearedIds += gameId
        entries.remove(gameId)
    }
}
