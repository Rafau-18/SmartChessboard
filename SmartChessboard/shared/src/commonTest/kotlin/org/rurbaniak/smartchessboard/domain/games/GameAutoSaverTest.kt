package org.rurbaniak.smartchessboard.domain.games

import kotlinx.coroutines.test.runTest
import org.rurbaniak.smartchessboard.domain.chess.pgn.PgnMeta
import org.rurbaniak.smartchessboard.domain.chess.pgn.writePgn
import org.rurbaniak.smartchessboard.presentation.FakeGameJournal
import org.rurbaniak.smartchessboard.presentation.FakeGamesRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val GAME_ID = "game-1"

class GameAutoSaverTest {
    private val repository = FakeGamesRepository()
    private val journal = FakeGameJournal()
    private val saver = GameAutoSaver(gamesRepository = repository, journal = journal)

    private val meta =
        PgnMeta(
            event = "Smart Chessboard",
            date = "2026.06.12",
            white = "White",
            black = "Black",
            result = "*",
            mode = "digital",
        )

    private fun pgn(vararg sans: String) = writePgn(meta, sans.toList())

    private fun cloudRecord(pgn: String) =
        GameRecord(
            id = GAME_ID,
            createdAt = "2026-06-12T10:00:00Z",
            mode = GameMode.DIGITAL,
            status = GameStatus.IN_PROGRESS,
            result = null,
            whiteLabel = "White",
            blackLabel = "Black",
            pgn = pgn,
        )

    // --- acceptMove: the §6.2 ordering gate ---

    @Test
    fun acceptMoveJournalsDurablyWithoutTouchingTheCloud() {
        saver.acceptMove(GAME_ID, pgn("e4"))

        assertEquals(JournalEntry(pgn("e4"), dirty = true), journal.entries[GAME_ID])
        assertTrue(repository.updatePgnCalls.isEmpty(), "journal write must precede any cloud call")
        assertTrue(saver.syncPending.value)
    }

    // --- sync: best-effort flush with bounded retry ---

    @Test
    fun syncUploadsJournaledPgnAndMarksSynced() =
        runTest {
            saver.acceptMove(GAME_ID, pgn("e4"))

            saver.sync(GAME_ID)

            assertEquals(listOf(GAME_ID to pgn("e4")), repository.updatePgnCalls)
            assertEquals(listOf(GAME_ID), journal.syncedIds)
            assertFalse(journal.entries.getValue(GAME_ID).dirty)
            assertFalse(saver.syncPending.value)
        }

    @Test
    fun syncRetriesAfterTransientFailures() =
        runTest {
            repository.updatePgnFailures = 2
            saver.acceptMove(GAME_ID, pgn("e4"))

            saver.sync(GAME_ID)

            assertEquals(3, repository.updatePgnCalls.size)
            assertFalse(journal.entries.getValue(GAME_ID).dirty)
            assertFalse(saver.syncPending.value)
        }

    @Test
    fun syncGivesUpAfterBoundedAttemptsLeavingEntryDirty() =
        runTest {
            repository.updatePgnFailures = Int.MAX_VALUE
            saver.acceptMove(GAME_ID, pgn("e4"))

            saver.sync(GAME_ID)

            assertEquals(4, repository.updatePgnCalls.size, "initial attempt + one per retry delay")
            assertTrue(journal.entries.getValue(GAME_ID).dirty)
            assertTrue(saver.syncPending.value)
        }

    @Test
    fun syncSkipsCleanEntryAndLowersIndicator() =
        runTest {
            journal.save(GAME_ID, pgn("e4"), dirty = false)

            saver.sync(GAME_ID)

            assertTrue(repository.updatePgnCalls.isEmpty())
            assertFalse(saver.syncPending.value)
        }

    @Test
    fun syncDoesNotMarkSyncedWhenANewerMoveLandedMidFlight() =
        runTest {
            saver.acceptMove(GAME_ID, pgn("e4"))
            repository.onUpdatePgn = { _, _ -> saver.acceptMove(GAME_ID, pgn("e4", "e5")) }

            saver.sync(GAME_ID)

            assertEquals(JournalEntry(pgn("e4", "e5"), dirty = true), journal.entries[GAME_ID])
            assertTrue(saver.syncPending.value, "the superseding move still awaits its own sync")
        }

