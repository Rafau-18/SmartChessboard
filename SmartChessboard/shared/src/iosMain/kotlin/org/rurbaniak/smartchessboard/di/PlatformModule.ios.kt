package org.rurbaniak.smartchessboard.di

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.rurbaniak.smartchessboard.data.board.emulator.EmulatedBoard
import org.rurbaniak.smartchessboard.domain.board.BoardConnection
import org.rurbaniak.smartchessboard.presentation.physical.PhysicalPlayViewModel

// Android/iOS are the only targets that can drive a physical board (supportsPhysicalBoard), so the
// BoardConnection + PhysicalPlayViewModel are bound here and deliberately absent from the wasm module.
actual val platformModule: Module =
    module {
        single<Settings> { NSUserDefaultsSettings(platform.Foundation.NSUserDefaults.standardUserDefaults) }
        // S-06: the emulated board is the only BoardConnection until the S-09 BLE adapter ships (see the
        // Android module for the connect-on-bind / snapshot-recovery rationale).
        // TODO(S-09): this scope is never cancelled and disconnect() is never called — harmless for the
        // emulator, but a real BLE adapter bound on this shape would leak the connection. Cancel the scope
        // on teardown (e.g. Koin onClose { (it as? EmulatedBoard)?.disconnect() }) when the BLE adapter
        // lands. Keep in sync with the Android module's BoardConnection binding.
        single<BoardConnection> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            EmulatedBoard(scope = scope).also { board -> scope.launch { board.connect() } }
        }
        viewModel { (gameId: String) ->
            PhysicalPlayViewModel(
                gameId = gameId,
                gamesRepository = get(),
                autoSaver = get(),
                boardConnection = get(),
            )
        }
    }
