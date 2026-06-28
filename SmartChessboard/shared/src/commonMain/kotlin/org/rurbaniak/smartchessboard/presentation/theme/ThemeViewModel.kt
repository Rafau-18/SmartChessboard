package org.rurbaniak.smartchessboard.presentation.theme

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import org.rurbaniak.smartchessboard.domain.preferences.ThemeMode
import org.rurbaniak.smartchessboard.domain.preferences.UiPreferences

/**
 * Holds the active [ThemeMode] for the whole app frame (MVVM, the project default per lessons.md).
 * The mode is sourced from [UiPreferences] — the durable, reactive backing — and re-exposed here so
 * the composition root can drive [org.rurbaniak.smartchessboard.presentation.theme.AppTheme]. Both
 * [setMode] and [cycle] persist through the same store, so the choice survives a restart on every
 * target.
 */
class ThemeViewModel(
    private val prefs: UiPreferences,
) : ViewModel() {
    val mode: StateFlow<ThemeMode> = prefs.themeMode

    fun setMode(mode: ThemeMode) {
        prefs.setThemeMode(mode)
    }

    /** The History control's single action: System → Light → Dark → System. */
    fun cycle() {
        val next =
            when (mode.value) {
                ThemeMode.SYSTEM -> ThemeMode.LIGHT
                ThemeMode.LIGHT -> ThemeMode.DARK
                ThemeMode.DARK -> ThemeMode.SYSTEM
            }
        setMode(next)
    }
}
