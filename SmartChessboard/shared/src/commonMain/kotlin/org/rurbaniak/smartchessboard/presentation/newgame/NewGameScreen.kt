package org.rurbaniak.smartchessboard.presentation.newgame

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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

/** Caps the form width on wide screens so the fields don't stretch edge-to-edge. */
private val FORM_MAX_WIDTH = 480.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGameScreen(
    onBack: () -> Unit,
    onGameCreated: (String) -> Unit,
) {
    val viewModel = koinViewModel<NewGameViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // One-shot: when the INSERT succeeds, hand the new id to navigation exactly once.
    LaunchedEffect(uiState.createdGameId) {
        uiState.createdGameId?.let { gameId ->
            viewModel.onNavigated()
            onGameCreated(gameId)
        }
    }

    var white by rememberSaveable { mutableStateOf("White") }
    var black by rememberSaveable { mutableStateOf("Black") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New game") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val fieldModifier = Modifier.widthIn(max = FORM_MAX_WIDTH).fillMaxWidth()
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
                onClick = { viewModel.create(white, black) },
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
