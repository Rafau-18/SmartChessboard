package org.rurbaniak.smartchessboard.presentation.physical

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import org.rurbaniak.smartchessboard.presentation.components.AdaptiveScaffold
import org.rurbaniak.smartchessboard.presentation.components.BOARD_CHROME_COLUMN
import org.rurbaniak.smartchessboard.presentation.components.BoardScreenScaffold
import org.rurbaniak.smartchessboard.presentation.components.MoveList
import org.rurbaniak.smartchessboard.presentation.components.SECTION_MAX_WIDTH
import org.rurbaniak.smartchessboard.presentation.layout.BoardArrangement
import org.rurbaniak.smartchessboard.presentation.layout.LocalWindowSizeClass
import org.rurbaniak.smartchessboard.presentation.layout.boardArrangement
import org.rurbaniak.smartchessboard.presentation.layout.boardResizeEnabled
import org.rurbaniak.smartchessboard.presentation.play.EndGamePicker

/**
 * PhysicalPlay's banner slot is taller than the shared default: the slot must fit the recovery
 * [BoardMessage] — up to two message lines plus a Reconnect / Show-diagnostics action — with the
 * turn banner as its no-message fallback. 12 dp surface padding ×2 + two 20 dp text lines + 8 dp
 * gap + a 40 dp button.
 */
private val PHYSICAL_BANNER_SLOT_HEIGHT = 112.dp

/**
 * Cap on the message text inside the banner slot: a longer recovery text scrolls internally rather
 * than growing the slot (the no-jump invariant — the slot's size never depends on its content).
 */
private val MESSAGE_TEXT_MAX_HEIGHT = 40.dp

/**
 * Viewport height consumed above/below the diagnostics grid when it leads the side panel at compact
 * height: system bars (~24) + screen padding (32) + the banner slot (112) + the panel spacer (12).
 * Passing this as the grid's chrome keeps the whole grid visible beside the board without scrolling
 * — the FR-010/011 recovery aid the side-pane arrangement exists for.
 */
private val DIAGNOSTICS_PANE_CHROME = 180.dp

