package org.rurbaniak.smartchessboard.di

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

// commit = true: the journal write must be durable before a move counts as accepted (§6.2);
// the default async apply() can lose the write on process death — exactly the protected window.
actual val platformModule: Module =
    module {
        single<Settings> {
            SharedPreferencesSettings(
                androidContext().getSharedPreferences("game_journal", Context.MODE_PRIVATE),
                commit = true,
            )
        }
    }
