package org.rurbaniak.smartchessboard.presentation.connection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// The runtime permissions BLE scanning needs, by API level. On API 31+ (S) the modern split applies:
// BLUETOOTH_SCAN (declared neverForLocation in the manifest) + BLUETOOTH_CONNECT. On ≤30, a BLE scan
// is gated by ACCESS_FINE_LOCATION (the legacy BLUETOOTH/BLUETOOTH_ADMIN are install-time, not runtime),
// matching the minSdk-24 dual permission set declared in the app manifest.
private val blePermissions: Array<String>
    get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

@Composable
actual fun rememberBlePermissionController(onResult: (granted: Boolean) -> Unit): BlePermissionController {
    val context = LocalContext.current
    // Registered during composition (the contract's requirement). A multi-permission grant counts as
    // granted only when every requested permission is allowed.
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            onResult(grants.values.all { it })
        }
    return remember(context, launcher) {
        object : BlePermissionController {
            override fun isGranted(): Boolean =
                blePermissions.all {
                    context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                }

            override fun request() = launcher.launch(blePermissions)

            override fun openAppSettings() {
                val intent =
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
}
