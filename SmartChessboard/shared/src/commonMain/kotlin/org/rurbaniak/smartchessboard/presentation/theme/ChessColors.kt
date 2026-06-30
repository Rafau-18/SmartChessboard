package org.rurbaniak.smartchessboard.presentation.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Chess-specific color tokens that Material's [androidx.compose.material3.ColorScheme] doesn't
 * model: the constant wood board squares plus the chrome (selection / target / lift highlights, the
 * best-move arrow, the eval bar, and the diagnostics grid) layered around them.
 *
 * The board hues ([lightSquare] / [darkSquare]) are **identical in light and dark** — the wood never
 * repaints, so a game position always reads the same. The overlays that sit *on* that constant wood
 * ([selectedTint] / [targetMark] / [bestMoveArrow] / [liftHighlight] / [occupancyDot]) are likewise
 * the same in both modes. Only the chrome that sits on themed surfaces (the eval bar and the diagnostics cells) takes
 * a mode-specific value so it stays legible on either background.
 */
data class ChessColors(
    val lightSquare: Color,
    val darkSquare: Color,
    val selectedTint: Color,
    val targetMark: Color,
    val bestMoveArrow: Color,
    val liftHighlight: Color,
    val occupancyDot: Color,
    val evalBarTrack: Color,
    val evalBarFill: Color,
    val evalBarLabel: Color,
    val diagLightCell: Color,
    val diagDarkCell: Color,
)

// Board squares and the overlays drawn on them are constant across modes (the wood never repaints).
private val BoardLightSquare = Color(0xFFF0D9B5)
private val BoardDarkSquare = Color(0xFFB58863)
private val SelectedTint = Color(0x80FBE34D)
private val TargetMark = Color(0x662E7D32)
private val BestMoveArrow = Color(0xCC2E7D32)
private val LiftHighlight = Color(0x804FC3F7)

// The live reed-matrix overlay's corner dot (S-09): a neutral, mostly-opaque dark slate that reads on
// both the cream and the brown wood. Constant across modes like the other on-wood overlays; the exact
// hue is tuned on real reeds in the Phase 8 hardware gate.
private val OccupancyDot = Color(0xCC1C2530)

// White-advantage fill stays light and black-advantage track stays dark in BOTH modes (chess
// semantics, not surface theming). The eval-bar label sits at the bar's bottom anchor, which is the
// light fill for essentially the whole range, so it stays dark in both modes for legibility.
private val EvalBarLabel = Color(0xFF1C2530)

val ChessColorsLight =
    ChessColors(
        lightSquare = BoardLightSquare,
        darkSquare = BoardDarkSquare,
        selectedTint = SelectedTint,
        targetMark = TargetMark,
        bestMoveArrow = BestMoveArrow,
        liftHighlight = LiftHighlight,
        occupancyDot = OccupancyDot,
        evalBarTrack = Color(0xFF424242),
        evalBarFill = Color(0xFFF5F5F5),
        evalBarLabel = EvalBarLabel,
        diagLightCell = Color(0xFFECEFF1),
        diagDarkCell = Color(0xFFB0BEC5),
    )

val ChessColorsDark =
    ChessColors(
        lightSquare = BoardLightSquare,
        darkSquare = BoardDarkSquare,
        selectedTint = SelectedTint,
        targetMark = TargetMark,
        bestMoveArrow = BestMoveArrow,
        liftHighlight = LiftHighlight,
        occupancyDot = OccupancyDot,
        // Keep white-advantage = light fill; nudge the track lighter so it reads against the dark surface.
        evalBarTrack = Color(0xFF2E2E2E),
        evalBarFill = Color(0xFFE8ECF0),
        evalBarLabel = EvalBarLabel,
        // Neutral sensor cells re-toned for a dark surface — still reads as a readout, not the wood board.
        diagLightCell = Color(0xFF37414C),
        diagDarkCell = Color(0xFF2A323C),
    )

/**
 * The chess token set for the current mode, provided by
 * [org.rurbaniak.smartchessboard.presentation.theme.AppTheme]. Defaults to the light set so a stray
 * read outside the theme (e.g. a preview) still renders.
 */
val LocalChessColors = staticCompositionLocalOf { ChessColorsLight }
