package org.rurbaniak.smartchessboard.di

import org.koin.core.module.Module

/**
 * Platform-provided bindings — currently the durable [com.russhwolf.settings.Settings] backing
 * the game journal (SharedPreferences with synchronous commit / NSUserDefaults / localStorage).
 */
expect val platformModule: Module
