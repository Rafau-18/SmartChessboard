package org.rurbaniak.smartchessboard.presentation.physical

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.rurbaniak.smartchessboard.domain.board.toOccupancy
import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.chess.GameStatus
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.preferences.MoveListMode
import org.rurbaniak.smartchessboard.domain.preferences.effectiveMoveListMode
import org.rurbaniak.smartchessboard.presentation.board.BoardPreferencesViewModel
import org.rurbaniak.smartchessboard.presentation.board.ChessBoardView
import org.rurbaniak.smartchessboard.presentation.board.PromotionPicker
import org.rurbaniak.smartchessboard.presentation.board.ReedDiagnosticsGrid
import org.rurbaniak.smartchessboard.presentation.board.ResizableBoardBox
import org.rurbaniak.smartchessboard.presentation.board.rememberIsWideScreen
import org.rurbaniak.smartchessboard.presentation.components.CONTENT_MAX_WIDTH
import org.rurbaniak.smartchessboard.presentation.components.MoveList
import org.rurbaniak.smartchessboard.presentation.play.EndGamePicker

/** Caps the non-board sections (status, diagnostics, controls, move list) so they don't stretch edge-to-edge on wide screens. */
private val SECTION_MAX_WIDTH = 480.dp

/**
 * The physical-mode game screen (S-06). Renders the same components as the digital flow but driven by
 * the MVI [PhysicalPlayViewModel]: the board is display-only (moves come from the reed-switch board,
 * not taps) with lifted pieces highlighted, plus a connection / setup / paused / rejection surface.
 * Resolved per game so reopening a different game never reuses a stale state machine. Reachable only
 * on platforms where `supportsPhysicalBoard` is true — web routes a physical game to Replay instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhysicalPlayScreen(
    gameId: String,
    onBack: () -> Unit,
    onReviewGame: () -> Unit,
    onBackToHistory: () -> Unit,
) {
    val viewModel = koinViewModel<PhysicalPlayViewModel>(key = "physical-$gameId") { parametersOf(gameId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val boardPrefs = koinViewModel<BoardPreferencesViewModel>()
    val boardSize by boardPrefs.boardSize.collectAsStateWithLifecycle()
    val isWide = rememberIsWideScreen()
    val moveListOverride by boardPrefs.moveListMode.collectAsStateWithLifecycle()
    val tableMoveList = effectiveMoveListMode(moveListOverride, isWide) == MoveListMode.TABLE
    Scaffold(
        // The board screen is watched, not tapped, for minutes — keep it awake so a dim/auto-lock can't
        // background the app and drop the foreground-first BLE link. Compose's own iOS idle-timer manager
        // owns UIApplication.idleTimerDisabled, so this modifier (not a manual set) is what actually holds
        // it (S-09 Phase 8).
        modifier = Modifier.keepScreenOn(),
        topBar = {
            TopAppBar(
                title = { Text(titleFor(state)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
                actions = {
                    if (state is PhysicalPlayState.Playing) {
                        TextButton(onClick = viewModel::flipBoard) {
                            Text("Flip")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                PhysicalPlayState.Loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                PhysicalPlayState.Error -> {
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
                        Button(onClick = viewModel::retry) { Text("Retry") }
                    }
                }

                is PhysicalPlayState.Playing -> {
                    PlayingContent(
                        modifier =
                            Modifier
                                .widthIn(max = CONTENT_MAX_WIDTH)
                                .fillMaxHeight()
                                .align(Alignment.TopCenter),
                        state = current,
                        isWide = isWide,
                        tableMoveList = tableMoveList,
                        boardSize = boardSize,
                        onBoardSizeChange = boardPrefs::setBoardSize,
                        onPromotionPick = viewModel::pickPromotion,
                        onPromotionDismiss = viewModel::dismissPromotion,
                        onShowDiagnostics = viewModel::showDiagnostics,
                        onHideDiagnostics = viewModel::hideDiagnostics,
                        onReconnect = viewModel::reconnect,
                        onEndGameRequest = viewModel::requestEndGame,
                        onResultPick = viewModel::pickResult,
                        onConfirmEndGame = viewModel::confirmEndGame,
                        onEndGameDismiss = viewModel::dismissEndGame,
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
    state: PhysicalPlayState.Playing,
    isWide: Boolean,
    tableMoveList: Boolean,
    boardSize: Float,
    onBoardSizeChange: (Float) -> Unit,
    onPromotionPick: (PieceType) -> Unit,
    onPromotionDismiss: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onHideDiagnostics: () -> Unit,
    onReconnect: () -> Unit,
    onEndGameRequest: () -> Unit,
    onResultPick: (GameResult) -> Unit,
    onConfirmEndGame: () -> Unit,
    onEndGameDismiss: () -> Unit,
    onReviewGame: () -> Unit,
    onBackToHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val sectionModifier = Modifier.widthIn(max = SECTION_MAX_WIDTH).fillMaxWidth()
        // The live reed-matrix overlay is on by default; this screen-local toggle hides it. Display-only,
        // so flipping it never touches game state (S-09 Phase 7).
        var showSensorDots by rememberSaveable { mutableStateOf(true) }
        StatusBanner(state = state, modifier = sectionModifier)
        Spacer(Modifier.height(8.dp))
        BoardMessage(
            state = state,
            onShowDiagnostics = onShowDiagnostics,
            onReconnect = onReconnect,
            modifier = sectionModifier,
        )
        Spacer(Modifier.height(12.dp))
        ResizableBoardBox(isWide = isWide, size = boardSize, onSizeChange = onBoardSizeChange) { boardModifier ->
            ChessBoardView(
                position = state.position,
                modifier = boardModifier,
                orientation = state.orientation,
                // Display-only — moves come from the physical board, never taps. Lifted pieces are highlighted.
                interaction = null,
                highlightedSquares = state.liftedSquares,
                // Live reed-matrix overlay: mirror what the board senses right now (null while toggled off).
                occupancyDots = if (showSensorDots) state.sensedOccupancy else null,
            )
        }
        Spacer(Modifier.height(8.dp))
        SensorDotsToggle(
            checked = showSensorDots,
            onCheckedChange = { showSensorDots = it },
            modifier = sectionModifier,
        )
        if (state.diagnosticsVisible) {
            Spacer(Modifier.height(12.dp))
            ReedDiagnosticsSection(
                state = state,
                onHideDiagnostics = onHideDiagnostics,
                modifier = sectionModifier,
            )
        }
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
 * The physical-board status line beneath the turn banner: a disconnected board pauses play; a
 * setup mismatch asks the player to match the on-screen position; a rejected confirmation pauses the
 * game (the [PhysicalPlayState.Playing.recovering] gate) and offers the live reed grid as the
 * restoration aid (S-07, FR-010). Renders nothing when the board is connected, set up, and the last
 * confirmation was accepted.
 */
