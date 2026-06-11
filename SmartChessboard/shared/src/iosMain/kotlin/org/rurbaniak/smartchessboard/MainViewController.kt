package org.rurbaniak.smartchessboard

import androidx.compose.ui.window.ComposeUIViewController

// PascalCase kept: this is the entry point Swift references as MainViewControllerKt.MainViewController().
@Suppress("ktlint:standard:function-naming")
fun MainViewController() = ComposeUIViewController { App() }
