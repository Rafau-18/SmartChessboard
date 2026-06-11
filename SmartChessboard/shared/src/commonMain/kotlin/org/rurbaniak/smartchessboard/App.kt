package org.rurbaniak.smartchessboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.rurbaniak.smartchessboard.domain.auth.SessionState
import org.rurbaniak.smartchessboard.presentation.auth.AuthViewModel
import org.rurbaniak.smartchessboard.presentation.auth.SignInScreen
import org.rurbaniak.smartchessboard.presentation.history.HistoryScreen

@Composable
fun App() {
    MaterialTheme {
        val authViewModel = koinViewModel<AuthViewModel>()
        val uiState by authViewModel.uiState.collectAsStateWithLifecycle()
        when (val session = uiState.sessionState) {
            SessionState.Restoring -> {
                RestoringScreen()
            }

            SessionState.SignedOut -> {
                SignInScreen(
                    isSigningIn = uiState.isSigningIn,
                    signInFailed = uiState.signInFailed,
                    onContinueWithGoogle = authViewModel::signInWithGoogle,
                )
            }

            is SessionState.SignedIn -> {
                HistoryScreen(
                    userId = session.userId,
                    onSignOut = authViewModel::signOut,
                )
            }
        }
    }
}

/**
 * Shown while a persisted session (or, on web, the OAuth redirect callback) is
 * still being consumed — the root must not flash the sign-in screen during this window.
 */
@Composable
private fun RestoringScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
