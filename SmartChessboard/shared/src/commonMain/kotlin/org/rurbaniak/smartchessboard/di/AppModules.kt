package org.rurbaniak.smartchessboard.di

import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.rurbaniak.smartchessboard.data.auth.SupabaseAuthRepository
import org.rurbaniak.smartchessboard.data.games.SupabaseGamesRepository
import org.rurbaniak.smartchessboard.data.supabase.createAppSupabaseClient
import org.rurbaniak.smartchessboard.domain.auth.AuthRepository
import org.rurbaniak.smartchessboard.domain.games.GamesRepository
import org.rurbaniak.smartchessboard.presentation.auth.AuthViewModel
import org.rurbaniak.smartchessboard.presentation.history.HistoryViewModel

val dataModule =
    module {
        single { createAppSupabaseClient() }
        single<AuthRepository> { SupabaseAuthRepository(get()) }
        single<GamesRepository> { SupabaseGamesRepository(get()) }
    }

val presentationModule =
    module {
        viewModelOf(::AuthViewModel)
        viewModelOf(::HistoryViewModel)
    }

val appModules = listOf(dataModule, presentationModule)

/** Single Koin bootstrap; each platform entry point (Android Application, iOS entry, web main) calls this once. */
fun initKoin(config: KoinAppDeclaration? = null) {
    startKoin {
        config?.invoke(this)
        modules(appModules)
    }
}
