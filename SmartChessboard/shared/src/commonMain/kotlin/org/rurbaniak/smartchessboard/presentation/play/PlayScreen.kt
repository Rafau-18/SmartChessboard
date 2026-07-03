package org.rurbaniak.smartchessboard.presentation.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.chess.GameStatus
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.preferences.MoveListMode
import org.rurbaniak.smartchessboard.domain.preferences.effectiveMoveListMode
import org.rurbaniak.smartchessboard.presentation.board.BoardInteraction
import org.rurbaniak.smartchessboard.presentation.board.BoardPreferencesViewModel
import org.rurbaniak.smartchessboard.presentation.board.ChessBoardView
import org.rurbaniak.smartchessboard.presentation.board.PromotionPicker
import org.rurbaniak.smartchessboard.presentation.board.ResizableBoardBox
import org.rurbaniak.smartchessboard.presentation.components.AdaptiveScaffold
import org.rurbaniak.smartchessboard.presentation.components.BoardScreenScaffold
import org.rurbaniak.smartchessboard.presentation.components.MoveList
import org.rurbaniak.smartchessboard.presentation.components.SECTION_MAX_WIDTH
import org.rurbaniak.smartchessboard.presentation.layout.BoardArrangement
import org.rurbaniak.smartchessboard.presentation.layout.LocalWindowSizeClass
import org.rurbaniak.smartchessboard.presentation.layout.boardArrangement
import org.rurbaniak.smartchessboard.presentation.layout.boardResizeEnabled

