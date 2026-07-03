package org.rurbaniak.smartchessboard.presentation.replay

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.rurbaniak.smartchessboard.domain.chess.pgn.PgnHeaders
import org.rurbaniak.smartchessboard.domain.preferences.BOARD_SIZE_DEFAULT
import org.rurbaniak.smartchessboard.domain.preferences.BOARD_SIZE_MAX
import org.rurbaniak.smartchessboard.domain.preferences.MoveListMode
import org.rurbaniak.smartchessboard.domain.preferences.effectiveMoveListMode
import org.rurbaniak.smartchessboard.presentation.board.BoardArrow
import org.rurbaniak.smartchessboard.presentation.board.BoardPreferencesViewModel
import org.rurbaniak.smartchessboard.presentation.board.ChessBoardView
import org.rurbaniak.smartchessboard.presentation.board.ResizableBoardBox
import org.rurbaniak.smartchessboard.presentation.board.parseUciArrow
import org.rurbaniak.smartchessboard.presentation.components.AdaptiveActionButton
import org.rurbaniak.smartchessboard.presentation.components.AdaptiveBackButton
import org.rurbaniak.smartchessboard.presentation.components.AdaptiveScaffold
import org.rurbaniak.smartchessboard.presentation.components.BoardScreenScaffold
import org.rurbaniak.smartchessboard.presentation.components.MoveList
import org.rurbaniak.smartchessboard.presentation.components.SECTION_MAX_WIDTH
import org.rurbaniak.smartchessboard.presentation.layout.BoardArrangement
import org.rurbaniak.smartchessboard.presentation.layout.LocalWindowSizeClass
import org.rurbaniak.smartchessboard.presentation.layout.boardArrangement
import org.rurbaniak.smartchessboard.presentation.layout.boardResizeEnabled
import org.rurbaniak.smartchessboard.presentation.layout.isHeightCompact

@Composable
fun ReplayScreen(
    gameId: String,
    onBack: () -> Unit,
) {
    // Keyed by game so reopening a different game never reuses a stale parse.
    val viewModel =
        koinViewModel<ReplayViewModel>(key = "replay-$gameId") { parametersOf(gameId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val boardPrefs = koinViewModel<BoardPreferencesViewModel>()
    val boardSize by boardPrefs.boardSize.collectAsStateWithLifecycle()
    val moveListOverride by boardPrefs.moveListMode.collectAsStateWithLifecycle()
    // The move list defaults to the lichess-style table when it renders in the side panel (landscape
    // phone or wide window) and to the compact inline flow in the portrait column; an explicit toggle
    // (persisted) overrides that. One effective value drives the top-bar control and the lists below.
    val inSidePanel = boardArrangement(LocalWindowSizeClass.current) == BoardArrangement.SidePane
    val effectiveMoveListMode = effectiveMoveListMode(moveListOverride, inSidePanel)
    AdaptiveScaffold(
        title = { Text(titleFor(uiState)) },
        navigationIcon = { AdaptiveBackButton(onBack) },
        actions = {
            (uiState as? ReplayUiState.Loaded)?.let { state ->
                // Shows the current layout and toggles to the other one (persisted).
                val table = effectiveMoveListMode == MoveListMode.TABLE
                AdaptiveActionButton(
                    label = if (table) "Table" else "Inline",
                    icon = if (table) Icons.Filled.TableRows else Icons.AutoMirrored.Filled.Notes,
                    onClick = {
                        boardPrefs.setMoveListMode(if (table) MoveListMode.INLINE else MoveListMode.TABLE)
                    },
                )
                AdaptiveActionButton(
                    label = "Analysis",
                    icon = Icons.Filled.Insights,
                    onClick = viewModel::toggleAnalysis,
                    selected = state.analysisEnabled,
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                ReplayUiState.Loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                ReplayUiState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Couldn't load this game.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = viewModel::retry) {
                            Text("Retry")
                        }
                    }
                }

                is ReplayUiState.Loaded -> {
                    LoadedReplay(
                        state = state,
                        boardSize = boardSize,
                        onBoardSizeChange = boardPrefs::setBoardSize,
                        tableMoveList = effectiveMoveListMode == MoveListMode.TABLE,
                        onStart = viewModel::goToStart,
                        onStepBack = viewModel::stepBack,
                        onStepForward = viewModel::stepForward,
                        onEnd = viewModel::goToEnd,
                        onJump = viewModel::jumpTo,
                        onRetryEval = viewModel::retryEval,
                    )
                }
            }
        }
    }
}

