package org.rurbaniak.smartchessboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.resources.painterResource
import org.rurbaniak.smartchessboard.data.ProbeResult
import org.rurbaniak.smartchessboard.data.probeSupabase
import smartchessboard.shared.generated.resources.Res
import smartchessboard.shared.generated.resources.compose_multiplatform

@Composable
@Preview
fun App() {
    MaterialTheme {
        var showContent by remember { mutableStateOf(false) }
        var supabaseStatus by remember { mutableStateOf("Connecting to Supabase…") }
        LaunchedEffect(Unit) {
            supabaseStatus =
                when (val result = probeSupabase()) {
                    is ProbeResult.Ok -> {
                        "Connected to Supabase ✓ (anon sees ${result.visibleRows} rows — RLS enforced)"
                    }

                    is ProbeResult.Error -> {
                        "Supabase error: ${result.message}"
                    }
                }
        }
        Column(
            modifier =
                Modifier
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .safeContentPadding()
                    .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(supabaseStatus)
            Button(onClick = { showContent = !showContent }) {
                Text("Click me!")
            }
            AnimatedVisibility(showContent) {
                val greeting = remember { Greeting().greet() }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(painterResource(Res.drawable.compose_multiplatform), null)
                    Text("Compose: $greeting")
                }
            }
        }
    }
}
