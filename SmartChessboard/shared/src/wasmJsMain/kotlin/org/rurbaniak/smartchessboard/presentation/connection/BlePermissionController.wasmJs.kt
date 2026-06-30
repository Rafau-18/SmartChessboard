package org.rurbaniak.smartchessboard.presentation.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Web is digital-only: the connection route is never registered on web (`supportsPhysicalBoard = false`),
 * so this stub is never invoked. It exists only because this project has no intermediate mobile source
 * set — every commonMain `expect` needs a wasmJs `actual` or `:webApp` won't compile (mirrors the
 * `supportsPhysicalBoard = false` precedent). Reports unsupported (never granted) and no-ops.
 */
@Composable
actual fun rememberBlePermissionController(onResult: (granted: Boolean) -> Unit): BlePermissionController =
    remember {
        object : BlePermissionController {
            override fun isGranted(): Boolean = false

            override fun request() = onResult(false)

            override fun openAppSettings() = Unit
        }
    }