/**
 * The loaded game through the shared [BoardScreenScaffold]: the player line in the banner slot, the
 * board (+ eval bar) in the board slot, and the transport / eval / move-list sections in the panel.
 * The side-pane vs column arrangement comes from the shared window policy — a landscape phone and a
 * wide window both put the board beside the panel — replacing the private 840 dp two-pane breakpoint.
 */
@Composable
internal fun LoadedReplay(
    state: ReplayUiState.Loaded,
    boardSize: Float,
    onBoardSizeChange: (Float) -> Unit,
    tableMoveList: Boolean,
    onStart: () -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onEnd: () -> Unit,
    onJump: (Int) -> Unit,
    onRetryEval: () -> Unit,
) {
    val windowSizeClass = LocalWindowSizeClass.current
    val boardResize = boardResizeEnabled(windowSizeClass)
    val inSidePanel = boardArrangement(windowSizeClass) == BoardArrangement.SidePane
    // At compact height the transport renders dense — panel width is board budget there.
    val compactTransport = windowSizeClass.isHeightCompact
    // Dragging the board past its default size widens the side-pane container past the default
    // margins (BoardScreenScaffold.contentWidthExpansion). Zero where the resize handle is
    // disabled — the stored fraction is ignored there, so it must not widen anything either.
    val enlargement =
        if (boardResize) {
            ((boardSize - BOARD_SIZE_DEFAULT) / (BOARD_SIZE_MAX - BOARD_SIZE_DEFAULT)).coerceIn(0f, 1f)
        } else {
            0f
        }
    BoardScreenScaffold(
        banner = { PlayerLine(state.game.headers) },
        board = {
            BoardWithEvalBar(
                state = state,
                boardResize = boardResize,
                boardSize = boardSize,
                onBoardSizeChange = onBoardSizeChange,
            )
        },
        contentWidthExpansion = enlargement,
    ) {
        val sectionModifier = Modifier.widthIn(max = SECTION_MAX_WIDTH).fillMaxWidth()
        if (inSidePanel) {
            // Beside the board the transport leads the panel, so stepping through plies never needs
            // a scroll; the eval text follows it.
            if (state.isTruncated) {
                Spacer(Modifier.height(8.dp))
                TruncationBanner(modifier = sectionModifier)
            }
            Spacer(Modifier.height(12.dp))
            TransportControls(
                state = state,
                onStart = onStart,
                onBack = onStepBack,
                onForward = onStepForward,
                onEnd = onEnd,
                modifier = sectionModifier,
                compact = compactTransport,
            )
            if (state.analysisEnabled) {
                Spacer(Modifier.height(12.dp))
                // Crossfade the panel between eval states (Loading ↔ Evaluated ↔ Unavailable …).
                Crossfade(targetState = state.currentEval, modifier = sectionModifier, label = "evalPanel") { eval ->
                    EvalPanel(eval = eval, onRetry = onRetryEval)
                }
            }
        } else {
            // The portrait column keeps its shipped order: eval text right under the board's eval
            // bar, then the truncation notice, then the transport row.
            if (state.analysisEnabled) {
                Spacer(Modifier.height(8.dp))
                // Crossfade the panel between eval states (Loading ↔ Evaluated ↔ Unavailable …).
                Crossfade(targetState = state.currentEval, modifier = sectionModifier, label = "evalPanel") { eval ->
                    EvalPanel(eval = eval, onRetry = onRetryEval)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (state.isTruncated) {
                TruncationBanner(modifier = sectionModifier)
                Spacer(Modifier.height(12.dp))
            }
            TransportControls(
                state = state,
                onStart = onStart,
                onBack = onStepBack,
                onForward = onStepForward,
                onEnd = onEnd,
                modifier = sectionModifier,
            )
        }
        Spacer(Modifier.height(16.dp))
        MoveList(
            sanMoves = state.game.sanMoves,
            currentPly = state.currentPly,
            onJump = onJump,
            modifier = sectionModifier,
            tableMode = tableMoveList,
        )
    }
}

/**
 * The board, sized by [ResizableBoardBox] (auto-fit; corner drag handle where [boardResize] holds),
 * with the vertical eval bar on its right. The board's modifier is a concrete square, so the inner
 * `IntrinsicSize.Min` Row gives the eval bar the board's exact height — without querying the
 * surrounding `BoxWithConstraints` for intrinsics (which a SubcomposeLayout can't answer). The bar
 * appears only while analysis is enabled.
 */
@Composable
private fun BoardWithEvalBar(
    state: ReplayUiState.Loaded,
    boardResize: Boolean,
    boardSize: Float,
    onBoardSizeChange: (Float) -> Unit,
) {
    // When the eval bar is shown, reserve its width (+ the Row gap) so the board leaves room for it —
    // otherwise a full-width board pushes the bar off-screen.
    val reserved = if (state.analysisEnabled) EvalBarWidth + 8.dp else 0.dp
    ResizableBoardBox(
        isWide = boardResize,
        size = boardSize,
        onSizeChange = onBoardSizeChange,
        reservedWidth = reserved,
    ) { boardModifier ->
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChessBoardView(
                position = state.position,
                modifier = boardModifier,
                bestMoveArrow = bestMoveArrowFor(state),
            )
            if (state.analysisEnabled) {
                EvalBar(eval = state.currentEval, modifier = Modifier.fillMaxHeight())
            }
        }
    }
}

/** The arrow renders only for a resolved evaluation of the viewed ply with a parseable best move. */
private fun bestMoveArrowFor(state: ReplayUiState.Loaded): BoardArrow? =
    (state.currentEval as? PlyEvalState.Evaluated)?.bestMoveUci?.let(::parseUciArrow)

@Composable
private fun PlayerLine(headers: PgnHeaders) {
    val white = headers.white ?: "White"
    val black = headers.black ?: "Black"
    val result = headers.result?.takeIf { it.isNotBlank() && it != "*" }
    Text(
        text = if (result != null) "$white vs $black · $result" else "$white vs $black",
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun TruncationBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            "This record contains an invalid move — replay is shortened to the last valid position.",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun TransportControls(
    state: ReplayUiState.Loaded,
    onStart: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // At compact height every dp is board budget: 32 dp-tall buttons with tight padding instead of
    // the default 40 dp (the touch target still meets the interactive minimum via M3's enforcement).
    val buttonHeight = if (compact) Modifier.height(32.dp) else Modifier
    val contentPadding =
        if (compact) PaddingValues(horizontal = 8.dp, vertical = 4.dp) else ButtonDefaults.ContentPadding
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onStart,
            enabled = state.canStepBack,
            modifier = Modifier.weight(1f).then(buttonHeight),
            contentPadding = contentPadding,
        ) {
            Text("|<")
        }
        Button(
            onClick = onBack,
            enabled = state.canStepBack,
            modifier = Modifier.weight(1f).then(buttonHeight),
            contentPadding = contentPadding,
        ) {
            Text("<")
        }
        Button(
            onClick = onForward,
            enabled = state.canStepForward,
            modifier = Modifier.weight(1f).then(buttonHeight),
            contentPadding = contentPadding,
        ) {
            Text(">")
        }
        Button(
            onClick = onEnd,
            enabled = state.canStepForward,
            modifier = Modifier.weight(1f).then(buttonHeight),
            contentPadding = contentPadding,
        ) {
            Text(">|")
        }
    }
}

private fun titleFor(state: ReplayUiState): String =
    when (state) {
        is ReplayUiState.Loaded -> {
            val h = state.game.headers
            "${h.white ?: "White"} vs ${h.black ?: "Black"}"
        }

        else -> {
            "Replay"
        }
    }
