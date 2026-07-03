package org.rurbaniak.smartchessboard.presentation.replay

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import org.rurbaniak.smartchessboard.presentation.board.rememberIsWideScreen
import org.rurbaniak.smartchessboard.presentation.components.AdaptiveScaffold
import org.rurbaniak.smartchessboard.presentation.components.CONTENT_MAX_WIDTH
import org.rurbaniak.smartchessboard.presentation.components.MoveList
import org.rurbaniak.smartchessboard.presentation.components.SECTION_MAX_WIDTH
import org.rurbaniak.smartchessboard.presentation.components.SIDE_PANEL_MAX_WIDTH

/** Material "expanded" breakpoint — at and above this width ReplayScreen lays out as two panes. */
private val TWO_PANE_MIN_WIDTH = 840.dp

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
    // The move list defaults to the lichess-style table on wide screens and the compact inline flow on
    // phones; an explicit toggle (persisted) overrides that. One effective value drives the top-bar
    // control and the lists below.
    val effectiveMoveListMode = effectiveMoveListMode(moveListOverride, rememberIsWideScreen())
    AdaptiveScaffold(
        title = { Text(titleFor(uiState)) },
        navigationIcon = {
            TextButton(onClick = onBack) {
                Text("Back")
            }
        },
        actions = {
            (uiState as? ReplayUiState.Loaded)?.let { state ->
                // Shows the current layout and toggles to the other one (persisted).
                TextButton(
                    onClick = {
                        boardPrefs.setMoveListMode(
                            if (effectiveMoveListMode == MoveListMode.TABLE) {
                                MoveListMode.INLINE
                            } else {
                                MoveListMode.TABLE
                            },
                        )
                    },
                ) {
                    Text(if (effectiveMoveListMode == MoveListMode.TABLE) "Table" else "Inline")
                }
                TextButton(onClick = viewModel::toggleAnalysis) {
                    Text(
                        "Analysis",
                        fontWeight = if (state.analysisEnabled) FontWeight.Bold else FontWeight.Normal,
                        color =
                            if (state.analysisEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
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
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The two-pane breakpoint doubles as the board's "wide" flag — at/above it the board auto-fits
        // and gets a resize handle; below it the board is full-width auto-fit with no handle.
        val isWide = maxWidth >= TWO_PANE_MIN_WIDTH
        if (isWide) {
            WideReplay(
                state,
                boardSize,
                onBoardSizeChange,
                tableMoveList,
                onStart,
                onStepBack,
                onStepForward,
                onEnd,
                onJump,
                onRetryEval,
            )
        } else {
            NarrowReplay(
                state,
                boardSize,
                onBoardSizeChange,
                tableMoveList,
                onStart,
                onStepBack,
                onStepForward,
                onEnd,
                onJump,
                onRetryEval,
            )
        }
    }
}

/** The phone layout: one scrolling column, the eval section between board and transport controls. */
@Composable
private fun NarrowReplay(
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
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val sectionModifier = Modifier.widthIn(max = SECTION_MAX_WIDTH).fillMaxWidth()
        PlayerLine(state.game.headers)
        Spacer(Modifier.height(12.dp))
        // Phones are never "wide": full-width auto-fit board, no resize handle.
        BoardWithEvalBar(state = state, isWide = false, boardSize = boardSize, onBoardSizeChange = onBoardSizeChange)
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
 * The expanded layout (web/tablet ≥ [TWO_PANE_MIN_WIDTH]): board + eval bar + transport on the
 * left, eval panel + move list on the right. Same state, no layout-specific behavior.
 */
@Composable
private fun WideReplay(
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
    // Centre the two-pane content. The width cap is the default margin, but it expands toward the full
    // window as the board is enlarged past its default — so dragging the board bigger can spill past
    // the default margins instead of being trapped inside them.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val fullWidth = maxWidth
        val enlargement = ((boardSize - BOARD_SIZE_DEFAULT) / (BOARD_SIZE_MAX - BOARD_SIZE_DEFAULT)).coerceIn(0f, 1f)
        val containerMax =
            if (fullWidth <= CONTENT_MAX_WIDTH) {
                fullWidth
            } else {
                CONTENT_MAX_WIDTH + (fullWidth - CONTENT_MAX_WIDTH) * enlargement
            }
        Row(
            modifier =
                Modifier
                    .widthIn(max = containerMax)
                    .fillMaxSize()
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Board column takes the remaining width; the side panel is bounded so the move list never
            // takes half the screen.
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val sectionModifier = Modifier.widthIn(max = SECTION_MAX_WIDTH).fillMaxWidth()
                PlayerLine(state.game.headers)
                Spacer(Modifier.height(12.dp))
                // Wide layout: the board auto-fits the pane (bounded by viewport height) and gets a resize handle.
                BoardWithEvalBar(
                    state = state,
                    isWide = true,
                    boardSize = boardSize,
                    onBoardSizeChange = onBoardSizeChange,
                )
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
            Column(
                modifier =
                    Modifier
                        .widthIn(max = SIDE_PANEL_MAX_WIDTH)
                        .verticalScroll(rememberScrollState()),
            ) {
                if (state.analysisEnabled) {
                    // Crossfade the panel between eval states (Loading ↔ Evaluated ↔ Unavailable …).
                    Crossfade(
                        targetState = state.currentEval,
                        modifier = Modifier.fillMaxWidth(),
                        label = "evalPanel",
                    ) { eval ->
                        EvalPanel(eval = eval, onRetry = onRetryEval)
                    }
                    Spacer(Modifier.height(16.dp))
                }
                MoveList(
                    sanMoves = state.game.sanMoves,
                    currentPly = state.currentPly,
                    onJump = onJump,
                    modifier = Modifier.fillMaxWidth(),
                    tableMode = tableMoveList,
                )
            }
        }
    }
}

/**
 * The board, sized by [ResizableBoardBox] (auto-fit + resize handle on wide screens), with the
 * vertical eval bar on its right. The board's modifier is a concrete square, so the inner
 * `IntrinsicSize.Min` Row gives the eval bar the board's exact height — without querying the
 * surrounding `BoxWithConstraints` for intrinsics (which a SubcomposeLayout can't answer). The bar
 * appears only while analysis is enabled.
 */
@Composable
private fun BoardWithEvalBar(
    state: ReplayUiState.Loaded,
    isWide: Boolean,
    boardSize: Float,
    onBoardSizeChange: (Float) -> Unit,
) {
    // When the eval bar is shown, reserve its width (+ the Row gap) so the board leaves room for it —
    // otherwise on a phone the full-width board pushes the bar off-screen.
    val reserved = if (state.analysisEnabled) EvalBarWidth + 8.dp else 0.dp
    ResizableBoardBox(
        isWide = isWide,
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
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onStart, enabled = state.canStepBack, modifier = Modifier.weight(1f)) {
            Text("|<")
        }
        Button(onClick = onBack, enabled = state.canStepBack, modifier = Modifier.weight(1f)) {
            Text("<")
        }
        Button(onClick = onForward, enabled = state.canStepForward, modifier = Modifier.weight(1f)) {
            Text(">")
        }
        Button(onClick = onEnd, enabled = state.canStepForward, modifier = Modifier.weight(1f)) {
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
