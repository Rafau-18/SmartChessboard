package org.rurbaniak.smartchessboard.data.journal

import com.russhwolf.settings.MapSettings
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.games.JournalEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsGameJournalTest {
    private val journal = SettingsGameJournal(MapSettings())

    @Test
    fun loadOfUnknownGameReturnsNull() {
        assertNull(journal.load("missing"))
    }

    @Test
    fun saveThenLoadRoundTrips() {
        journal.save("g1", "1. e4 *", dirty = true)

        assertEquals(JournalEntry("1. e4 *", dirty = true), journal.load("g1"))
    }

    @Test
    fun saveOverwritesThePreviousEntry() {
        journal.save("g1", "1. e4 *", dirty = true)
        journal.save("g1", "1. e4 e5 *", dirty = false)

        assertEquals(JournalEntry("1. e4 e5 *", dirty = false), journal.load("g1"))
    }

    @Test
    fun markSyncedFlipsDirtyAndKeepsPgn() {
        journal.save("g1", "1. e4 *", dirty = true)

        journal.markSynced("g1")

        assertEquals(JournalEntry("1. e4 *", dirty = false), journal.load("g1"))
    }

    @Test
    fun entriesAreIndependentPerGame() {
        journal.save("g1", "1. e4 *", dirty = true)
        journal.save("g2", "1. d4 *", dirty = false)

        journal.markSynced("g1")

        assertEquals(JournalEntry("1. e4 *", dirty = false), journal.load("g1"))
        assertEquals(JournalEntry("1. d4 *", dirty = false), journal.load("g2"))
    }

    @Test
    fun saveWithResultRoundTripsTheFinishedMarker() {
        journal.save("g1", "1. e4 e5 0-1", dirty = true, result = GameResult.BLACK)

        assertEquals(
            JournalEntry("1. e4 e5 0-1", dirty = true, result = GameResult.BLACK),
            journal.load("g1"),
        )
    }

    @Test
    fun savingInProgressClearsAStaleFinishedMarker() {
        journal.save("g1", "1. e4 1/2-1/2", dirty = true, result = GameResult.DRAW)
        journal.save("g1", "1. e4 e5 *", dirty = true)

        assertEquals(JournalEntry("1. e4 e5 *", dirty = true, result = null), journal.load("g1"))
    }

    @Test
    fun clearRemovesTheEntryEntirely() {
        journal.save("g1", "1. e4 e5 1-0", dirty = true, result = GameResult.WHITE)

        journal.clear("g1")

        assertNull(journal.load("g1"))
    }
}
