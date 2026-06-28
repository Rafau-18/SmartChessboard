package org.rurbaniak.smartchessboard.presentation.board

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import org.rurbaniak.smartchessboard.domain.preferences.UiPreferences

/**
 * Exposes the persisted wide-screen board size to the board screens (Replay / Play / PhysicalPlay),
 * MVVM-style (the project default per lessons.md). The value is sourced from the singleton
 * [UiPreferences], so every screen that resolves this ViewModel reads and writes the same durable
 * fraction — a resize on one screen is the size every screen sees, and it survives a restart.
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
}
