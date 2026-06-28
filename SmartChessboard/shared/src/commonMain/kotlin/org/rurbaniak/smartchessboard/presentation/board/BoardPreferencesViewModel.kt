package org.rurbaniak.smartchessboard.presentation.board

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import org.rurbaniak.smartchessboard.domain.preferences.MoveListMode
import org.rurbaniak.smartchessboard.domain.preferences.UiPreferences

/**
 * Exposes the persisted board-screen UI preferences (wide-screen board size and move-list layout) to
 * the board screens (Replay / Play / PhysicalPlay), MVVM-style (the project default per lessons.md).
 * Values are sourced from the singleton [UiPreferences], so every screen that resolves this ViewModel
 * reads and writes the same durable choices — a change on one screen is what every screen sees, and it
 * survives a restart.
 */
class BoardPreferencesViewModel(
    private val prefs: UiPreferences,
) : ViewModel() {
    /** Board size as a fraction of the available pane width; only wide screens apply it. */
    val boardSize: StateFlow<Float> = prefs.boardSize

    /** Persist the new size (clamped to the valid range inside [UiPreferences]). */
    fun setBoardSize(fraction: Float) {
        prefs.setBoardSize(fraction)
    }

    /** The user's explicit move-list layout choice, or `null` (default by screen width). */
    val moveListMode: StateFlow<MoveListMode?> = prefs.moveListMode

    /** Persist an explicit move-list layout choice. */
    fun setMoveListMode(mode: MoveListMode) {
        prefs.setMoveListMode(mode)
    }
}
