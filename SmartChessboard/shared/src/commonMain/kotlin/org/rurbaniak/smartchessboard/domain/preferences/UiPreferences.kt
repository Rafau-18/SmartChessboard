package org.rurbaniak.smartchessboard.domain.preferences

import kotlinx.coroutines.flow.StateFlow

/** Theme preference: follow the OS, or force light/dark regardless of the system setting. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * A small durable store for UI-only choices (theme mode now; board size lands in Phase 2). Backed
 * by the same key-value store as the game journal but under a `ui.` key prefix, so the two never
 * collide. The current value is exposed as a [StateFlow] so the app frame recomposes when it
 * changes. Reads are **total** — an unset or unrecognized stored value resolves to a sensible
 * default ([ThemeMode.SYSTEM]) and never throws.
 */
interface UiPreferences {
    /** The persisted theme mode, mirrored reactively so the UI follows changes. */
    val themeMode: StateFlow<ThemeMode>

    /** Persist [mode] and update [themeMode] live. */
    fun setThemeMode(mode: ThemeMode)
}
