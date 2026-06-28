package org.rurbaniak.smartchessboard.data.preferences

import com.russhwolf.settings.MapSettings
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
}
