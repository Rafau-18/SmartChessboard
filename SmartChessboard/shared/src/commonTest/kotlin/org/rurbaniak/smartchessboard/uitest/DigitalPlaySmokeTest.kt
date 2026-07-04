package org.rurbaniak.smartchessboard.uitest

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilExactlyOneExists
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.games.GameStatus
import org.rurbaniak.smartchessboard.domain.games.GameSummary
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Generous ceiling for load/navigation transitions; the fakes answer instantly, so real runs never near it. */
private const val STEP_TIMEOUT_MS = 5_000L

/**
 * The digital happy path (Phase 3 smoke): from an empty History, create a pass-and-play game, play
 * 1. e4 on the board, see it in the move list, end the game manually (White wins), and land back on
 * History showing the finished game. Real `App()` root — real navigation and ViewModels — over the
 * [FakeSeed] data layer; every step asserts by semantics, never pixels.
 */
@OptIn(ExperimentalTestApi::class)
class DigitalPlaySmokeTest {
    /** Unique-per-run player names (E2E house rule) so a leaked state collision can never false-pass. */
    private val runId = Random.nextInt(100_000, 1_000_000)
    private val whiteName = "White-$runId"
    private val blackName = "Black-$runId"
    private val gameId = "uitest-digital-$runId"

    private val createdRecord =
        GameRecord(
            id = gameId,
            createdAt = "2026-07-04T10:00:00+00:00",
            mode = GameMode.DIGITAL,
            status = GameStatus.IN_PROGRESS,
            result = null,
            whiteLabel = whiteName,
            blackLabel = blackName,
            pgn = "",
        )

    private val finishedSummary =
        GameSummary(
            id = gameId,
            createdAt = createdRecord.createdAt,
            mode = GameMode.DIGITAL,
            status = GameStatus.FINISHED,
            result = GameResult.WHITE,
            whiteLabel = whiteName,
            blackLabel = blackName,
        )

    private val seed =
        FakeSeed().apply {
            games.games = emptyList()
            games.createdGame = createdRecord
            games.records = mapOf(gameId to createdRecord)
        }

    @Test
    fun digitalHappyPath_createPlayEndAndReturnToHistory() =
        runAppTest(seed) {
            // Signed-in root lands on History, which loads to the empty state.
            waitUntilExactlyOneExists(hasText("No games yet", substring = true), STEP_TIMEOUT_MS)

            // Create a pass-and-play game with unique-per-run labels.
            onAction("New game").performClick()
            waitUntilExactlyOneExists(hasSetTextAction() and hasText("White"), STEP_TIMEOUT_MS)
            onNode(hasSetTextAction() and hasText("White")).apply {
                performTextClearance()
                performTextInput(whiteName)
            }
            onNode(hasSetTextAction() and hasText("Black")).apply {
                performTextClearance()
                performTextInput(blackName)
            }
            onNode(hasClickAction() and hasText("Start")).performClick()

            // The form is replaced by the Play screen for the created game.
            waitUntilExactlyOneExists(hasTestTag("chess-board"), STEP_TIMEOUT_MS)
            waitUntilExactlyOneExists(hasText("White to move"), STEP_TIMEOUT_MS)

            // 1. e4: select the pawn, tap the target; the move shows up in the move list.
            tapSquare("e2")
            tapSquare("e4")
            waitUntilExactlyOneExists(hasText("e4"), STEP_TIMEOUT_MS)
            waitUntilExactlyOneExists(hasText("Black to move"), STEP_TIMEOUT_MS)

            // From here the repository lists the finished game — what History must show on return.
            seed.games.games = listOf(finishedSummary)

            // Manual end (FR-018): picker → White wins → irreversibility confirmation.
            onNode(hasClickAction() and hasText("End game")).performClick()
            waitUntilExactlyOneExists(hasClickAction() and hasText("White wins"), STEP_TIMEOUT_MS)
            onNode(hasClickAction() and hasText("White wins")).performClick()
            waitUntilExactlyOneExists(hasText("End game?"), STEP_TIMEOUT_MS)
            onNode(hasClickAction() and hasText("End game") and hasAnyAncestor(isDialog())).performClick()

            // The game is closed: final banner + post-game actions replace the End-game control.
            waitUntilExactlyOneExists(hasText("White wins"), STEP_TIMEOUT_MS)
            waitUntilExactlyOneExists(hasClickAction() and hasText("Back to history"), STEP_TIMEOUT_MS)
            onNode(hasClickAction() and hasText("Back to history")).performClick()

            // Back on History: the finished game is listed with its outcome.
            waitUntilExactlyOneExists(hasText("$whiteName vs $blackName"), STEP_TIMEOUT_MS)
            onNodeWithText("White won", substring = true).assertExists()

            // The flow really went through the domain seams: the finish landed on the repository
            // with the played movetext, and the journal entry cleared once the cloud confirmed.
            val finishCall = seed.games.finishGameCalls.single()
            assertEquals(gameId, finishCall.first)
            assertEquals(GameResult.WHITE, finishCall.second)
            assertTrue(finishCall.third.contains("1. e4"), "finished PGN carries the played move")
            assertTrue(finishCall.third.contains("1-0"), "finished PGN carries the result token")
            assertEquals(listOf(gameId), seed.journal.clearedIds)
        }
}
