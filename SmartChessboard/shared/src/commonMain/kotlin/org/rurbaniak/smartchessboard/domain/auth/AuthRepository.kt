package org.rurbaniak.smartchessboard.domain.auth

import kotlinx.coroutines.flow.Flow

/**
 * Restoring covers the startup window where a persisted session (or, on web, the
 * OAuth redirect callback) is still being consumed — the UI must not conclude
 * SignedOut until this state resolves.
 */
sealed interface SessionState {
    data object Restoring : SessionState

    data object SignedOut : SessionState

    data class SignedIn(
        val userId: String,
    ) : SessionState
}

interface AuthRepository {
    val sessionState: Flow<SessionState>

    suspend fun signInWithGoogle()

    suspend fun signOut()
}
