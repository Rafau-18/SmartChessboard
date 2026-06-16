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
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.koin.compose.viewmodel.koinViewModel
import org.rurbaniak.smartchessboard.domain.auth.SessionState
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameStatus
import org.rurbaniak.smartchessboard.presentation.auth.AuthViewModel
import org.rurbaniak.smartchessboard.presentation.auth.SignInScreen
import org.rurbaniak.smartchessboard.presentation.history.HistoryScreen
import org.rurbaniak.smartchessboard.presentation.navigation.HistoryKey
import org.rurbaniak.smartchessboard.presentation.navigation.NewGameKey
import org.rurbaniak.smartchessboard.presentation.navigation.PlayKey
import org.rurbaniak.smartchessboard.presentation.navigation.ReplayKey
import org.rurbaniak.smartchessboard.presentation.navigation.bindBrowserNavigation
import org.rurbaniak.smartchessboard.presentation.navigation.navSavedStateConfiguration
import org.rurbaniak.smartchessboard.presentation.newgame.NewGameScreen
import org.rurbaniak.smartchessboard.presentation.play.PlayScreen
import org.rurbaniak.smartchessboard.presentation.replay.ReplayScreen

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
                    nativeGoogleConfigured = BuildKonfig.GOOGLE_SERVER_CLIENT_ID.isNotEmpty(),
                    onSignInStarted = authViewModel::onInteractiveSignInStarted,
                    onSignInFailed = authViewModel::onInteractiveSignInFailed,
                    onBrowserFallback = authViewModel::signInWithGoogle,
                )
            }

            is SessionState.SignedIn -> {
                val backStack = rememberNavBackStack(navSavedStateConfiguration, HistoryKey)
                // On web, map the Nav3 back stack to browser history so Back/Forward move through
                // the app's stack instead of leaving the site. No-op on Android/iOS.
                bindBrowserNavigation(backStack)
                NavDisplay(
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    entryDecorators =
                        listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                    entryProvider =
                        entryProvider {
                            entry<HistoryKey> {
                                HistoryScreen(
                                    userId = session.userId,
                                    onSignOut = authViewModel::signOut,
                                    onNewGame = { backStack.add(NewGameKey) },
                                    // In-progress digital games resume on the interactive board;
                                    // everything else opens in Replay (interview decision).
                                    onGameClick = { game ->
                                        if (game.status == GameStatus.IN_PROGRESS && game.mode == GameMode.DIGITAL) {
                                            backStack.add(PlayKey(game.id))
                                        } else {
                                            backStack.add(ReplayKey(game.id))
                                        }
                                    },
                                )
                            }
                            entry<NewGameKey> {
                                NewGameScreen(
                                    onBack = { backStack.removeLastOrNull() },
                                    // Replace the form with Play so Back from Play returns to History.
                                    onGameCreated = { gameId ->
                                        backStack.removeLastOrNull()
                                        backStack.add(PlayKey(gameId))
                                    },
                                )
                            }
                            entry<ReplayKey> { key ->
                                ReplayScreen(
                                    gameId = key.gameId,
                                    onBack = { backStack.removeLastOrNull() },
                                )
                            }
                            entry<PlayKey> { key ->
                                PlayScreen(
                                    gameId = key.gameId,
                                    onBack = { backStack.removeLastOrNull() },
                                    // Replace the frozen Play with Replay so Back doesn't return to
                                    // a finished board.
                                    onReviewGame = {
                                        backStack.removeLastOrNull()
                                        backStack.add(ReplayKey(key.gameId))
                                    },
                                    // Pop to the History root (it is the back stack's root) — a
                                    // finished game shouldn't leave a frozen Play behind it.
                                    onBackToHistory = {
                                        while (backStack.size > 1) {
                                            backStack.removeLastOrNull()
                                        }
                                    },
                                )
                            }
                        },
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
