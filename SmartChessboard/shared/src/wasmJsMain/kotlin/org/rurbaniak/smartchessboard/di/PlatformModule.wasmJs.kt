package org.rurbaniak.smartchessboard.di

import com.russhwolf.settings.Settings
import com.russhwolf.settings.StorageSettings
import org.koin.core.module.Module
import org.koin.dsl.module

// localStorage is synchronous and needs no SharedArrayBuffer — the COOP/COEP lesson does not apply.
actual val platformModule: Module =
    module {
        single<Settings> { StorageSettings() }
    }
