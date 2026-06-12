package org.rurbaniak.smartchessboard

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.rurbaniak.smartchessboard.di.initKoin

class SmartChessboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            // Context for the game journal's SharedPreferences provisioning (platformModule).
            androidContext(this@SmartChessboardApplication)
        }
    }
}
