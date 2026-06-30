package org.rurbaniak.smartchessboard.data.board.ble

import com.russhwolf.settings.Settings
import org.rurbaniak.smartchessboard.domain.board.RememberedBoardStore

/**
 * [RememberedBoardStore] over multiplatform-settings (SharedPreferences / NSUserDefaults). The key is
 * `ble.`-prefixed so it never collides with the journal's `journal.` keys or the UI's `ui.` keys in
 * the same store. The durable [settings] is the single source of truth — no in-memory mirror is
 * needed because the connection screen only reads the remembered id once on entry (to decide
 * auto-connect), unlike the live-observed UI preferences.
 */
class SettingsRememberedBoardStore(
    private val settings: Settings,
) : RememberedBoardStore {
    override fun rememberedId(): String? = settings.getStringOrNull(REMEMBERED_BOARD_KEY)

    override fun remember(id: String) = settings.putString(REMEMBERED_BOARD_KEY, id)

    override fun forget() = settings.remove(REMEMBERED_BOARD_KEY)

    private companion object {
        const val REMEMBERED_BOARD_KEY = "ble.rememberedBoardId"
    }
}
