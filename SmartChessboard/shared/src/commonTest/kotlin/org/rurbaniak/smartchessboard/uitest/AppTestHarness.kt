package org.rurbaniak.smartchessboard.uitest

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Dispatchers
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.rurbaniak.smartchessboard.App
import org.rurbaniak.smartchessboard.di.appModules
import org.rurbaniak.smartchessboard.domain.auth.AuthRepository
import org.rurbaniak.smartchessboard.domain.auth.SessionState
import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.eval.EvalRepository
import org.rurbaniak.smartchessboard.domain.games.GameJournal
import org.rurbaniak.smartchessboard.domain.games.GamesRepository
import org.rurbaniak.smartchessboard.presentation.FakeAuthRepository
import org.rurbaniak.smartchessboard.presentation.FakeEvalRepository
import org.rurbaniak.smartchessboard.presentation.FakeGameJournal
import org.rurbaniak.smartchessboard.presentation.FakeGamesRepository
import org.rurbaniak.smartchessboard.presentation.play.PlayViewModel

/**
 * The fake data layer one smoke test drives: the existing hand-written fakes, pre-wired to the
 * state every flow starts from — a signed-in session (the smoke flows exercise the app past the
 * auth gate, not the sign-in itself). Tests script the fakes directly (seed games, stub the created
 * record) and assert against their call logs.
 */
class FakeSeed {
    val auth = FakeAuthRepository().apply { sessionFlow.value = SessionState.SignedIn("uitest-user") }
    val games = FakeGamesRepository()
    val eval = FakeEvalRepository()
    val journal = FakeGameJournal()
}

/**
 * Composes the production [App] root under compose.uiTest v2 with Koin started from the production
 * [appModules] plus an override module that replaces the data layer at the repository seams with
 * [seed]'s fakes. `SupabaseClient` (and the BLE adapter) stay lazy `single {}` definitions nothing
 * resolves, so the suite runs without any Supabase credentials. [Settings] is overridden with an
 * in-memory [MapSettings] so the journal/preferences bindings never touch platform storage (or, on
 * Android, a Koin `androidContext` this harness does not provide). Koin is torn down in a `finally`
 * so one test's graph can never leak into the next.
 */
@OptIn(ExperimentalTestApi::class)
fun runAppTest(
    seed: FakeSeed = FakeSeed(),
    body: suspend ComposeUiTest.() -> Unit,
) = runComposeUiTest {
    startKoin {
        allowOverride(true)
        modules(
            appModules +
                module {
                    single<Settings> { MapSettings() }
                    single<AuthRepository> { seed.auth }
                    single<GamesRepository> { seed.games }
                    single<EvalRepository> { seed.eval }
                    single<GameJournal> { seed.journal }
                    // Same production PlayViewModel, but with parsePgn pinned to Main.immediate —
                    // no dispatch hop at all (viewModelScope already runs on Main.immediate). On
                    // wasm the browser has one thread and a withContext hop (Default, or even
                    // dispatching Main) escapes what waitUntil pumps, so the load parks forever on
                    // the Loading spinner. This is exactly what the injectable parseDispatcher
                    // exists for (see PlayViewModel).
                    viewModel { (gameId: String) ->
                        PlayViewModel(
                            gameId = gameId,
                            gamesRepository = get(),
                            autoSaver = get(),
                            parseDispatcher = Dispatchers.Main.immediate,
                        )
                    }
                },
        )
    }
    try {
        setContent { App() }
        body()
    } finally {
        stopKoin()
    }
}

/**
 * The chrome-agnostic action-button matcher: [org.rurbaniak.smartchessboard.presentation.components.AdaptiveActionButton]
 * renders a labelled [androidx.compose.material3.TextButton] in the top bar but an icon-only button
 * (label as content description) in the compact-height left rail — one matcher finds it in either
 * chrome, so the smoke flows don't encode the window shape.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.onAction(label: String): SemanticsNodeInteraction =
    onNode(hasClickAction() and (hasText(label) or hasContentDescription(label)))

/**
 * Taps the center of [square] (algebraic, e.g. `"e2"`) on the `chess-board`-tagged node. The board
 * Canvas has no per-square semantics, so the tap is a touch at a computed offset: cell (column,
 * rowFromTop) mirrors `ChessBoardView.squareAt` for [orientation] (white bottom by default), and
 * the center is `(index + 0.5) / 8` of the node's edge.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.tapSquare(
    square: String,
    orientation: Color = Color.WHITE,
) {
    require(square.length == 2 && square[0] in 'a'..'h' && square[1] in '1'..'8') {
        "not an algebraic square: $square"
    }
    val file = square[0] - 'a'
    val rank = square[1] - '1'
    val column = if (orientation == Color.WHITE) file else 7 - file
    val rowFromTop = if (orientation == Color.WHITE) 7 - rank else rank
    onNodeWithTag("chess-board").performTouchInput {
        click(
            Offset(
                x = (column + 0.5f) * (width / 8f),
                y = (rowFromTop + 0.5f) * (height / 8f),
            ),
        )
    }
}
