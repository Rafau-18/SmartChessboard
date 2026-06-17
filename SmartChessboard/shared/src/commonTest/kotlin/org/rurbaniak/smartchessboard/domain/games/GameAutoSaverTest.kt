package org.rurbaniak.smartchessboard.domain.games

import kotlinx.coroutines.test.runTest
import org.rurbaniak.smartchessboard.domain.chess.pgn.PgnMeta
import org.rurbaniak.smartchessboard.domain.chess.pgn.writePgn
import org.rurbaniak.smartchessboard.presentation.FakeGameJournal
import org.rurbaniak.smartchessboard.presentation.FakeGamesRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    private fun finishedPgn(
        result: GameResult,
        vararg sans: String,
    ) = writePgn(meta.copy(result = result.toPgnResultToken()), sans.toList())

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

    // --- finishGame: the offline-safe closure gate (S-05) ---

    @Test
    fun finishGameJournalsTheResultDurablyWithoutTouchingTheCloud() {
        val finished = finishedPgn(GameResult.DRAW, "e4", "e5")

        saver.finishGame(GAME_ID, GameResult.DRAW, finished)

        assertEquals(
            JournalEntry(finished, dirty = true, result = GameResult.DRAW),
            journal.entries[GAME_ID],
        )
        assertTrue(repository.finishGameCalls.isEmpty(), "journal write must precede any cloud call")
        assertTrue(saver.syncPending.value)
    }

    @Test
    fun syncFlushesAFinishedEntryViaFinishGameAndClearsIt() =
        runTest {
            val finished = finishedPgn(GameResult.WHITE, "e4", "e5", "Qh5", "Nc6", "Qxf7#")
            saver.finishGame(GAME_ID, GameResult.WHITE, finished)

            saver.sync(GAME_ID)

            assertEquals(listOf(Triple(GAME_ID, GameResult.WHITE, finished)), repository.finishGameCalls)
            assertTrue(repository.updatePgnCalls.isEmpty(), "a finished entry must not take the updatePgn path")
            assertEquals(listOf(GAME_ID), journal.clearedIds, "entry removed only after a confirmed flush")
            assertNull(journal.entries[GAME_ID])
            assertFalse(saver.syncPending.value)
        }

    @Test
    fun offlineFinishKeepsRetryingPastTheBoundedWindowThenFlushesOnReconnect() =
        runTest {
            // A finished game has no further move to re-trigger the flush, so its sync must NOT give
            // up at the bounded window (as an in-progress save does) — it keeps retrying until the
            // connection returns, otherwise "Saving…" would spin forever after a slow reconnect.
            val finished = finishedPgn(GameResult.DRAW, "e4")
            repository.finishGameFailures = 5 // fails through the 3-delay window, then reconnects
            saver.finishGame(GAME_ID, GameResult.DRAW, finished)

            saver.sync(GAME_ID)

            assertEquals(6, repository.finishGameCalls.size, "retried past the bounded window until it landed")
            assertEquals(Triple(GAME_ID, GameResult.DRAW, finished), repository.finishGameCalls.last())
            assertEquals(listOf(GAME_ID), journal.clearedIds, "entry cleared only after the confirmed flush")
            assertNull(journal.entries[GAME_ID])
            assertFalse(saver.syncPending.value)
        }

    @Test
    fun offlineInProgressSaveStillGivesUpAtTheBoundedWindow() =
        runTest {
            // The in-progress path is unchanged: it gives up after the bounded attempts (its next
            // accepted move re-enters sync), so a single sync never loops indefinitely.
            repository.updatePgnFailures = Int.MAX_VALUE
            saver.acceptMove(GAME_ID, pgn("e4"))

            saver.sync(GAME_ID)

            assertEquals(4, repository.updatePgnCalls.size, "initial attempt + one per retry delay")
            assertTrue(journal.entries.getValue(GAME_ID).dirty)
            assertTrue(saver.syncPending.value)
        }

    @Test
    fun reconcileReFlushesAJournaledButUnsyncedFinish() =
        runTest {
            // Crash window: finished offline (journal dirty + result), the flush never reached the
            // cloud, which still holds the in-progress document.
            val finished = finishedPgn(GameResult.BLACK, "e4", "e5")
            journal.save(GAME_ID, finished, dirty = true, result = GameResult.BLACK)

            val resolved = saver.reconcile(cloudRecord(pgn("e4", "e5")))

            assertEquals(finished, resolved, "play continues from the finished journal")
            assertEquals(listOf(Triple(GAME_ID, GameResult.BLACK, finished)), repository.finishGameCalls)
            assertNull(journal.entries[GAME_ID], "the finish lands and the entry clears")
            assertFalse(saver.syncPending.value)
        }

    @Test
    fun reconcileWithTwoFinishedDocsSameMovesPrefersCloud() =
        runTest {
            // F1 follow-up: two *finished* documents, identical moves, different results. Not
            // reachable in MVP (finishGame clears the journal), but isAhead must resolve it by
            // status — both finished -> last-writer-wins, cloud wins — not by an accidental
            // terminator-string prefix.
            val journalFinished = finishedPgn(GameResult.BLACK, "e4", "e5")
            val cloudFinished = finishedPgn(GameResult.WHITE, "e4", "e5")
            journal.save(GAME_ID, journalFinished, dirty = true, result = GameResult.BLACK)

            val resolved = saver.reconcile(cloudRecord(cloudFinished))

            assertEquals(cloudFinished, resolved, "both finished + same moves -> cloud wins")
            assertEquals(JournalEntry(cloudFinished, dirty = false), journal.entries[GAME_ID])
            assertTrue(repository.finishGameCalls.isEmpty(), "cloud-wins must not re-flush the journal")
            assertFalse(saver.syncPending.value)
        }
}
