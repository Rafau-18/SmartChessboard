package org.rurbaniak.smartchessboard.presentation.replay

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.rurbaniak.smartchessboard.domain.chess.pgn.parsePgn
import org.rurbaniak.smartchessboard.domain.preferences.BOARD_SIZE_DEFAULT

/**
 * Verifies Phase 4 §4.6 without any backend: `Nf6` is unreachable on move 2, so the parser
 * truncates after `1. e4 e5` — a hand-built corrupted [org.rurbaniak.smartchessboard.domain.chess.pgn.ReplayGame].
 * Opening at the last valid ply means the forward controls are disabled (navigation clamped to the
 * truncated range), and the red truncation banner renders above the controls.
 */
@Preview(showBackground = true)
@Composable
private fun ReplayTruncatedPreview() {
    val game = parsePgn("1. e4 e5 2. Nf6")
    MaterialTheme {
        LoadedReplay(
            state = ReplayUiState.Loaded(game = game, currentPly = game.sanMoves.size),
            boardSize = BOARD_SIZE_DEFAULT,
            onBoardSizeChange = {},
            onStart = {},
            onStepBack = {},
            onStepForward = {},
            onEnd = {},
            onJump = {},
            onRetryEval = {},
        )
    }
}
