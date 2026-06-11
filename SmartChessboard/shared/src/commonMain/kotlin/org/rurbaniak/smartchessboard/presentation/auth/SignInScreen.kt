package org.rurbaniak.smartchessboard.presentation.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.compose.auth.composable.NativeSignInResult
import io.github.jan.supabase.compose.auth.composable.rememberSignInWithGoogle
import io.github.jan.supabase.compose.auth.composeAuth
import org.koin.compose.koinInject

@Composable
fun SignInScreen(
    isSigningIn: Boolean,
    signInFailed: Boolean,
    nativeGoogleConfigured: Boolean,
    onSignInStarted: () -> Unit,
    onSignInFailed: () -> Unit,
    onBrowserFallback: () -> Unit,
) {
    val client = koinInject<SupabaseClient>()
    // Native Google sign-in on Android (Credential Manager). On iOS/web there is no native
    // provider, so `fallback` runs the existing browser OAuth flow instead. On success the
    // session is imported into the client and surfaces via sessionStatus → the root switches
    // to History on its own, so there is nothing to do here.
    val googleSignIn =
        client.composeAuth.rememberSignInWithGoogle(
            onResult = { result ->
                when (result) {
                    NativeSignInResult.ClosedByUser -> Unit

                    // intentional cancel — no error message
                    is NativeSignInResult.Error -> onSignInFailed()

                    is NativeSignInResult.NetworkError -> onSignInFailed()

                    is NativeSignInResult.Success -> Unit // session arrives via sessionStatus
                }
            },
            fallback = { onBrowserFallback() },
        )

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeContentPadding()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Smart Chessboard", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in to see your game history.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                onSignInStarted()
                // Without a configured Web client ID the native sheet can only error, so skip
                // straight to the browser flow. When configured, startFlow() runs the native
                // Credential Manager sheet on Android and self-falls-back to the browser on iOS/web.
                if (nativeGoogleConfigured) {
                    googleSignIn.startFlow()
                } else {
                    onBrowserFallback()
                }
            },
            enabled = !isSigningIn,
        ) {
            Text(if (isSigningIn) "Opening Google…" else "Continue with Google")
        }
        // Universal escape hatch: the native sheet fails to render on some OEM ROMs
        // (e.g. OxygenOS/ColorOS), returning a silent cancel. The browser flow always works,
        // so it stays one tap away. Redundant when native isn't configured (the primary button
        // already runs the browser flow), so it's only shown alongside the native path.
        if (nativeGoogleConfigured) {
            TextButton(
                onClick = {
                    onSignInStarted()
                    onBrowserFallback()
                },
                enabled = !isSigningIn,
            ) {
                Text("Trouble signing in? Continue in browser")
            }
        }
        if (signInFailed) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Sign-in didn't complete. Please try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}
