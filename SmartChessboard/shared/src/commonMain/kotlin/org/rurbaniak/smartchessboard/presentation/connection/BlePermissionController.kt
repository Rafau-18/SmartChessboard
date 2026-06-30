package org.rurbaniak.smartchessboard.presentation.connection

import androidx.compose.runtime.Composable

/**
 * The platform BLE-permission gate the connection screen drives **before** it asks [org.rurbaniak.smartchessboard.domain.board.BoardTransport]
 * to scan. Kept out of the MVI core ([ConnectionViewModel] / [reduceConnection]) so the reducer stays
 * pure and exhaustively testable: the screen owns the OS handshake and feeds the boolean result back
 * as a [ConnectionMsg.PermissionGranted] / [ConnectionMsg.PermissionDenied] intent.
 *
 * Platform shapes differ (declared as an `expect` Composable with one `actual` per target):
 * - **Android** requests the runtime permissions (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` on API 31+, or
 *   `ACCESS_FINE_LOCATION` on ≤30) via an Activity-result launcher.
 * - **iOS** has no pre-scan request — the system prompt appears on first `CBCentralManager` use (driven
 *   by Kable), gated by the Info.plist usage string — so the gate reports granted and lets the OS prompt.
 * - **wasm** is a no-op `Unsupported` stub: web is digital-only, the connection route is never
 *   registered there, so this is never invoked — it exists only because every commonMain `expect`
 *   needs a wasmJs `actual` to compile (mirrors the `supportsPhysicalBoard = false` precedent).
 */
interface BlePermissionController {
    /** Snapshot of whether the BLE permissions are already granted, so the screen can skip the prompt. */
    fun isGranted(): Boolean

    /** Trigger the OS permission request; the boolean result is delivered to the screen's `onResult`. */
    fun request()

    /** Open the OS app-settings page (the "denied → enable in Settings" escape hatch). */
    fun openAppSettings()
}

/**
 * Remember a [BlePermissionController] wired to [onResult] (invoked with the grant outcome after
 * [BlePermissionController.request]). Composable because the Android actual installs an Activity-result
 * launcher, which must be registered during composition.
 */
@Composable
expect fun rememberBlePermissionController(onResult: (granted: Boolean) -> Unit): BlePermissionController
