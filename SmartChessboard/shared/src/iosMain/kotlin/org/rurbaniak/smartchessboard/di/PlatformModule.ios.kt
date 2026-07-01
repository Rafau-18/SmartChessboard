package org.rurbaniak.smartchessboard.di

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.binds
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.rurbaniak.smartchessboard.data.board.ble.KableBoardAdapter
import org.rurbaniak.smartchessboard.data.board.ble.SettingsRememberedBoardStore
import org.rurbaniak.smartchessboard.domain.board.BoardConnection
import org.rurbaniak.smartchessboard.domain.board.BoardTransport
import org.rurbaniak.smartchessboard.domain.board.RememberedBoardStore
import org.rurbaniak.smartchessboard.presentation.connection.ConnectionViewModel
import org.rurbaniak.smartchessboard.presentation.physical.PhysicalPlayViewModel

// Android/iOS are the only targets that can drive a physical board (supportsPhysicalBoard), so the
// BoardConnection + PhysicalPlayViewModel are bound here and deliberately absent from the wasm module.
actual val platformModule: Module =
    module {
        single<Settings> { NSUserDefaultsSettings(platform.Foundation.NSUserDefaults.standardUserDefaults) }
        // S-09: the real Kable BLE adapter replaces the emulator (now test-only). See the Android module
        // for the constructed-idle / one-instance-two-faces (BoardConnection + BoardTransport) /
        // onClose-leak-fix rationale; kept in sync.
        single {
            KableBoardAdapter(scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))
        } onClose { it?.close() } binds arrayOf(BoardConnection::class, BoardTransport::class)
        viewModel { (gameId: String) ->
            PhysicalPlayViewModel(
                gameId = gameId,
                gamesRepository = get(),
                autoSaver = get(),
                boardConnection = get(),
                boardTransport = get(),
            )
        }
        // The connection screen (Phase 5) drives BoardTransport and remembers the last paired board.
        // Same Settings store as the journal (ble.-prefixed key); mobile-only, like PhysicalPlayViewModel.
        single<RememberedBoardStore> { SettingsRememberedBoardStore(get()) }
        viewModel { ConnectionViewModel(transport = get(), rememberedBoards = get(), scanTimeoutMs = 20_000L) }
    }
