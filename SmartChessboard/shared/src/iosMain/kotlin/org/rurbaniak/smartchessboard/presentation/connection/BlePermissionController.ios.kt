package org.rurbaniak.smartchessboard.presentation.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * iOS has no pre-scan runtime permission request: the system Bluetooth prompt appears automatically
 * the first time a `CBCentralManager` is used (Kable creates one on scan/connect), gated by the
 * Info.plist `NSBluetoothAlwaysUsageDescription` string. So the gate reports granted and lets the OS
 * prompt on first use (manual gate 5.5) — foreground-first, no background mode.
 */
@Composable
actual fun rememberBlePermissionController(onResult: (granted: Boolean) -> Unit): BlePermissionController =
    remember {
        object : BlePermissionController {
            override fun isGranted(): Boolean = true

            override fun request() = onResult(true)

            override fun openAppSettings() {
                val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
                UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
            }
        }
    }
