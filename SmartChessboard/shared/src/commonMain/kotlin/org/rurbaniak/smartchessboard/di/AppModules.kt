package org.rurbaniak.smartchessboard.di

import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.rurbaniak.smartchessboard.data.auth.SupabaseAuthRepository
import org.rurbaniak.smartchessboard.data.eval.SupabaseEvalRepository
import org.rurbaniak.smartchessboard.data.games.SupabaseGamesRepository
import org.rurbaniak.smartchessboard.data.journal.SettingsGameJournal
import org.rurbaniak.smartchessboard.data.supabase.createAppSupabaseClient
import org.rurbaniak.smartchessboard.domain.auth.AuthRepository
import org.rurbaniak.smartchessboard.domain.eval.EvalRepository
import org.rurbaniak.smartchessboard.domain.games.GameAutoSaver
import org.rurbaniak.smartchessboard.domain.games.GameJournal
import org.rurbaniak.smartchessboard.domain.games.GamesRepository
import org.rurbaniak.smartchessboard.presentation.auth.AuthViewModel
import org.rurbaniak.smartchessboard.presentation.history.HistoryViewModel
import org.rurbaniak.smartchessboard.presentation.newgame.NewGameViewModel
import org.rurbaniak.smartchessboard.presentation.play.PlayViewModel
import org.rurbaniak.smartchessboard.presentation.replay.ReplayViewModel

val dataModule =
    module {
        single { createAppSupabaseClient() }
        single<AuthRepository> { SupabaseAuthRepository(get()) }
        single<GamesRepository> { SupabaseGamesRepository(get()) }
        single<EvalRepository> { SupabaseEvalRepository(get()) }
        single<GameJournal> { SettingsGameJournal(get()) }
        // Per-screen instance: syncPending tracks the one game a Play screen drives.
        factory { GameAutoSaver(gamesRepository = get(), journal = get()) }
    }

val presentationModule =
    module {
        viewModelOf(::AuthViewModel)
        viewModelOf(::HistoryViewModel)
        viewModelOf(::NewGameViewModel)
        // gameId arrives from the Replay nav entry via parametersOf(gameId).
        viewModel { (gameId: String) ->
            ReplayViewModel(gameId = gameId, gamesRepository = get(), evalRepository = get())
        }
        // gameId arrives from the Play nav entry via parametersOf(gameId); autoSaver is a
        // per-screen factory instance (its syncPending tracks this one game).
        viewModel { (gameId: String) ->
            PlayViewModel(gameId = gameId, gamesRepository = get(), autoSaver = get())
        }
    }

val appModules = listOf(platformModule, dataModule, presentationModule)

/** Single Koin bootstrap; each platform entry point (Android Application, iOS entry, web main) calls this once. */
fun initKoin(config: KoinAppDeclaration? = null) {
    startKoin {
        config?.invoke(this)
        modules(appModules)
    }
}
