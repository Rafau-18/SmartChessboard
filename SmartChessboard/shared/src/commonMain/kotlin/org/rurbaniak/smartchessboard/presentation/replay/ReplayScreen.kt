package org.rurbaniak.smartchessboard.presentation.replay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.rurbaniak.smartchessboard.domain.chess.pgn.PgnHeaders
import org.rurbaniak.smartchessboard.presentation.board.ChessBoardView

/** Caps the board on wide screens (web/desktop) so it doesn't stretch edge-to-edge — the one
 * responsive concession this slice (side-by-side multi-pane is the app-wide adaptive follow-up). */
private val BOARD_MAX_WIDTH = 480.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplayScreen(
    gameId: String,
    onBack: () -> Unit,
) {
    // Keyed by game so reopening a different game never reuses a stale parse.
    val viewModel =
        koinViewModel<ReplayViewModel>(key = "replay-$gameId") { parametersOf(gameId) }
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
            )
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
                        onStart = viewModel::goToStart,
                        onStepBack = viewModel::stepBack,
                        onStepForward = viewModel::stepForward,
                        onEnd = viewModel::goToEnd,
                        onJump = viewModel::jumpTo,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LoadedReplay(
    state: ReplayUiState.Loaded,
    onStart: () -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onEnd: () -> Unit,
    onJump: (Int) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlayerLine(state.game.headers)
        Spacer(Modifier.height(12.dp))
        ChessBoardView(
            position = state.position,
            modifier = Modifier.widthIn(max = BOARD_MAX_WIDTH).fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        if (state.isTruncated) {
            TruncationBanner(modifier = Modifier.widthIn(max = BOARD_MAX_WIDTH).fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        TransportControls(
            state = state,
            onStart = onStart,
            onBack = onStepBack,
            onForward = onStepForward,
            onEnd = onEnd,
            modifier = Modifier.widthIn(max = BOARD_MAX_WIDTH).fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        MoveList(
            sanMoves = state.game.sanMoves,
            currentPly = state.currentPly,
            onJump = onJump,
            modifier = Modifier.widthIn(max = BOARD_MAX_WIDTH).fillMaxWidth(),
        )
    }
}

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

/**
 * Numbered move pairs. The move that produced the current position is highlighted; tapping any move
 * jumps to the position right after it (`jumpTo(plyIndex + 1)`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoveList(
    sanMoves: List<String>,
    currentPly: Int,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sanMoves.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sanMoves.forEachIndexed { plyIndex, san ->
            if (plyIndex % 2 == 0) {
                Text(
                    "${plyIndex / 2 + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val isCurrent = plyIndex + 1 == currentPly
            Text(
                text = san,
                modifier =
                    Modifier
                        .clickable { onJump(plyIndex + 1) }
                        .padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color =
                    if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
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
