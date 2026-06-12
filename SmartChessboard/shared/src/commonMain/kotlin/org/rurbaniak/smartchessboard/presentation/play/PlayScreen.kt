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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import org.rurbaniak.smartchessboard.presentation.board.BoardInteraction
import org.rurbaniak.smartchessboard.presentation.board.ChessBoardView
import org.rurbaniak.smartchessboard.presentation.board.PromotionPicker
import org.rurbaniak.smartchessboard.presentation.components.MoveList

/** Caps the board on wide screens (web/desktop) so it doesn't stretch edge-to-edge (matches Replay). */
private val BOARD_MAX_WIDTH = 480.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayScreen(
    gameId: String,
    onBack: () -> Unit,
) {
    // Keyed by game so reopening a different game never reuses a stale state machine.
    val viewModel = koinViewModel<PlayViewModel>(key = "play-$gameId") { parametersOf(gameId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
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
            )
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
                        onSquareTap = viewModel::onSquareTap,
                        onPromotionPick = viewModel::onPromotionPick,
                        onPromotionDismiss = viewModel::onPromotionDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayingContent(
    state: PlayUiState.Playing,
    onSquareTap: (Int) -> Unit,
    onPromotionPick: (PieceType) -> Unit,
    onPromotionDismiss: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val sectionModifier = Modifier.widthIn(max = BOARD_MAX_WIDTH).fillMaxWidth()
        StatusBanner(state = state, modifier = sectionModifier)
        Spacer(Modifier.height(12.dp))
        ChessBoardView(
            position = state.position,
            modifier = sectionModifier,
            orientation = state.orientation,
            // A terminal position freezes input — display-only, no highlights.
            interaction =
                if (state.terminal) {
                    null
                } else {
                    BoardInteraction(
                        selectedSquare = state.selectedSquare,
                        targetSquares = state.targetSquares,
                        onSquareTap = onSquareTap,
                    )
                },
        )
        Spacer(Modifier.height(8.dp))
        SyncIndicator(syncPending = state.syncPending, modifier = sectionModifier)
        Spacer(Modifier.height(8.dp))
        MoveList(
            sanMoves = state.sanMoves,
            currentPly = state.sanMoves.size,
            modifier = sectionModifier,
        )
    }

    state.pendingPromotion?.let { pending ->
        PromotionPicker(
            color = pending.color,
            onPick = onPromotionPick,
            onDismiss = onPromotionDismiss,
        )
    }
}

/** Turn indicator while ongoing; check / checkmate / stalemate banner at terminal-or-check states. */
@Composable
private fun StatusBanner(
    state: PlayUiState.Playing,
    modifier: Modifier = Modifier,
) {
    val sideToMove = state.position.sideToMove
    val (text, emphasized) =
        when (state.status) {
            GameStatus.Ongoing -> "${colorName(sideToMove)} to move" to false

            GameStatus.Check -> "${colorName(sideToMove)} to move — check" to true

            // The side to move is mated, so the other color won.
            GameStatus.Checkmate -> "Checkmate — ${colorName(sideToMove.opposite)} wins" to true

            GameStatus.Stalemate -> "Stalemate — draw" to true
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

private fun titleFor(state: PlayUiState): String =
    when (state) {
        is PlayUiState.Playing -> "${state.whiteLabel} vs ${state.blackLabel}"
        else -> "Game"
    }
