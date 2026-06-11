package org.rurbaniak.smartchessboard

import android.app.Application
import org.rurbaniak.smartchessboard.di.initKoin

class SmartChessboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin()
    }
}
