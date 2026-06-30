package org.rurbaniak.smartchessboard.presentation.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * The app's typed back-stack keys. Each route is `@Serializable` so Nav3 can save/restore the back
 * stack on every target.
 */
@Serializable
data object HistoryKey : NavKey

/** The new-digital-game form. Replaced by [PlayKey] on the back stack once the game is created. */
@Serializable
data object NewGameKey : NavKey

@Serializable
data class ReplayKey(
    val gameId: String,
) : NavKey

/** An in-progress digital game played on the interactive board. */
@Serializable
data class PlayKey(
    val gameId: String,
) : NavKey

/**
 * The BLE connect / pair gate guarding an in-progress physical game ([gameId]). Reached only on
 * platforms where `supportsPhysicalBoard` is true; on a successful connect it is replaced by
 * [PhysicalPlayKey] (so Back returns to History, not the connect screen). Web never links here.
 */
@Serializable
data class ConnectionKey(
    val gameId: String,
) : NavKey

/**
 * An in-progress physical game played against the reed-switch board over BLE. Reached only on
 * platforms where `supportsPhysicalBoard` is true (via [ConnectionKey] first); a physical game on web
 * routes to [ReplayKey].
 */
@Serializable
data class PhysicalPlayKey(
    val gameId: String,
) : NavKey

/**
 * Save/restore configuration for the Nav3 back stack. iOS/wasm have no reflection, so every
 * [NavKey] subtype is registered **explicitly** with its compile-time-generated serializer — that
 * polymorphic registration (not runtime reflection) is what makes the back stack survive state
 * save/restore on Native/wasm. A non-registered NavKey crashes save/restore there: a permanent
 * multiplatform constraint, not a stability caveat. Pass this to `rememberNavBackStack`.
 */
val navSavedStateConfiguration: SavedStateConfiguration =
    SavedStateConfiguration {
        serializersModule =
            SerializersModule {
                polymorphic(NavKey::class) {
                    subclass(HistoryKey::class, HistoryKey.serializer())
                    subclass(NewGameKey::class, NewGameKey.serializer())
                    subclass(ReplayKey::class, ReplayKey.serializer())
                    subclass(PlayKey::class, PlayKey.serializer())
                    subclass(ConnectionKey::class, ConnectionKey.serializer())
                    subclass(PhysicalPlayKey::class, PhysicalPlayKey.serializer())
                }
            }
    }
