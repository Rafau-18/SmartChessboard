package org.rurbaniak.smartchessboard.data.preferences

import com.russhwolf.settings.MapSettings
import org.rurbaniak.smartchessboard.domain.preferences.BOARD_SIZE_DEFAULT
import org.rurbaniak.smartchessboard.domain.preferences.BOARD_SIZE_MAX
import org.rurbaniak.smartchessboard.domain.preferences.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsUiPreferencesTest {
    @Test
    fun defaultsToSystemWhenUnset() {
        val prefs = SettingsUiPreferences(MapSettings())

        assertEquals(ThemeMode.SYSTEM, prefs.themeMode.value)
    }

    @Test
    fun setThemeModeUpdatesTheFlowLive() {
        val prefs = SettingsUiPreferences(MapSettings())

        prefs.setThemeMode(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, prefs.themeMode.value)
    }

    @Test
    fun setThemeModePersistsAcrossInstancesOverTheSameStore() {
        val settings = MapSettings()
        SettingsUiPreferences(settings).setThemeMode(ThemeMode.DARK)

        // A fresh instance over the same store re-reads the persisted choice — the "survives restart"
        // guarantee, since each platform's real store is process-durable.
        assertEquals(ThemeMode.DARK, SettingsUiPreferences(settings).themeMode.value)
    }

    @Test
    fun unrecognizedStoredValueFallsBackToSystem() {
        val settings = MapSettings().apply { putString("ui.themeMode", "PLAID") }

        assertEquals(ThemeMode.SYSTEM, SettingsUiPreferences(settings).themeMode.value)
    }

    @Test
    fun boardSizeDefaultsWhenUnset() {
        val prefs = SettingsUiPreferences(MapSettings())

        assertEquals(BOARD_SIZE_DEFAULT, prefs.boardSize.value)
    }

    @Test
    fun setBoardSizeUpdatesTheFlowLive() {
        val prefs = SettingsUiPreferences(MapSettings())

        prefs.setBoardSize(0.55f)

        assertEquals(0.55f, prefs.boardSize.value)
    }

    @Test
    fun setBoardSizePersistsAcrossInstancesOverTheSameStore() {
        val settings = MapSettings()
        SettingsUiPreferences(settings).setBoardSize(0.6f)

        // A fresh instance re-reads the persisted size — the "survives restart" guarantee.
        assertEquals(0.6f, SettingsUiPreferences(settings).boardSize.value)
    }

    @Test
    fun setBoardSizeClampsOutOfRangeValues() {
        val prefs = SettingsUiPreferences(MapSettings())

        prefs.setBoardSize(99f)

        assertEquals(BOARD_SIZE_MAX, prefs.boardSize.value)
    }

    @Test
    fun outOfRangeStoredBoardSizeIsClampedOnRead() {
        val settings = MapSettings().apply { putFloat("ui.boardSize", 5f) }

        assertEquals(BOARD_SIZE_MAX, SettingsUiPreferences(settings).boardSize.value)
    }
}
