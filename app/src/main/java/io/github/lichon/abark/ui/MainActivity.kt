package io.github.lichon.abark.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val viewModel: AbarkViewModel by viewModels()

    private var notificationsGranted by mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph_init()
        notificationsGranted = hasNotificationPermission()

        setContent {
            AbarkTheme {
                AbarkScreen(
                    viewModel = viewModel,
                    notificationsGranted = notificationsGranted,
                    onRequestNotifications = { requestNotifications() },
                    onOpenBatterySettings = { openBatterySettings() },
                    initialMessageId = intent?.getStringExtra(EXTRA_MESSAGE_ID),
                )
            }
        }

        if (!notificationsGranted) requestNotifications()
    }

    private fun Graph_init() {
        io.github.lichon.abark.Graph.init(this)
    }

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Battery optimisation is the single biggest cause of missed pushes on Android.
     *
     * No single intent works everywhere: AOSP only answers the request dialog, while
     * several OEM ROMs (MIUI, ColorOS, One UI) reject or ignore it. So try a chain of
     * screens and stop at the first one that actually launches. The app itself is never
     * exempt-checked up front: tapping the button must always show something, even after
     * the exemption has already been granted.
     */
    private fun openBatterySettings() {
        val powerManager = getSystemService(PowerManager::class.java)
        val exempt = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        val packageUri = Uri.fromParts("package", packageName, null)

        val candidates = buildList {
            if (!exempt) {
                add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
            }
            // Full whitelist: usable even when the ROM ignores the direct request.
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            // App info page, always present, has the per-app background/battery entry.
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            add(Intent(Settings.ACTION_SETTINGS))
        }

        if (candidates.none { launch(it) }) {
            Toast.makeText(this, "No battery optimisation screen on this device", Toast.LENGTH_LONG).show()
        }
    }

    private fun launch(intent: Intent): Boolean =
        try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }

    companion object {
        const val EXTRA_MESSAGE_ID: String = "io.github.lichon.abark.extra.MESSAGE_ID"
    }
}
