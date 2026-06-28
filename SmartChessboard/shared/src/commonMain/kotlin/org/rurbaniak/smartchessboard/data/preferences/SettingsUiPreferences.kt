package org.rurbaniak.smartchessboard.data.preferences

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.rurbaniak.smartchessboard.domain.preferences.BOARD_SIZE_DEFAULT
import org.rurbaniak.smartchessboard.domain.preferences.ThemeMode
import org.rurbaniak.smartchessboard.domain.preferences.UiPreferences
import org.rurbaniak.smartchessboard.domain.preferences.clampBoardSize

/**
 * [UiPreferences] over multiplatform-settings (SharedPreferences / NSUserDefaults / localStorage).
 * Keys are `ui.`-prefixed so they never collide with the journal's `journal.` keys in the same
 * store. The durable [settings] is the source of truth; the current value is mirrored in a
 * [MutableStateFlow] seeded from the store at construction, so a fresh instance over the same store
 * re-reads the persisted choice (the "survives restart" guarantee). Reads are total —
 * [readThemeMode] maps an unset or unrecognized value to [ThemeMode.SYSTEM], and [readBoardSize]
 * clamps an unset or out-of-range value to [BOARD_SIZE_DEFAULT].
 */
class SettingsUiPreferences(
    private val settings: Settings,
) : UiPreferences {
    private val _themeMode = MutableStateFlow(readThemeMode())
    override val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _boardSize = MutableStateFlow(readBoardSize())
    override val boardSize: StateFlow<Float> = _boardSize.asStateFlow()

    override fun setThemeMode(mode: ThemeMode) {
        settings.putString(THEME_MODE_KEY, mode.name)
        _themeMode.value = mode
    }

    override fun setBoardSize(fraction: Float) {
        val clamped = clampBoardSize(fraction)
        settings.putFloat(BOARD_SIZE_KEY, clamped)
        _boardSize.value = clamped
    }

    private fun readThemeMode(): ThemeMode {
        val stored = settings.getStringOrNull(THEME_MODE_KEY)
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
    }

    // Total read: an unset key falls back to the default before clamping, and any stored value
    // (including a corrupt out-of-range one) is clamped into the valid range.
    private fun readBoardSize(): Float = clampBoardSize(settings.getFloat(BOARD_SIZE_KEY, BOARD_SIZE_DEFAULT))

    private companion object {
        const val THEME_MODE_KEY = "ui.themeMode"
        const val BOARD_SIZE_KEY = "ui.boardSize"
    }
}