/**
 * The physical-mode game screen (S-06). Renders the same components as the digital flow but driven by
 * the MVI [PhysicalPlayViewModel]: the board is display-only (moves come from the reed-switch board,
 * not taps) with lifted pieces highlighted, plus a connection / setup / paused / rejection surface.
 * Resolved per game so reopening a different game never reuses a stale state machine. Reachable only
 * on platforms where `supportsPhysicalBoard` is true — web routes a physical game to Replay instead.
 */
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
    // Window-shape policies (same reads as PlayScreen): the resize handle only on a true wide screen,
    // and the move-list default follows the container it renders in (side panel → TABLE).
    val windowSizeClass = LocalWindowSizeClass.current
    val boardResize = boardResizeEnabled(windowSizeClass)
    val inSidePanel = boardArrangement(windowSizeClass) == BoardArrangement.SidePane
    val moveListOverride by boardPrefs.moveListMode.collectAsStateWithLifecycle()
    val tableMoveList = effectiveMoveListMode(moveListOverride, inSidePanel) == MoveListMode.TABLE
    AdaptiveScaffold(
        title = { Text(titleFor(state)) },
        // The board screen is watched, not tapped, for minutes — keep it awake so a dim/auto-lock can't
        // background the app and drop the foreground-first BLE link. Compose's own iOS idle-timer manager
        // owns UIApplication.idleTimerDisabled, so this modifier (not a manual set) is what actually holds
        // it (S-09 Phase 8).
        modifier = Modifier.keepScreenOn(),
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
                        state = current,
                        boardResize = boardResize,
                        inSidePanel = inSidePanel,
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
    boardResize: Boolean,
    inSidePanel: Boolean,
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
    // The live reed-matrix overlay is on by default; this screen-local toggle hides it. Display-only,
    // so flipping it never touches game state (S-09 Phase 7). Hoisted above the scaffold because the
    // toggle lives in the panel while the dots render in the board slot.
    var showSensorDots by rememberSaveable { mutableStateOf(true) }
    BoardScreenScaffold(
        banner = {
            PhysicalBanner(
                state = state,
                onShowDiagnostics = onShowDiagnostics,
                onReconnect = onReconnect,
                modifier = Modifier.fillMaxWidth(),
            )
        },
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
                    // Display-only — moves come from the physical board, never taps. Lifted pieces are highlighted.
                    interaction = null,
                    highlightedSquares = state.liftedSquares,
                    // Live reed-matrix overlay: mirror what the board senses right now (null while toggled off).
                    occupancyDots = if (showSensorDots) state.sensedOccupancy else null,
                )
            }
        },
        modifier = modifier,
        bannerSlotHeight = PHYSICAL_BANNER_SLOT_HEIGHT,
    ) {
        val sectionModifier = Modifier.widthIn(max = SECTION_MAX_WIDTH).fillMaxWidth()
        if (inSidePanel) {
            // Recovery aid first: beside the board the grid must sit above the panel's fold, so a
            // player comparing board vs sensors (FR-010/011) never scrolls between the two.
            if (state.diagnosticsVisible) {
                Spacer(Modifier.height(12.dp))
                ReedDiagnosticsSection(
                    state = state,
                    onHideDiagnostics = onHideDiagnostics,
                    modifier = sectionModifier,
                    gridVerticalChrome = DIAGNOSTICS_PANE_CHROME,
                )
            }
            Spacer(Modifier.height(8.dp))
            SensorDotsToggle(
                checked = showSensorDots,
                onCheckedChange = { showSensorDots = it },
                modifier = sectionModifier,
            )
        } else {
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
 * What PhysicalPlay shows in the scaffold's fixed banner slot: the recovery [BoardMessage] when one
 * applies, else the turn [StatusBanner]. One slot, one occupant — the message outranks the turn
 * indicator because during a pause/rejection the instruction is what the player needs, and both never
 * fit the reserved height together. Swapping inside the fixed slot keeps the board perfectly still.
 */
@Composable
private fun PhysicalBanner(
    state: PhysicalPlayState.Playing,
    onShowDiagnostics: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = boardMessageFor(state)
    if (message != null) {
        BoardMessage(
            message = message,
            state = state,
            onShowDiagnostics = onShowDiagnostics,
            onReconnect = onReconnect,
            modifier = modifier,
        )
    } else {
        StatusBanner(state = state, modifier = modifier)
    }
}

/**
 * The physical-board status message (S-07, FR-010): a disconnected board pauses play; a setup
 * mismatch asks the player to match the on-screen position; a rejected confirmation pauses the game
 * (the [PhysicalPlayState.Playing.recovering] gate). `null` when the board is connected, set up, and
 * the last confirmation was accepted — [PhysicalBanner] then falls back to the turn banner.
 */
private fun boardMessageFor(state: PhysicalPlayState.Playing): String? {
    if (state.result != null) return null
    return when {
        state.paused -> "Board disconnected — moves are paused until it reconnects."

        // A rejected move outranks the generic setup-mismatch text: the reason (illegal / inconsistent /
        // promotion) is what the player needs, even though placing the wrong piece also trips
        // setupMismatch. The reed grid still opens — diagnosticsVisible includes setupMismatch (S-09 P8).
        state.rejection != null -> rejectionText(state.rejection)

        state.setupMismatch -> "Set up the board to match the position on screen."

        else -> null
    }
}

/**
 * Renders a [boardMessageFor] message with its escape hatch, sized for the fixed banner slot: the
 * text is capped at [MESSAGE_TEXT_MAX_HEIGHT] with internal scroll so a long recovery text never
 * grows the slot, and the action button stays visible below it.
 */
@Composable
private fun BoardMessage(
    message: String,
    state: PhysicalPlayState.Playing,
    onShowDiagnostics: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                modifier =
                    Modifier
                        .heightIn(max = MESSAGE_TEXT_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
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
 * The live reed-diagnostics panel while [PhysicalPlayState.Playing.diagnosticsVisible] (S-07, FR-011):
 * the observed-vs-expected [ReedDiagnosticsGrid] with its caption below. A "Hide" affordance shows
 * only when the grid was opened manually ([PhysicalPlayState.Playing.manualDiagnostics]) — a
 * setup-mismatch auto-entry clears itself once the board matches, so there is nothing to dismiss.
 * [gridVerticalChrome] is the grid's viewport reservation — the side panel passes its own so the grid
 * stays fully visible beside the board.
 */
@Composable
private fun ReedDiagnosticsSection(
    state: PhysicalPlayState.Playing,
    onHideDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    gridVerticalChrome: Dp = BOARD_CHROME_COLUMN,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        ReedDiagnosticsGrid(
            observed = state.latestOccupancy ?: state.position.toOccupancy(),
            expected = state.position.toOccupancy(),
            modifier = Modifier.fillMaxWidth(),
            orientation = state.orientation,
            verticalChrome = gridVerticalChrome,
        )
        Spacer(Modifier.height(8.dp))
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
