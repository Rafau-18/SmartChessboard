package org.rurbaniak.smartchessboard.presentation.play

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.rurbaniak.smartchessboard.domain.games.GameResult

/**
 * The modal manual end-game flow (FR-018), shown while [EndGamePrompt] is non-null. Two steps guard
 * the irreversible close: [EndGamePrompt.Picking] offers the three results (White wins / Black wins
 * / Draw); choosing one ([onPick]) advances to [EndGamePrompt.Confirming], which spells out that
 * finishing can't be undone before [onConfirm] fires. Dismissing (back / scrim / Cancel) leaves the
 * game in progress via [onDismiss]. Mirrors `PromotionPicker`'s split: a [Dialog] wrapper plus a
 * previewable inner [EndGamePickerSurface].
 */
@Composable
fun EndGamePicker(
    prompt: EndGamePrompt,
    onPick: (GameResult) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        // Animate the surface in (fade + slight scale-up). The Dialog window itself can't be tweened,
        // so the enter transition runs on the content; dismissal removes the dialog wholesale. The
        // Picking → Confirming step swap stays instant (no logic change).
        val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn() + scaleIn(initialScale = 0.92f),
        ) {
            EndGamePickerSurface(prompt = prompt, onPick = onPick, onConfirm = onConfirm, onDismiss = onDismiss)
        }
    }
}

/** The dialog's visible content, split out from [Dialog] so it can be previewed in isolation. */
@Composable
internal fun EndGamePickerSurface(
    prompt: EndGamePrompt,
    onPick: (GameResult) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(24.dp).widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (prompt) {
                EndGamePrompt.Picking -> {
                    PickingStep(onPick = onPick, onDismiss = onDismiss)
                }

                is EndGamePrompt.Confirming -> {
                    ConfirmingStep(result = prompt.result, onConfirm = onConfirm, onDismiss = onDismiss)
                }
            }
        }
    }
}

/** Step 1: pick which result to record. Each option advances to the confirmation step. */
@Composable
private fun PickingStep(
    onPick: (GameResult) -> Unit,
    onDismiss: () -> Unit,
) {
    Text("End game", style = MaterialTheme.typography.titleMedium)
    Text(
        "Record the result of this game.",
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    // Order matches the finished banner / History phrasing (White, Black, Draw).
    ResultOption(label = "White wins", onClick = { onPick(GameResult.WHITE) })
    Spacer(Modifier.height(8.dp))
    ResultOption(label = "Black wins", onClick = { onPick(GameResult.BLACK) })
    Spacer(Modifier.height(8.dp))
    ResultOption(label = "Draw", onClick = { onPick(GameResult.DRAW) })
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onDismiss) {
        Text("Cancel")
    }
}

/** Step 2: confirm the irreversible close. Cancel abandons the flow; the game stays in progress. */
@Composable
private fun ConfirmingStep(
    result: GameResult,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Text("End game?", style = MaterialTheme.typography.titleMedium)
    Text(
        "This records ${resultDescription(result)} and ends the game. This can't be undone.",
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
            Text("Cancel")
        }
        Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
            Text("End game")
        }
    }
}

@Composable
private fun ResultOption(
    label: String,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label)
    }
}

/** Human-readable description of the result for the irreversibility confirmation copy. */
private fun resultDescription(result: GameResult): String =
    when (result) {
        GameResult.WHITE -> "a win for White"
        GameResult.BLACK -> "a win for Black"
        GameResult.DRAW -> "a draw"
    }