@Composable
private fun BoardMessage(
    state: PhysicalPlayState.Playing,
    onShowDiagnostics: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.result != null) return
    val message =
        when {
            state.paused -> "Board disconnected — moves are paused until it reconnects."

            // A rejected move outranks the generic setup-mismatch text: the reason (illegal / inconsistent /
            // promotion) is what the player needs, even though placing the wrong piece also trips
            // setupMismatch. The reed grid still opens — diagnosticsVisible includes setupMismatch (S-09 P8).
            state.rejection != null -> rejectionText(state.rejection)

            state.setupMismatch -> "Set up the board to match the position on screen."

            else -> null
        } ?: return
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
            )
            if (state.paused) {
                // A dropped link auto-retries in the background (the adapter); this is the manual escape
                // hatch for when that backed off without reaching the board (S-09 Phase 8).
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onReconnect,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                ) {
                    Text("Reconnect")
                }
            } else if (state.recovering && !state.diagnosticsVisible) {
                // While recovering, the reed grid is the assistance (raw diagnostics, no step-by-step). The
                // CTA shows only when the grid is hidden; a setup-mismatch already auto-opens it.
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onShowDiagnostics,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                ) {
                    Text("Show diagnostics")
                }
            }
        }
    }
}

/**
 * The live reed-diagnostics panel under the board while [PhysicalPlayState.Playing.diagnosticsVisible]
 * (S-07, FR-011): a raw-diagnostics caption and the observed-vs-expected [ReedDiagnosticsGrid]. A
 * "Hide" affordance shows only when the grid was opened manually ([PhysicalPlayState.Playing.manualDiagnostics]) —
 * a setup-mismatch auto-entry clears itself once the board matches, so there is nothing to dismiss.
 */
@Composable
private fun ReedDiagnosticsSection(
    state: PhysicalPlayState.Playing,
    onHideDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Reed diagnostics — highlighted squares differ from the position above.",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.manualDiagnostics) {
                TextButton(onClick = onHideDiagnostics) { Text("Hide") }
            }
        }
        Spacer(Modifier.height(8.dp))
        ReedDiagnosticsGrid(
            observed = state.latestOccupancy ?: state.position.toOccupancy(),
            expected = state.position.toOccupancy(),
            modifier = Modifier.fillMaxWidth(),
            orientation = state.orientation,
        )
    }
}

private fun rejectionText(reason: RejectionReason): String =
    when (reason) {
        RejectionReason.ILLEGAL -> "That move isn't legal — restore the previous position and try again."

        // The absolute board disagrees with the game (FR-010); the reed grid below points at the squares to fix.
        RejectionReason.INCONSISTENT -> "The board doesn't match the game — restore the previous position to continue."

        RejectionReason.AMBIGUOUS -> "Couldn't read that move clearly — restore the position and try again."

        RejectionReason.PROMOTION_REQUIRED -> "Pick a promotion piece before confirming."

        RejectionReason.SAVE_FAILED -> "Couldn't save the move — check your connection and try again."
    }

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
            Button(onClick = onReviewGame, modifier = Modifier.weight(1f)) { Text("Analyse") }
            Button(onClick = onBackToHistory, modifier = Modifier.weight(1f)) { Text("Back to history") }
        }
    } else {
        OutlinedButton(onClick = onEndGameRequest, modifier = modifier) { Text("End game") }
    }
}

/** Turn indicator while ongoing; check / checkmate / stalemate / final-result banner otherwise. */
@Composable
private fun StatusBanner(
    state: PhysicalPlayState.Playing,
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
        Text(text, modifier = modifier, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

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

/**
 * Toggles the live reed-matrix overlay (S-09 Phase 7): the corner dots on the board that mirror what
 * the physical board senses right now, before the player confirms. On by default; display-only, so
 * flipping it never touches game state.
 */
@Composable
private fun SensorDotsToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sensor dots", style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun colorName(color: Color): String = if (color == Color.WHITE) "White" else "Black"

private fun finalResultText(result: GameResult): String =
    when (result) {
        GameResult.WHITE -> "White wins"
        GameResult.BLACK -> "Black wins"
        GameResult.DRAW -> "Draw"
    }

private fun titleFor(state: PhysicalPlayState): String =
    when (state) {
        is PhysicalPlayState.Playing -> "${state.whiteLabel} vs ${state.blackLabel}"
        else -> "Game"
    }