@Composable
fun PlayScreen(
    gameId: String,
    onBack: () -> Unit,
    // Post-finish navigation (S-05): open the finished game in Replay, or return to History.
    onReviewGame: () -> Unit,
    onBackToHistory: () -> Unit,
) {
    // Keyed by game so reopening a different game never reuses a stale state machine.
    val viewModel = koinViewModel<PlayViewModel>(key = "play-$gameId") { parametersOf(gameId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val boardPrefs = koinViewModel<BoardPreferencesViewModel>()
    val boardSize by boardPrefs.boardSize.collectAsStateWithLifecycle()
    // Window-shape policies: the resize handle only on a true wide screen (never a landscape phone),
    // and the move-list default follows the container it renders in (side panel → TABLE).
    val windowSizeClass = LocalWindowSizeClass.current
    val boardResize = boardResizeEnabled(windowSizeClass)
    val inSidePanel = boardArrangement(windowSizeClass) == BoardArrangement.SidePane
    val moveListOverride by boardPrefs.moveListMode.collectAsStateWithLifecycle()
    val tableMoveList = effectiveMoveListMode(moveListOverride, inSidePanel) == MoveListMode.TABLE
    AdaptiveScaffold(
        title = { Text(titleFor(uiState)) },
        navigationIcon = {
            TextButton(onClick = onBack) {
                Text("Back")
            }
        },
        actions = {
            if (uiState is PlayUiState.Playing) {
                TextButton(onClick = viewModel::flipBoard) {
                    Text("Flip")
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                PlayUiState.Loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                PlayUiState.Error -> {
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

                is PlayUiState.Playing -> {
                    PlayingContent(
                        state = state,
                        boardResize = boardResize,
                        tableMoveList = tableMoveList,
                        boardSize = boardSize,
                        onBoardSizeChange = boardPrefs::setBoardSize,
                        onSquareTap = viewModel::onSquareTap,
                        onPromotionPick = viewModel::onPromotionPick,
                        onPromotionDismiss = viewModel::onPromotionDismiss,
                        onEndGameRequest = viewModel::onEndGameRequest,
                        onResultPick = viewModel::onResultPick,
                        onConfirmEndGame = viewModel::onConfirmEndGame,
                        onEndGameDismiss = viewModel::onEndGameDismiss,
                        onReviewGame = onReviewGame,
                        onBackToHistory = onBackToHistory,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayingContent(
    state: PlayUiState.Playing,
    boardResize: Boolean,
    tableMoveList: Boolean,
    boardSize: Float,
    onBoardSizeChange: (Float) -> Unit,
    onSquareTap: (Int) -> Unit,
    onPromotionPick: (PieceType) -> Unit,
    onPromotionDismiss: () -> Unit,
    onEndGameRequest: () -> Unit,
    onResultPick: (GameResult) -> Unit,
    onConfirmEndGame: () -> Unit,
    onEndGameDismiss: () -> Unit,
    onReviewGame: () -> Unit,
    onBackToHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoardScreenScaffold(
        banner = { StatusBanner(state = state, modifier = Modifier.fillMaxWidth()) },
        board = {
            ResizableBoardBox(
                isWide = boardResize,
                size = boardSize,
                onSizeChange = onBoardSizeChange,
            ) { boardModifier ->
                ChessBoardView(
                    position = state.position,
                    modifier = boardModifier,
                    orientation = state.orientation,
                    // A terminal position or a finished game freezes input — display-only, no highlights.
                    // A manual end (FR-018) on a non-terminal position is frozen via result, not terminal.
                    interaction =
                        if (state.terminal || state.result != null) {
                            null
                        } else {
                            BoardInteraction(
                                selectedSquare = state.selectedSquare,
                                targetSquares = state.targetSquares,
                                onSquareTap = onSquareTap,
                            )
                        },
                )
            }
        },
        modifier = modifier,
    ) {
        val sectionModifier = Modifier.widthIn(max = SECTION_MAX_WIDTH).fillMaxWidth()
        Spacer(Modifier.height(8.dp))
        SyncIndicator(syncPending = state.syncPending, modifier = sectionModifier)
        Spacer(Modifier.height(12.dp))
        EndGameSection(
            finished = state.result != null,
            onEndGameRequest = onEndGameRequest,
            onReviewGame = onReviewGame,
            onBackToHistory = onBackToHistory,
            modifier = sectionModifier,
        )
        Spacer(Modifier.height(12.dp))
        MoveList(
            sanMoves = state.sanMoves,
            currentPly = state.sanMoves.size,
            modifier = sectionModifier,
            tableMode = tableMoveList,
        )
    }

    state.pendingPromotion?.let { pending ->
        PromotionPicker(
            color = pending.color,
            onPick = onPromotionPick,
            onDismiss = onPromotionDismiss,
        )
    }

    state.endGamePrompt?.let { prompt ->
        EndGamePicker(
            prompt = prompt,
            onPick = onResultPick,
            onConfirm = onConfirmEndGame,
            onDismiss = onEndGameDismiss,
        )
    }
}

/**
 * The end-game control block under the board. While in progress it shows the manual "End game"
 * affordance (FR-018); once [finished] the board is frozen, so it offers the post-game actions —
 * open the game in Replay (Analyse) or return to History.
 */
@Composable
private fun EndGameSection(
    finished: Boolean,
    onEndGameRequest: () -> Unit,
    onReviewGame: () -> Unit,
    onBackToHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (finished) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onReviewGame, modifier = Modifier.weight(1f)) {
                Text("Analyse")
            }
            Button(onClick = onBackToHistory, modifier = Modifier.weight(1f)) {
                Text("Back to history")
            }
        }
    } else {
        // Secondary, guarded action (the dialog confirms before closing) — outlined to sit quietly
        // beneath the board without competing with piece interaction.
        OutlinedButton(onClick = onEndGameRequest, modifier = modifier) {
            Text("End game")
        }
    }
}

/**
 * Turn indicator while ongoing; check / checkmate / stalemate banner at terminal-or-check states.
 * A finished game ([PlayUiState.Playing.result] non-null) shows its recorded result — the
 * checkmate/stalemate phrasing already carries it for an auto-close, but a manual end (FR-018) on a
 * non-terminal position would otherwise still read "… to move", so the result is shown explicitly.
 */
@Composable
private fun StatusBanner(
    state: PlayUiState.Playing,
    modifier: Modifier = Modifier,
) {
    val sideToMove = state.position.sideToMove
    val result = state.result
    val (text, emphasized) =
        when {
            result != null && !state.terminal -> {
                finalResultText(result) to true
            }

            else -> {
                when (state.status) {
                    GameStatus.Ongoing -> "${colorName(sideToMove)} to move" to false

                    GameStatus.Check -> "${colorName(sideToMove)} to move — check" to true

                    // The side to move is mated, so the other color won.
                    GameStatus.Checkmate -> "Checkmate — ${colorName(sideToMove.opposite)} wins" to true

                    GameStatus.Stalemate -> "Stalemate — draw" to true
                }
            }
        }
    if (emphasized) {
        Surface(
            modifier = modifier,
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        Text(
            text,
            modifier = modifier,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** Non-blocking "sync pending" hint (contract §3.4: cloud sync is best-effort, off the move path). */
@Composable
private fun SyncIndicator(
    syncPending: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!syncPending) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            "Saving…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun colorName(color: Color): String = if (color == Color.WHITE) "White" else "Black"

/** Banner text for a finished game (matches the picker options and History's outcome phrasing). */
private fun finalResultText(result: GameResult): String =
    when (result) {
        GameResult.WHITE -> "White wins"
        GameResult.BLACK -> "Black wins"
        GameResult.DRAW -> "Draw"
    }

private fun titleFor(state: PlayUiState): String =
    when (state) {
        is PlayUiState.Playing -> "${state.whiteLabel} vs ${state.blackLabel}"
        else -> "Game"
    }
