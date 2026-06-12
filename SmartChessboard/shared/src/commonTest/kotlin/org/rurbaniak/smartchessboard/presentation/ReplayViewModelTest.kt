package org.rurbaniak.smartchessboard.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.rurbaniak.smartchessboard.domain.chess.GameStatus
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.toFen
import org.rurbaniak.smartchessboard.domain.eval.EvalOutcome
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.presentation.replay.PlyEvalState
import org.rurbaniak.smartchessboard.presentation.replay.ReplayUiState
import org.rurbaniak.smartchessboard.presentation.replay.ReplayViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.rurbaniak.smartchessboard.domain.games.GameStatus as RecordStatus

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayViewModelTest {
    private lateinit var repository: FakeGamesRepository
    private lateinit var evalRepository: FakeEvalRepository

    // One TestDispatcher drives both Main (viewModelScope) and the injected parse dispatcher. Sharing
    // the scheduler is what lets advanceUntilIdle() run the off-Main parse to completion — without it
    // a real Dispatchers.Default parse escapes virtual time and the state stays Loading.
    private lateinit var dispatcher: TestDispatcher

    // Four-ply opening; sanMoves.size == 4, positions.size == 5.
    private val openingPgn = "1. e4 e5 2. Nf3 Nc6"

    // Nf6 is unreachable by either white knight on move 2 → parser truncates at ply index 2,
    // leaving sanMoves == [e4, e5] (a "truncated range" of two plies).
    private val illegalPgn = "1. e4 e5 2. Nf6"

    // Fool's mate — the final position is checkmate (terminal short-circuit scenarios).
    private val foolsMatePgn = "1. f3 e5 2. g4 Qh4#"

    private val evaluatedOutcome =
        EvalOutcome.Evaluated(evalCp = 22, mate = null, bestMoveUci = "e2e4", source = "lichess", depth = 36)

    @BeforeTest
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        repository = FakeGamesRepository()
        evalRepository = FakeEvalRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun record(
        id: String,
        pgn: String,
    ) = GameRecord(
        id = id,
        createdAt = "2026-06-12T12:00:00+00:00",
        mode = GameMode.DIGITAL,
        status = RecordStatus.FINISHED,
        result = GameResult.WHITE,
        whiteLabel = "White",
        blackLabel = "Black",
        pgn = pgn,
    )

    private fun loadedViewModel(
        id: String,
        pgn: String,
    ): ReplayViewModel {
        repository.records = mapOf(id to record(id, pgn))
        val viewModel =
            ReplayViewModel(
                gameId = id,
                gamesRepository = repository,
                evalRepository = evalRepository,
                parseDispatcher = dispatcher,
            )
        return viewModel
    }

    private fun loaded(state: ReplayUiState): ReplayUiState.Loaded = assertIs<ReplayUiState.Loaded>(state)

    @Test
    fun startsInLoadingState() =
        runTest {
            val viewModel = loadedViewModel("g1", openingPgn)
            assertEquals(ReplayUiState.Loading, viewModel.uiState.value)
        }

    @Test
    fun loadSuccessResolvesToLoadedAtPlyZero() =
        runTest {
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()

            val state = loaded(viewModel.uiState.value)
            assertEquals(0, state.currentPly)
            assertEquals(Position.start(), state.position)
            assertEquals(4, state.game.sanMoves.size)
            assertFalse(state.canStepBack)
            assertTrue(state.canStepForward)
            assertFalse(state.isTruncated)
        }

    @Test
    fun repositoryFailureResolvesToErrorThenRetryReloads() =
        runTest {
            repository.records = mapOf("g1" to record("g1", openingPgn))
            repository.shouldFail = true
            val viewModel =
                ReplayViewModel(
                    gameId = "g1",
                    gamesRepository = repository,
                    evalRepository = evalRepository,
                    parseDispatcher = dispatcher,
                )
            advanceUntilIdle()
            assertEquals(ReplayUiState.Error, viewModel.uiState.value)

            repository.shouldFail = false
            viewModel.retry()
            assertEquals(ReplayUiState.Loading, viewModel.uiState.value)
            advanceUntilIdle()
            assertIs<ReplayUiState.Loaded>(viewModel.uiState.value)
        }

    @Test
    fun stepForwardAndBackMoveOnePly() =
        runTest {
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()

            viewModel.stepForward()
            assertEquals(1, loaded(viewModel.uiState.value).currentPly)
            viewModel.stepForward()
            assertEquals(2, loaded(viewModel.uiState.value).currentPly)
            viewModel.stepBack()
            assertEquals(1, loaded(viewModel.uiState.value).currentPly)
        }

    @Test
    fun navigationClampsAtBothBounds() =
        runTest {
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()

            viewModel.stepBack()
            assertEquals(0, loaded(viewModel.uiState.value).currentPly)

            viewModel.goToEnd()
            assertEquals(4, loaded(viewModel.uiState.value).currentPly)
            viewModel.stepForward()
            assertEquals(4, loaded(viewModel.uiState.value).currentPly)

            viewModel.jumpTo(99)
            assertEquals(4, loaded(viewModel.uiState.value).currentPly)
            viewModel.jumpTo(-5)
            assertEquals(0, loaded(viewModel.uiState.value).currentPly)
            viewModel.jumpTo(2)
            assertEquals(2, loaded(viewModel.uiState.value).currentPly)
        }

    @Test
    fun goToEndLandsOnFinalPosition() =
        runTest {
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()

            viewModel.goToEnd()
            val state = loaded(viewModel.uiState.value)
            assertEquals(state.game.positions.last(), state.position)
            assertFalse(state.canStepForward)
            assertTrue(state.canStepBack)
        }

    @Test
    fun goToStartReturnsToPlyZero() =
        runTest {
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()

            viewModel.goToEnd()
            viewModel.goToStart()
            assertEquals(0, loaded(viewModel.uiState.value).currentPly)
        }

    @Test
    fun truncatedGameExposesNoticeAndClampsToTruncatedRange() =
        runTest {
            val viewModel = loadedViewModel("g1", illegalPgn)
            advanceUntilIdle()

            val state = loaded(viewModel.uiState.value)
            assertTrue(state.isTruncated)
            assertEquals(2, state.game.sanMoves.size)

            viewModel.goToEnd()
            assertEquals(2, loaded(viewModel.uiState.value).currentPly)
            viewModel.jumpTo(10)
            assertEquals(2, loaded(viewModel.uiState.value).currentPly)
            assertFalse(loaded(viewModel.uiState.value).canStepForward)
        }

    @Test
    fun emptyPgnIsLoadedWithSinglePositionAndDisabledForward() =
        runTest {
            val viewModel = loadedViewModel("g1", "")
            advanceUntilIdle()

            val state = loaded(viewModel.uiState.value)
            assertEquals(0, state.currentPly)
            assertTrue(state.game.sanMoves.isEmpty())
            assertEquals(Position.start(), state.position)
            assertFalse(state.canStepForward)
            assertFalse(state.canStepBack)
        }

    // ---- Analysis machine (S-03) ----

    @Test
    fun toggleOnEvaluatesCurrentPly() =
        runTest {
            evalRepository.defaultOutcome = evaluatedOutcome
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()

            viewModel.toggleAnalysis()
            advanceUntilIdle()

            val state = loaded(viewModel.uiState.value)
            assertTrue(state.analysisEnabled)
            val eval = assertIs<PlyEvalState.Evaluated>(state.currentEval)
            assertEquals(22, eval.evalCp)
            assertEquals("e2e4", eval.bestMoveUci)
            assertEquals(1, evalRepository.evaluateCalls)
            assertEquals(Position.start().toFen(), evalRepository.requestedFens.single())
        }

    @Test
    fun plyChangeFetchesUncachedPly() =
        runTest {
            evalRepository.defaultOutcome = evaluatedOutcome
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()
            viewModel.toggleAnalysis()
            advanceUntilIdle()

            viewModel.stepForward()
            advanceUntilIdle()

            val state = loaded(viewModel.uiState.value)
            assertIs<PlyEvalState.Evaluated>(state.currentEval)
            assertEquals(2, evalRepository.evaluateCalls)
        }

    @Test
    fun revisitedPlyIsServedFromSessionCache() =
        runTest {
            evalRepository.defaultOutcome = evaluatedOutcome
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()
            viewModel.toggleAnalysis()
            advanceUntilIdle()
            viewModel.stepForward()
            advanceUntilIdle()

            viewModel.stepBack()
            advanceUntilIdle()

            val state = loaded(viewModel.uiState.value)
            assertIs<PlyEvalState.Evaluated>(state.currentEval)
            assertEquals(2, evalRepository.evaluateCalls)
        }

    @Test
    fun inFlightRequestIsCanceledOnPlyChangeAndRevisitRefetches() =
        runTest {
            evalRepository.defaultOutcome = evaluatedOutcome
            // Hold only the first request in flight; later ones answer immediately.
            evalRepository.onEvaluate = { if (evalRepository.evaluateCalls == 1) delay(60_000) }
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()

            viewModel.toggleAnalysis()
            runCurrent() // first request reaches the fake and suspends
            viewModel.stepForward() // cancels it, fetches ply 1
            advanceUntilIdle()

            var state = loaded(viewModel.uiState.value)
            assertIs<PlyEvalState.Evaluated>(state.currentEval)
            assertEquals(PlyEvalState.Loading, state.evals[0], "canceled ply must not hold a resolved eval")
            assertEquals(2, evalRepository.evaluateCalls)

            viewModel.stepBack() // stale Loading is not a cache hit → refetch
            advanceUntilIdle()
            state = loaded(viewModel.uiState.value)
            assertIs<PlyEvalState.Evaluated>(state.currentEval)
            assertEquals(3, evalRepository.evaluateCalls)
        }

    @Test
    fun noEvalMapsToNoEvalState() =
        runTest {
            evalRepository.defaultOutcome = EvalOutcome.NoEval
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()

            viewModel.toggleAnalysis()
            advanceUntilIdle()

            assertEquals(PlyEvalState.NoEval, loaded(viewModel.uiState.value).currentEval)
        }

    @Test
    fun unavailableMapsToUnavailableState() =
        runTest {
            evalRepository.defaultOutcome = EvalOutcome.TemporarilyUnavailable(retryAfterSeconds = 42)
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()

            viewModel.toggleAnalysis()
            advanceUntilIdle()

            assertEquals(
                PlyEvalState.Unavailable(retryAfterSeconds = 42),
                loaded(viewModel.uiState.value).currentEval,
            )
        }

    @Test
    fun retryEvalRefetchesCurrentPly() =
        runTest {
            evalRepository.defaultOutcome = EvalOutcome.TemporarilyUnavailable(retryAfterSeconds = null)
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()
            viewModel.toggleAnalysis()
            advanceUntilIdle()
            assertIs<PlyEvalState.Unavailable>(loaded(viewModel.uiState.value).currentEval)

            evalRepository.defaultOutcome = evaluatedOutcome
            viewModel.retryEval()
            advanceUntilIdle()

            assertIs<PlyEvalState.Evaluated>(loaded(viewModel.uiState.value).currentEval)
            assertEquals(2, evalRepository.evaluateCalls)
        }

    @Test
    fun terminalPositionShortCircuitsWithoutRepositoryCall() =
        runTest {
            val viewModel = loadedViewModel("g1", foolsMatePgn)
            advanceUntilIdle()

            viewModel.goToEnd()
            viewModel.toggleAnalysis()
            advanceUntilIdle()

            assertEquals(
                PlyEvalState.Terminal(GameStatus.Checkmate),
                loaded(viewModel.uiState.value).currentEval,
            )
            assertEquals(0, evalRepository.evaluateCalls)
        }

    @Test
    fun toggleOffStopsFetchingAndRetainsCache() =
        runTest {
            evalRepository.defaultOutcome = evaluatedOutcome
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()
            viewModel.toggleAnalysis()
            advanceUntilIdle()

            viewModel.toggleAnalysis() // off
            viewModel.stepForward()
            advanceUntilIdle()

            val state = loaded(viewModel.uiState.value)
            assertFalse(state.analysisEnabled)
            assertNull(state.currentEval)
            assertEquals(1, evalRepository.evaluateCalls)
            assertIs<PlyEvalState.Evaluated>(state.evals[0], "session cache survives toggling off")
        }

    @Test
    fun reEnableReusesSessionCache() =
        runTest {
            evalRepository.defaultOutcome = evaluatedOutcome
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()
            viewModel.toggleAnalysis()
            advanceUntilIdle()

            viewModel.toggleAnalysis() // off
            viewModel.toggleAnalysis() // on again at the same ply
            advanceUntilIdle()

            val state = loaded(viewModel.uiState.value)
            assertIs<PlyEvalState.Evaluated>(state.currentEval)
            assertEquals(1, evalRepository.evaluateCalls)
        }
}
