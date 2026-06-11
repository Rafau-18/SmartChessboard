package org.rurbaniak.smartchessboard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.rurbaniak.smartchessboard.auth.handleAuthDeeplink

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // OAuth callback arriving on a cold start (contract §4.2).
        handleAuthDeeplink(intent)

        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // OAuth callback returning to the existing singleTask instance.
        handleAuthDeeplink(intent)
    }
}
