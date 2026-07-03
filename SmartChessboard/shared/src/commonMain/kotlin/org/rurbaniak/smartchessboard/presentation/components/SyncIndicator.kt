package org.rurbaniak.smartchessboard.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The sync hint's reserved height — laid out whether or not the hint is showing (no-jump). */
private val SYNC_SLOT_HEIGHT = 20.dp

/** How long a sync must stay pending before the hint shows — a healthy save lands well under this. */
private const val SYNC_HINT_DELAY_MS = 600L

/**
 * Non-blocking "Saving…" hint for the best-effort cloud sync (contract §3.4), shared by the game
 * screens' panels. Two disciplines keep it calm: the slot has a **fixed height** (laid out even
 * while idle, so the sections below never move — the no-jump invariant), and the hint appears only
 * after [SYNC_HINT_DELAY_MS] of continuous pending — a healthy per-move save lands well under that,
 * so the happy path shows nothing instead of flashing on every move. A slow network or the
 * terminal finish flush (which keeps retrying) crosses the delay and shows the hint until it lands.
 */
@Composable
fun SyncIndicator(
    syncPending: Boolean,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(syncPending) {
        if (syncPending) {
            delay(SYNC_HINT_DELAY_MS)
            visible = true
        } else {
            visible = false
        }
    }
    Box(modifier = modifier.height(SYNC_SLOT_HEIGHT), contentAlignment = Alignment.CenterStart) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Row(
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
    }
}
