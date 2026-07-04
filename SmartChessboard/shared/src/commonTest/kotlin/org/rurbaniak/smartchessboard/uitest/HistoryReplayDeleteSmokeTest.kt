package org.rurbaniak.smartchessboard.uitest

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilExactlyOneExists
import org.rurbaniak.smartchessboard.domain.chess.pgn.PgnFixtures
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.games.GameStatus
import org.rurbaniak.smartchessboard.domain.games.GameSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Generous ceiling for load/navigation transitions; the fakes answer instantly, so real runs never near it. */
private const val STEP_TIMEOUT_MS = 5_000L

/**
 * The History → Replay + delete smoke (Phase 4): a seeded finished game opens in Replay (board,
 * player line with result, move list), the transport steps through plies (next/prev, end/start),
 * Back returns to History, and the kebab → Delete → confirmation flow removes the row — asserted
 * in the UI (empty state) and against the fakes (cloud delete + journal clear). Analysis stays off,
 * so the eval repository is never queried (and would answer a stable NoEval if it were).
 *
 * The game labels come from the PGN headers ([PgnFixtures.OPERA_GAME]) so the History row, the
 * Replay title, and the player line all agree — the fixture is the single source of truth.
 */
@OptIn(ExperimentalTestApi::class)
class HistoryReplayDeleteSmokeTest {
    private val gameId = "uitest-replay-opera"
    private val matchup = "Paul Morphy vs Duke Karl / Count Isouard"

    private val finishedRecord =
        GameRecord(
            id = gameId,
            createdAt = "2026-07-04T09:00:00+00:00",
            mode = GameMode.DIGITAL,
            status = GameStatus.FINISHED,
            result = GameResult.WHITE,
            whiteLabel = "Paul Morphy",
            blackLabel = "Duke Karl / Count Isouard",
            pgn = PgnFixtures.OPERA_GAME,
        )

    private val finishedSummary =
        GameSummary(
            id = gameId,
            createdAt = finishedRecord.createdAt,
            mode = GameMode.DIGITAL,
            status = GameStatus.FINISHED,
            result = GameResult.WHITE,
            whiteLabel = finishedRecord.whiteLabel,
            blackLabel = finishedRecord.blackLabel,
        )

    private val seed =
        FakeSeed().apply {
            games.games = listOf(finishedSummary)
            games.records = mapOf(gameId to finishedRecord)
        }

    @Test
    fun historyOpensReplay_stepsThroughPlies_andDeleteEmptiesTheList() =
        runAppTest(seed) {
            // History lists the seeded finished game; tapping the row opens it in Replay.
            waitUntilExactlyOneExists(hasText(matchup), STEP_TIMEOUT_MS)
            onNode(hasClickAction() and hasText(matchup)).performClick()

            // Replay loaded: player line carries the PGN result, the board renders, and the move
            // list shows the final move of the fixture game.
            waitUntilExactlyOneExists(hasText("$matchup · 1-0"), STEP_TIMEOUT_MS)
            waitUntilExactlyOneExists(hasTestTag("chess-board"), STEP_TIMEOUT_MS)
            onNodeWithText("Rd8#").assertExists()

            // Transport: opens at ply 0 (back disabled) — forward/back and end/start move the ply,
            // observable through the buttons' enabled state flipping at each boundary.
            val start = hasClickAction() and hasText("|<")
            val back = hasClickAction() and hasText("<")
            val forward = hasClickAction() and hasText(">")
            val end = hasClickAction() and hasText(">|")
            onNode(back).assertIsNotEnabled()
            onNode(forward).assertIsEnabled()
            onNode(forward).performClick()
            waitForIdle()
            onNode(back).assertIsEnabled()
            onNode(back).performClick()
            waitForIdle()
            onNode(back).assertIsNotEnabled()
            onNode(end).performClick()
            waitForIdle()
            onNode(forward).assertIsNotEnabled()
            onNode(start).performClick()
            waitForIdle()
            onNode(forward).assertIsEnabled()
            onNode(back).assertIsNotEnabled()

            // Back to History — the row is still there.
            onAction("Back").performClick()
            waitUntilExactlyOneExists(hasText(matchup), STEP_TIMEOUT_MS)

            // From here the repository lists nothing — what History must show after the delete.
            seed.games.games = emptyList()

            // Kebab → Delete → confirmation dialog (names the matchup, states permanence).
            onNodeWithContentDescription("Game actions").performClick()
            waitUntilExactlyOneExists(hasClickAction() and hasText("Delete"), STEP_TIMEOUT_MS)
            onNode(hasClickAction() and hasText("Delete")).performClick()
            waitUntilExactlyOneExists(hasText("Delete game?"), STEP_TIMEOUT_MS)
            onNodeWithText("This permanently deletes $matchup", substring = true).assertExists()
            onNode(hasClickAction() and hasText("Delete")).performClick()

            // The row is gone: History drops to the empty state.
            waitUntilExactlyOneExists(hasText("No games yet", substring = true), STEP_TIMEOUT_MS)
            onNodeWithText(matchup).assertDoesNotExist()

            // The delete really crossed the domain seams: cloud row first, then the journal entry.
            assertEquals(listOf(gameId), seed.games.deleteGameCalls)
            assertTrue(gameId in seed.journal.clearedIds, "journal entry cleared after the cloud delete")
        }
}
