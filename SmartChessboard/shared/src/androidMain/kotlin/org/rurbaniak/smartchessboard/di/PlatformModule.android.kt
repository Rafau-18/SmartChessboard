package org.rurbaniak.smartchessboard.di

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.rurbaniak.smartchessboard.data.board.emulator.EmulatedBoard
import org.rurbaniak.smartchessboard.domain.board.BoardConnection
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
        // S-06: the emulated board is the only BoardConnection until the S-09 BLE adapter ships. It is
        // connected on a long-lived app scope so a physical screen finds it live; the ViewModel
        // re-requests a snapshot on the CONNECTED transition, so a missed on-connect burst is recovered.
        // TODO(S-09): this scope is never cancelled and disconnect() is never called — harmless for the
        // emulator (process-lifetime singleton), but a real BLE adapter bound on this exact shape would
        // leak the connection. When the BLE adapter lands, cancel the scope on teardown (e.g. Koin
        // onClose { (it as? EmulatedBoard)?.disconnect() }). Keep the iOS module's binding in sync.
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
