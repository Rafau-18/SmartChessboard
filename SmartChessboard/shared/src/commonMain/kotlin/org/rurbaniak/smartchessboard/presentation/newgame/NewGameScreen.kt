package org.rurbaniak.smartchessboard.presentation.newgame

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.platform.supportsPhysicalBoard
import org.rurbaniak.smartchessboard.presentation.components.AdaptiveScaffold
import org.rurbaniak.smartchessboard.presentation.components.SECTION_MAX_WIDTH

@Composable
fun NewGameScreen(
    onBack: () -> Unit,
    onGameCreated: (String, GameMode) -> Unit,
) {
    val viewModel = koinViewModel<NewGameViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // One-shot: when the INSERT succeeds, hand the new id + mode to navigation exactly once. Capture the
    // mode before onNavigated() clears it, so physical games route to the physical screen and digital to Play.
    LaunchedEffect(uiState.createdGameId) {
        uiState.createdGameId?.let { gameId ->
            val mode = uiState.createdGameMode ?: GameMode.DIGITAL
            viewModel.onNavigated()
            onGameCreated(gameId, mode)
        }
    }

    var white by rememberSaveable { mutableStateOf("White") }
    var black by rememberSaveable { mutableStateOf("Black") }
    // Boolean (not the enum) so rememberSaveable needs no custom saver across targets; the picker is
    // shown only where the platform can drive a physical board (web stays digital-only).
    var physical by rememberSaveable { mutableStateOf(false) }

    AdaptiveScaffold(
        title = { Text("New game") },
        navigationIcon = {
            TextButton(onClick = onBack) {
                Text("Back")
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // At compact heights (landscape phone) or with the keyboard open the form no longer
                    // fits: scroll keeps the Start button reachable, imePadding lifts the focused field
                    // clear of the IME. enableEdgeToEdge() (MainActivity) routes the ime inset here.
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val fieldModifier = Modifier.widthIn(max = SECTION_MAX_WIDTH).fillMaxWidth()
            // Digital / Physical picker — only on platforms that can drive a physical board. Web never
            // shows it and defaults to digital, so a web-created game is never a physical one.
            if (supportsPhysicalBoard) {
                Row(modifier = fieldModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !physical,
                        onClick = { physical = false },
                        enabled = !uiState.creating,
                        label = { Text("Digital") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = physical,
                        onClick = { physical = true },
                        enabled = !uiState.creating,
                        label = { Text("Physical") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            OutlinedTextField(
                value = white,
                onValueChange = { white = it },
                label = { Text("White") },
                singleLine = true,
                enabled = !uiState.creating,
                modifier = fieldModifier,
            )
            OutlinedTextField(
                value = black,
                onValueChange = { black = it },
                label = { Text("Black") },
                singleLine = true,
                enabled = !uiState.creating,
                modifier = fieldModifier,
            )
            if (uiState.failed) {
                Text(
                    "Couldn't create the game — check your connection and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = fieldModifier,
                )
            }
            Button(
                onClick = { viewModel.create(white, black, if (physical) GameMode.PHYSICAL else GameMode.DIGITAL) },
                enabled = !uiState.creating,
                modifier = fieldModifier,
            ) {
                if (uiState.creating) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(if (uiState.failed) "Retry" else "Start")
                }
            }
        }
    }
}
