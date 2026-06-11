package org.rurbaniak.smartchessboard.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.rurbaniak.smartchessboard.domain.auth.SessionState
import org.rurbaniak.smartchessboard.presentation.auth.AuthViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private lateinit var repository: FakeAuthRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        repository = FakeAuthRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsInRestoringState() =
        runTest {
            val viewModel = AuthViewModel(repository)
            assertEquals(SessionState.Restoring, viewModel.uiState.value.sessionState)
        }

    @Test
    fun restoringResolvesToSignedOutWhenNoSession() =
        runTest {
            val viewModel = AuthViewModel(repository)
            repository.sessionFlow.value = SessionState.SignedOut
            advanceUntilIdle()
            assertEquals(SessionState.SignedOut, viewModel.uiState.value.sessionState)
        }

    @Test
    fun restoringResolvesToSignedInWhenSessionRestored() =
        runTest {
            val viewModel = AuthViewModel(repository)
            repository.sessionFlow.value = SessionState.SignedIn("user-a")
            advanceUntilIdle()
            assertEquals(SessionState.SignedIn("user-a"), viewModel.uiState.value.sessionState)
        }

    @Test
    fun signInSuccessTransitionsToSignedIn() =
        runTest {
            val viewModel = AuthViewModel(repository)
            repository.sessionFlow.value = SessionState.SignedOut
            advanceUntilIdle()

            viewModel.signInWithGoogle()
            advanceUntilIdle()

            assertEquals(SessionState.SignedIn("user-a"), viewModel.uiState.value.sessionState)
            assertFalse(viewModel.uiState.value.isSigningIn)
            assertFalse(viewModel.uiState.value.signInFailed)
        }

    @Test
    fun signInCancelledStaysSignedOutWithoutErrorOrStuckSpinner() =
        runTest {
            repository.signInBehavior = FakeAuthRepository.SignInBehavior.CANCELLED
            val viewModel = AuthViewModel(repository)
            repository.sessionFlow.value = SessionState.SignedOut
            advanceUntilIdle()

            viewModel.signInWithGoogle()
            advanceUntilIdle()

            assertEquals(SessionState.SignedOut, viewModel.uiState.value.sessionState)
            assertFalse(viewModel.uiState.value.isSigningIn)
            assertFalse(viewModel.uiState.value.signInFailed)
        }

    @Test
    fun signInFailureSurfacesRetryableError() =
        runTest {
            repository.signInBehavior = FakeAuthRepository.SignInBehavior.FAILURE
            val viewModel = AuthViewModel(repository)
            repository.sessionFlow.value = SessionState.SignedOut
            advanceUntilIdle()

            viewModel.signInWithGoogle()
            advanceUntilIdle()

            assertEquals(SessionState.SignedOut, viewModel.uiState.value.sessionState)
            assertFalse(viewModel.uiState.value.isSigningIn)
            assertTrue(viewModel.uiState.value.signInFailed)
        }

    @Test
    fun retryAfterFailureCanSucceed() =
        runTest {
            repository.signInBehavior = FakeAuthRepository.SignInBehavior.FAILURE
            val viewModel = AuthViewModel(repository)
            repository.sessionFlow.value = SessionState.SignedOut
            advanceUntilIdle()

            viewModel.signInWithGoogle()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.signInFailed)

            repository.signInBehavior = FakeAuthRepository.SignInBehavior.SUCCESS
            viewModel.signInWithGoogle()
            advanceUntilIdle()

            assertEquals(SessionState.SignedIn("user-a"), viewModel.uiState.value.sessionState)
            assertFalse(viewModel.uiState.value.signInFailed)
            assertEquals(2, repository.signInCalls)
        }

    @Test
    fun retryClearsPreviousFailureFlagImmediately() =
        runTest {
            repository.signInBehavior = FakeAuthRepository.SignInBehavior.FAILURE
            val viewModel = AuthViewModel(repository)
            repository.sessionFlow.value = SessionState.SignedOut
            advanceUntilIdle()
            viewModel.signInWithGoogle()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.signInFailed)

            viewModel.signInWithGoogle()
            assertFalse(viewModel.uiState.value.signInFailed)
        }

    @Test
    fun signOutReturnsToSignedOut() =
        runTest {
            val viewModel = AuthViewModel(repository)
            repository.sessionFlow.value = SessionState.SignedIn("user-a")
            advanceUntilIdle()

            viewModel.signOut()
            advanceUntilIdle()

            assertEquals(SessionState.SignedOut, viewModel.uiState.value.sessionState)
            assertEquals(1, repository.signOutCalls)
        }
}
