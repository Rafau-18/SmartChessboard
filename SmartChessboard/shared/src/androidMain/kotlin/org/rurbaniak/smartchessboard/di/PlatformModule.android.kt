package org.rurbaniak.smartchessboard.di

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.binds
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.rurbaniak.smartchessboard.data.board.ble.KableBoardAdapter
import org.rurbaniak.smartchessboard.domain.board.BoardConnection
import org.rurbaniak.smartchessboard.domain.board.BoardTransport
import org.rurbaniak.smartchessboard.presentation.physical.PhysicalPlayViewModel

// commit = true: the journal write must be durable before a move counts as accepted (§6.2);
// the default async apply() can lose the write on process death — exactly the protected window.
//
// Android/iOS are the only targets that can drive a physical board (supportsPhysicalBoard), so the
// BoardConnection + PhysicalPlayViewModel are bound here and deliberately absent from the wasm module.
actual val platformModule: Module =
    module {
        single<Settings> {
            SharedPreferencesSettings(
                androidContext().getSharedPreferences("game_journal", Context.MODE_PRIVATE),
                commit = true,
            )
        }
        // S-09: the real Kable BLE adapter replaces the emulator (now test-only — never bound in
        // production DI). One instance serves both faces: the move-flow port (BoardConnection) and the
        // connection-screen driver (BoardTransport, Phase 5). Constructed idle — unlike the emulator's
        // connect-on-bind, nothing scans or connects until the connection screen drives BoardTransport;
        // the link (and the adapter scope) is dropped via onClose on graph teardown — the prescribed
        // TODO(S-09) leak fix. Keep the iOS module's binding in sync.
        single {
            KableBoardAdapter(scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))
        } onClose { it?.close() } binds arrayOf(BoardConnection::class, BoardTransport::class)
        viewModel { (gameId: String) ->
            PhysicalPlayViewModel(
                gameId = gameId,
                gamesRepository = get(),
                autoSaver = get(),
                boardConnection = get(),
            )
        }
    }
