package org.rurbaniak.smartchessboard.presentation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.rurbaniak.smartchessboard.domain.auth.AuthRepository
import org.rurbaniak.smartchessboard.domain.auth.SessionState

class FakeAuthRepository : AuthRepository {
    enum class SignInBehavior {
        /** OAuth round trip completes — a signed-in session arrives via the state flow. */
        SUCCESS,

        /** User abandons the browser flow — the call returns but no session ever arrives. */
        CANCELLED,

        /** Launch fails outright (network down, misconfiguration). */
        FAILURE,
    }

    val sessionFlow = MutableStateFlow<SessionState>(SessionState.Restoring)
    override val sessionState: Flow<SessionState> = sessionFlow

    var signInBehavior = SignInBehavior.SUCCESS
    var signedInUserId = "user-a"
    var signInCalls = 0
    var signOutCalls = 0

    override suspend fun signInWithGoogle() {
        signInCalls++
        when (signInBehavior) {
            SignInBehavior.SUCCESS -> sessionFlow.value = SessionState.SignedIn(signedInUserId)
            SignInBehavior.CANCELLED -> Unit
            SignInBehavior.FAILURE -> throw IllegalStateException("OAuth launch failed")
        }
    }

    override suspend fun signOut() {
        signOutCalls++
        sessionFlow.value = SessionState.SignedOut
    }
}
