package org.rurbaniak.smartchessboard.presentation.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.games.GameStatus
import org.rurbaniak.smartchessboard.domain.games.GameSummary
import org.rurbaniak.smartchessboard.domain.preferences.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    userId: String,
    onSignOut: () -> Unit,
    onNewGame: () -> Unit,
    onGameClick: (GameSummary) -> Unit,
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
) {
    // Keyed by user so a sign-out → sign-in as someone else never reuses a stale list.
    val viewModel = koinViewModel<HistoryViewModel>(key = "history-$userId")
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // List refresh after a game is created/finished is driven by the ViewModel's subscription to
    // GamesRepository.changes — not a screen-level effect. The screen is retained across the
    // push/pop on every platform, and composition-re-entry / lifecycle-resume signals diverge
    // across Android / iOS / web, so neither fires reliably here (a covered entry's composition is
    // disposed on Android/web but retained on iOS; ON_RESUME tracks the app, not the nav entry).
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My games") },
                actions = {
                    // Cycles System → Light → Dark → System; the label is the current mode so the
                    // control doubles as the live indicator. Lives here (no Settings screen, by decision).
                    TextButton(onClick = onCycleTheme) {
                        Text(themeMode.label())
                    }
                    TextButton(onClick = onNewGame) {
                        Text("New game")
                    }
                    TextButton(onClick = onSignOut) {
                        Text("Sign out")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                HistoryUiState.Loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                HistoryUiState.Empty -> {
                    Text(
                        "No games yet — tap \"New game\" to start one.",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }

                HistoryUiState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Couldn't load your games.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = viewModel::retry) {
                            Text("Retry")
                        }
                    }
                }

                is HistoryUiState.Loaded -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.games, key = { it.id }) { game ->
                            GameRow(game, onClick = { onGameClick(game) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameRow(
    game: GameSummary,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "${game.whiteLabel} vs ${game.blackLabel}",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${formatCreatedAt(game.createdAt)} · ${game.modeLabel()} · ${game.outcomeLabel()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Surface an in-progress physical game as resumable (FR-013). The tap behaviour is unchanged —
        // the whole row already routes to PhysicalPlay; this is only a legibility affordance.
        if (game.isResumablePhysical()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Resume",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    HorizontalDivider()
}

/** Compact top-bar label for the active theme mode — doubles as the live indicator for the cycle control. */
private fun ThemeMode.label(): String =
    when (this) {
        ThemeMode.SYSTEM -> "Auto"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
    }

/** An in-progress physical game is the FR-013 resume offer: tapping the row continues it on this device. */
private fun GameSummary.isResumablePhysical(): Boolean = mode == GameMode.PHYSICAL && status == GameStatus.IN_PROGRESS

private fun GameSummary.modeLabel(): String =
    when (mode) {
        GameMode.DIGITAL -> "Digital"
        GameMode.PHYSICAL -> "Physical"
    }

private fun GameSummary.outcomeLabel(): String =
    when (status) {
        GameStatus.IN_PROGRESS -> {
            "In progress"
        }

        GameStatus.FINISHED -> {
            when (result) {
                GameResult.WHITE -> "White won"
                GameResult.BLACK -> "Black won"
                GameResult.DRAW -> "Draw"
                null -> "Finished"
            }
        }
    }

/** "2026-06-11T14:03:27.123+00:00" → "2026-06-11 14:03" — good enough until a locale-aware formatter is needed. */
private fun formatCreatedAt(isoTimestamp: String): String = isoTimestamp.take(16).replace('T', ' ')
