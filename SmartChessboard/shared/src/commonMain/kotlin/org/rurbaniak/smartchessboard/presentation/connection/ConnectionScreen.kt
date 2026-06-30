package org.rurbaniak.smartchessboard.presentation.connection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.rurbaniak.smartchessboard.domain.board.DiscoveredBoard

/** Caps the content width so the list/status don't stretch edge-to-edge on wide screens. */
private val CONNECTION_MAX_WIDTH = 480.dp

/**
 * The BLE connection / pairing screen (S-09 Phase 5) — a gate the physical-play flow passes through:
 * request permission → scan → tap a board → pair → connected, then navigate on into physical play
 * (the singleton adapter stays connected, so [PhysicalPlayViewModel] resumes the live link). Driven by
 * the MVI [ConnectionViewModel]; reachable only where `supportsPhysicalBoard` is true (never on web).
 *
 * The screen owns the OS permission handshake (the [BlePermissionController]) and feeds its result back
 * as an intent; the rest of the surface renders [ConnectionUiState.phase].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    onBack: () -> Unit,
    onConnected: () -> Unit,
) {
    val viewModel = koinViewModel<ConnectionViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permissions =
        rememberBlePermissionController { granted ->
            if (granted) viewModel.onPermissionGranted() else viewModel.onPermissionDenied()
        }

    // On entry, settle the permission before any scan: proceed if already held, else prompt the OS.
    LaunchedEffect(Unit) {
        if (permissions.isGranted()) viewModel.onPermissionGranted() else permissions.request()
    }

    // One-shot: once the link is up, hand off to physical play exactly once (the adapter stays connected).
    val connected = state.phase is ConnectionPhase.Connected
    LaunchedEffect(connected) {
        if (connected) onConnected()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect board") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = CONNECTION_MAX_WIDTH)
                        .fillMaxWidth()
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (val phase = state.phase) {
                    ConnectionPhase.NeedsPermission -> {
                        StatusBlock("Checking Bluetooth permission…", spinner = true)
                    }

                    ConnectionPhase.PermissionDenied -> {
                        PermissionDeniedBlock(
                            onRetry = { permissions.request() },
                            onOpenSettings = { permissions.openAppSettings() },
                        )
                    }

                    ConnectionPhase.Scanning -> {
                        ScanningBlock(
                            state = state,
                            onSelect = viewModel::selectDevice,
                            onForget = viewModel::forgetDevice,
                        )
                    }

                    is ConnectionPhase.Connecting -> {
                        StatusBlock(if (phase.pairing) "Pairing…" else "Connecting…", spinner = true)
                    }

                    ConnectionPhase.Connected -> {
                        StatusBlock("Connected", spinner = true)
                    }

                    is ConnectionPhase.Failed -> {
                        FailedBlock(
                            reason = phase.reason,
                            canForget = state.rememberedBoardId != null,
                            onRetry = viewModel::retry,
                            onForget = viewModel::forgetDevice,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(
    message: String,
    spinner: Boolean,
) {
    if (spinner) {
        CircularProgressIndicator()
    }
    Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

@Composable
private fun ScanningBlock(
    state: ConnectionUiState,
    onSelect: (String) -> Unit,
    onForget: () -> Unit,
) {
    val lookingForSaved =
        state.rememberedBoardId != null && state.devices.none { it.id == state.rememberedBoardId }
    Text(
        if (lookingForSaved) "Looking for your saved board…" else "Scanning for boards…",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    CircularProgressIndicator()
    if (state.devices.isEmpty()) {
        Text(
            "Make sure the board is powered on and nearby.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    } else {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(state.devices, key = { it.id }) { board ->
                BoardRow(board = board, onClick = { onSelect(board.id) })
                HorizontalDivider()
            }
        }
    }
    if (state.rememberedBoardId != null) {
        TextButton(onClick = onForget) { Text("Forget saved board") }
    }
}

@Composable
private fun BoardRow(
    board: DiscoveredBoard,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
    ) {
        Text(board.name ?: "Unknown board", style = MaterialTheme.typography.bodyLarge)
        if (board.rssi != null) {
            Text(
                "Signal ${board.rssi} dBm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionDeniedBlock(
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Text(
        "Bluetooth permission is needed to find and connect to your board.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Text(
        "If the prompt doesn't appear, enable the Nearby devices / Bluetooth permission in Settings.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
    OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Open settings") }
}

@Composable
private fun FailedBlock(
    reason: ConnectionFailure,
    canForget: Boolean,
    onRetry: () -> Unit,
    onForget: () -> Unit,
) {
    Text(
        messageFor(reason),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
    if (canForget) {
        OutlinedButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) { Text("Forget & scan") }
    }
}

private fun messageFor(reason: ConnectionFailure): String =
    when (reason) {
        ConnectionFailure.BLUETOOTH_OFF -> "Bluetooth is off. Turn it on and retry."
        ConnectionFailure.OUT_OF_RANGE -> "Couldn't reach the board. Make sure it's powered on and nearby."
        ConnectionFailure.BOND_FAILED -> "Pairing failed. Retry, or forget the board and pair again."
        ConnectionFailure.GENERIC -> "Couldn't connect to the board. Please try again."
    }