    // --- reconcile: journal vs cloud on game load ---

    @Test
    fun reconcileWithoutJournalPlaysFromCloudAndSeedsJournalClean() =
        runTest {
            val resolved = saver.reconcile(cloudRecord(pgn("e4")))

            assertEquals(pgn("e4"), resolved)
            assertEquals(JournalEntry(pgn("e4"), dirty = false), journal.entries[GAME_ID])
            assertFalse(saver.syncPending.value)
        }

    @Test
    fun reconcileJournalAheadFlushesAndPlaysFromJournal() =
        runTest {
            journal.save(GAME_ID, pgn("e4", "e5"), dirty = true)

            val resolved = saver.reconcile(cloudRecord(pgn("e4")))

            assertEquals(pgn("e4", "e5"), resolved)
            assertEquals(listOf(GAME_ID to pgn("e4", "e5")), repository.updatePgnCalls)
            assertFalse(journal.entries.getValue(GAME_ID).dirty)
        }

    @Test
    fun reconcileJournalAheadOfEmptyCloudDocument() =
        runTest {
            // A freshly created row holds pgn = '' (contract §2.2 default).
            journal.save(GAME_ID, pgn("e4"), dirty = true)

            val resolved = saver.reconcile(cloudRecord(""))

            assertEquals(pgn("e4"), resolved)
            assertEquals(listOf(GAME_ID to pgn("e4")), repository.updatePgnCalls)
        }

    @Test
    fun reconcileJournalAheadWithCloudUnreachableStaysDirty() =
        runTest {
            repository.updatePgnFailures = Int.MAX_VALUE
            journal.save(GAME_ID, pgn("e4", "e5"), dirty = true)

            val resolved = saver.reconcile(cloudRecord(pgn("e4")))

            assertEquals(pgn("e4", "e5"), resolved, "offline resume still plays from the journal")
            assertTrue(journal.entries.getValue(GAME_ID).dirty)
            assertTrue(saver.syncPending.value)
        }

    @Test
    fun reconcileCloudAheadOverwritesCleanJournal() =
        runTest {
            journal.save(GAME_ID, pgn("e4"), dirty = false)

            val resolved = saver.reconcile(cloudRecord(pgn("e4", "e5")))

            assertEquals(pgn("e4", "e5"), resolved)
            assertEquals(JournalEntry(pgn("e4", "e5"), dirty = false), journal.entries[GAME_ID])
        }

    @Test
    fun reconcileCloudAheadOfDirtyJournalCloudWins() =
        runTest {
            journal.save(GAME_ID, pgn("e4"), dirty = true)

            val resolved = saver.reconcile(cloudRecord(pgn("e4", "e5")))

            assertEquals(pgn("e4", "e5"), resolved)
            assertEquals(JournalEntry(pgn("e4", "e5"), dirty = false), journal.entries[GAME_ID])
            assertTrue(repository.updatePgnCalls.isEmpty(), "cloud-wins must not push the stale journal")
        }

    @Test
    fun reconcileDivergedDocumentsCloudWins() =
        runTest {
            journal.save(GAME_ID, pgn("d4"), dirty = true)

            val resolved = saver.reconcile(cloudRecord(pgn("e4")))

            assertEquals(pgn("e4"), resolved)
            assertEquals(JournalEntry(pgn("e4"), dirty = false), journal.entries[GAME_ID])
            assertFalse(saver.syncPending.value)
        }

    @Test
    fun reconcileEqualMovetextWithStaleDirtyFlagSelfHeals() =
        runTest {
            // Crash window: cloud write landed but markSynced never ran.
            journal.save(GAME_ID, pgn("e4"), dirty = true)

            val resolved = saver.reconcile(cloudRecord(pgn("e4")))

            assertEquals(pgn("e4"), resolved)
            assertFalse(journal.entries.getValue(GAME_ID).dirty)
            assertTrue(repository.updatePgnCalls.isEmpty())
        }
}
