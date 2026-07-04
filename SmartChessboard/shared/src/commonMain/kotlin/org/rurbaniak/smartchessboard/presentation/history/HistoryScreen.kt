package org.rurbaniak.smartchessboard.presentation.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.games.GameStatus
import org.rurbaniak.smartchessboard.domain.games.GameSummary
import org.rurbaniak.smartchessboard.domain.preferences.ThemeMode
import org.rurbaniak.smartchessboard.presentation.components.AdaptiveActionButton
import org.rurbaniak.smartchessboard.presentation.components.AdaptiveScaffold
import org.rurbaniak.smartchessboard.presentation.components.LIST_MAX_WIDTH
import org.rurbaniak.smartchessboard.presentation.theme.label

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
    val deletePrompt by viewModel.deletePrompt.collectAsStateWithLifecycle()
    // List refresh after a game is created/finished is driven by the ViewModel's subscription to
    // GamesRepository.changes — not a screen-level effect. The screen is retained across the
    // push/pop on every platform, and composition-re-entry / lifecycle-resume signals diverge
    // across Android / iOS / web, so neither fires reliably here (a covered entry's composition is
    // disposed on Android/web but retained on iOS; ON_RESUME tracks the app, not the nav entry).
    AdaptiveScaffold(
        title = { Text("My games") },
        actions = {
            // Cycles System → Light → Dark → System; the label/icon is the current mode so the
            // control doubles as the live indicator. Lives here (no Settings screen, by decision).
            AdaptiveActionButton(label = themeMode.label(), icon = themeMode.icon(), onClick = onCycleTheme)
            AdaptiveActionButton(label = "New game", icon = Icons.Filled.Add, onClick = onNewGame)
            AdaptiveActionButton(label = "Sign out", icon = Icons.AutoMirrored.Filled.Logout, onClick = onSignOut)
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
                    // Cap the list width and centre it — a games list reads better narrow than stretched
                    // across a wide monitor.
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .widthIn(max = LIST_MAX_WIDTH)
                                .align(Alignment.TopCenter),
                    ) {
                        items(state.games, key = { it.id }) { game ->
                            GameRow(
                                game,
                                onClick = { onGameClick(game) },
                                onDelete = { viewModel.requestDelete(game) },
                            )
                        }
                    }
                }
            }
        }
    }

    // Visibility is driven solely by the ViewModel's prompt state (the EndGamePicker state-flag
    // pattern); dismissal while the delete is in flight is ignored by the ViewModel.
    deletePrompt?.let { prompt ->
        DeleteGameDialog(
            prompt = prompt,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

@Composable
private fun GameRow(
    game: GameSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The kebab is a sibling of the clickable text column (not nested inside it), so its taps
        // can never fall through to the row's own click.
        Column(
            modifier =
                Modifier
                    .weight(1f)
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
        // Per-game actions menu — deliberately a menu (not a bare delete icon) to leave room for
        // future row actions. Available on every row regardless of status or mode.
        Box {
            var menuExpanded by remember { mutableStateOf(false) }
            // The kebab is drawn on a Canvas (no glyph, no Icon), so it carries no semantics of its
            // own — the description is what screen readers and UI tests address it by.
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.semantics { contentDescription = "Game actions" },
            ) {
                KebabDots()
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
    HorizontalDivider()
}

/**
 * The delete confirmation gate (FR-021): names the matchup, states permanence, and holds the only
 * path to the destructive action. Modeled on EndGamePicker's confirm step; the in-flight and
 * failure treatment mirrors NewGameScreen (spinner inside the button, error line, Retry).
 */
@Composable
private fun DeleteGameDialog(
    prompt: DeletePromptState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(24.dp).widthIn(max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Delete game?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This permanently deletes ${prompt.game.whiteLabel} vs ${prompt.game.blackLabel}. " +
                        "This can't be undone.",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                if (prompt.failed) {
                    Text(
                        "Couldn't delete the game — check your connection and try again.",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !prompt.deleting,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = !prompt.deleting,
                        modifier = Modifier.weight(1f),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                    ) {
                        if (prompt.deleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onError,
                            )
                        } else {
                            Text(if (prompt.failed) "Retry" else "Delete")
                        }
                    }
                }
            }
        }
    }
}

/**
 * The kebab (⋮) affordance, drawn as three stacked dots on a [Canvas] rather than a glyph. The
 * bundled WasmJS font has no vertical-ellipsis glyph, so a `Text("⋮")` renders as tofu on web;
 * vector drawing is font-independent and identical on every target. No material-icons dependency.
 */
@Composable
private fun KebabDots() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(width = 4.dp, height = 16.dp)) {
        val radius = size.width / 2f
        val cx = size.width / 2f
        // Three evenly spaced dots, centred vertically.
        listOf(0.15f, 0.5f, 0.85f).forEach { fraction ->
            drawCircle(color = color, radius = radius, center = Offset(cx, size.height * fraction))
        }
    }
}

/** The theme-cycle control's rail icon — the current mode, mirroring [ThemeMode.label]. */
private fun ThemeMode.icon(): ImageVector =
    when (this) {
        ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
        ThemeMode.LIGHT -> Icons.Filled.LightMode
        ThemeMode.DARK -> Icons.Filled.DarkMode
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
