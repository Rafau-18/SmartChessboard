package org.rurbaniak.smartchessboard.domain.preferences

import kotlinx.coroutines.flow.StateFlow

/** Theme preference: follow the OS, or force light/dark regardless of the system setting. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * A small durable store for UI-only choices (theme mode and wide-screen board size). Backed by the
 * same key-value store as the game journal but under a `ui.` key prefix, so the two never collide.
 * Each value is exposed as a [StateFlow] so the app frame recomposes when it changes. Reads are
 * **total** — an unset or invalid stored value resolves to a sensible default ([ThemeMode.SYSTEM] /
 * [BOARD_SIZE_DEFAULT]) and never throws.
 */
interface UiPreferences {
    /** The persisted theme mode, mirrored reactively so the UI follows changes. */
    val themeMode: StateFlow<ThemeMode>

    /** Persist [mode] and update [themeMode] live. */
    fun setThemeMode(mode: ThemeMode)

    /**
     * The persisted wide-screen board size as a fraction of the available pane width
     * ([BOARD_SIZE_MIN]..[BOARD_SIZE_MAX]), mirrored reactively. Phones ignore it (full-width auto-fit).
     */
    val boardSize: StateFlow<Float>

    /** Persist [fraction] (clamped to the valid range) and update [boardSize] live. */
    fun setBoardSize(fraction: Float)

    /**
     * The user's explicit move-list layout choice, or `null` when unset — in which case the layout
     * defaults by the container the list renders in (see [effectiveMoveListMode]). Mirrored reactively.
     */
    val moveListMode: StateFlow<MoveListMode?>

    /** Persist an explicit [mode] (overrides the by-container default) and update [moveListMode] live. */
    fun setMoveListMode(mode: MoveListMode)
}
