package org.rurbaniak.smartchessboard.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.rurbaniak.smartchessboard.domain.auth.AuthRepository
import org.rurbaniak.smartchessboard.domain.auth.SessionState
import kotlin.coroutines.cancellation.CancellationException

data class AuthUiState(
    val sessionState: SessionState = SessionState.Restoring,
    /** True only while the sign-in launch call is in flight — never while the user is off in the browser. */
    val isSigningIn: Boolean = false,
    val signInFailed: Boolean = false,
)

class AuthViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.sessionState.collect { state ->
                _uiState.update { it.copy(sessionState = state) }
            }
        }
    }

    fun signInWithGoogle() {
        if (_uiState.value.isSigningIn) return
        _uiState.update { it.copy(isSigningIn = true, signInFailed = false) }
        viewModelScope.launch {
            try {
                authRepository.signInWithGoogle()
                // On mobile this returns once the external browser is launched; completion
                // (or the user abandoning the flow) arrives via sessionState — so the
                // spinner must not wait for a session, or a cancelled flow would hang it.
                _uiState.update { it.copy(isSigningIn = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(isSigningIn = false, signInFailed = true) }
            }
        }
    }

    /**
     * Native (Credential Manager) sign-in is driven by a Compose-side launcher, not the
     * repository, so the VM only owns the error surface for it. Called when the user taps
     * the button: clear any stale failure before the native sheet (or browser fallback) runs.
     */
    fun onInteractiveSignInStarted() {
        _uiState.update { it.copy(signInFailed = false) }
    }

    /** Native sign-in failed (not a user cancel) — show the friendly retry message. */
    fun onInteractiveSignInFailed() {
        _uiState.update { it.copy(isSigningIn = false, signInFailed = true) }
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                authRepository.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Sign-out failure is non-fatal: sessionState remains the single
                // source of truth for what the UI shows.
            }
        }
    }
}
