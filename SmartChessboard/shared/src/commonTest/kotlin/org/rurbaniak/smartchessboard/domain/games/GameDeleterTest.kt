package org.rurbaniak.smartchessboard.domain.games

import kotlinx.coroutines.test.runTest
import org.rurbaniak.smartchessboard.presentation.FakeGameJournal
import org.rurbaniak.smartchessboard.presentation.FakeGamesRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GAME_ID = "game-1"

class GameDeleterTest {
    private val repository = FakeGamesRepository()
    private val journal = FakeGameJournal()
    private val deleter = GameDeleter(gamesRepository = repository, journal = journal)

    @Test
    fun deleteRemovesCloudRowThenClearsAllJournalKeys() =
        runTest {
            journal.save(GAME_ID, "1. e4", dirty = true)

            deleter.delete(GAME_ID)

            assertEquals(listOf(GAME_ID), repository.deleteGameCalls)
            assertEquals(listOf(GAME_ID), journal.clearedIds, "cloud delete must be followed by journal.clear")
            assertNull(journal.entries[GAME_ID], "every journal key removed on success")
        }

    @Test
    fun deleteOfAGameWithNoJournalEntrySucceeds() =
        runTest {
            deleter.delete(GAME_ID)

            assertEquals(listOf(GAME_ID), repository.deleteGameCalls)
            assertEquals(listOf(GAME_ID), journal.clearedIds, "clear is a no-op on an absent entry, not an error")
            assertNull(journal.entries[GAME_ID])
        }

    @Test
    fun failedCloudDeleteLeavesTheDirtyJournalEntryIntact() =
        runTest {
            // Ordering invariant: the journal (an in-progress game's durability guarantee) must not
            // be cleared when the cloud delete fails.
            journal.save(GAME_ID, "1. e4", dirty = true)
            repository.shouldFail = true

            assertFailsWith<IllegalStateException> { deleter.delete(GAME_ID) }

            assertTrue(repository.deleteGameCalls.isNotEmpty(), "the cloud delete was attempted")
            assertTrue(journal.clearedIds.isEmpty(), "a failed delete must not clear the journal")
            assertEquals(JournalEntry("1. e4", dirty = true), journal.entries[GAME_ID])
        }

    @Test
    fun aNonExceptionThrowableFromDeletePropagatesAndLeavesTheJournal() =
        runTest {
            // On wasm a Ktor fetch failure is a kotlin.Error (not an Exception). GameDeleter does not
            // catch — it must propagate any Throwable and, because clear runs strictly after the
            // cloud call, leave the journal untouched. (The graceful-UI catch lives in the ViewModel.)
            journal.save(GAME_ID, "1. e4", dirty = true)
            repository.shouldFail = true
            repository.failure = kotlin.Error("wasm JsError shape")

            assertFailsWith<Error> { deleter.delete(GAME_ID) }

            assertTrue(journal.clearedIds.isEmpty(), "journal untouched after a failed cloud delete")
            assertEquals(JournalEntry("1. e4", dirty = true), journal.entries[GAME_ID])
        }
}
