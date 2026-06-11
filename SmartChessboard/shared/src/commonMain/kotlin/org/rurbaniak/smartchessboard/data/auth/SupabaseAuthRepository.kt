package org.rurbaniak.smartchessboard.data.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.rurbaniak.smartchessboard.domain.auth.AuthRepository
import org.rurbaniak.smartchessboard.domain.auth.SessionState

class SupabaseAuthRepository(
    private val client: SupabaseClient,
) : AuthRepository {
    override val sessionState: Flow<SessionState> =
        client.auth.sessionStatus.map { status ->
            when (status) {
                is SessionStatus.Initializing -> {
                    SessionState.Restoring
                }

                is SessionStatus.Authenticated -> {
                    status.session.user
                        ?.id
                        ?.let { SessionState.SignedIn(it) }
                        ?: SessionState.SignedOut
                }

                is SessionStatus.NotAuthenticated -> {
                    SessionState.SignedOut
                }

                // A session that cannot refresh cannot authorize any data access,
                // so the UI gates it the same as signed-out.
                is SessionStatus.RefreshFailure -> {
                    SessionState.SignedOut
                }
            }
        }

    override suspend fun signInWithGoogle() {
        client.auth.signInWith(Google)
    }

    override suspend fun signOut() {
        client.auth.signOut()
    }
}
