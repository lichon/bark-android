package io.github.lichon.abark.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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

    /** Battery optimisation is the single biggest cause of missed pushes on Android. */
    private fun openBatterySettings() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager?.isIgnoringBatteryOptimizations(packageName) == true) return
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    companion object {
        const val EXTRA_MESSAGE_ID: String = "io.github.lichon.abark.extra.MESSAGE_ID"
    }
}
