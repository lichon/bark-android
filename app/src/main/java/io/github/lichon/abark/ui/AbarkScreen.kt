package io.github.lichon.abark.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight

/** The three top-level destinations. Order is the order of the bottom bar. */
enum class AbarkTab(val label: String, val icon: ImageVector) {
    Servers("Servers", Icons.AutoMirrored.Filled.List),
    Messages("Messages", Icons.Filled.Notifications),
    Settings("Settings", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AbarkScreen(
    viewModel: AbarkViewModel,
    notificationsGranted: Boolean,
    onRequestNotifications: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    initialMessageId: String? = null,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(AbarkTab.Servers) }
    var showImport by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    // Tapping a notification opens the inbox, where the push it refers to is marked read.
    LaunchedEffect(initialMessageId) {
        initialMessageId?.let {
            viewModel.markRead(it)
            tab = AbarkTab.Messages
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ABark", fontWeight = FontWeight.SemiBold) },
            )
        },
        bottomBar = {
            AbarkNavigationBar(
                selected = tab,
                unread = state.messages.count { !it.read },
                onSelect = { tab = it },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                AbarkTab.Servers -> ServersTab(
                    state = state,
                    notificationsGranted = notificationsGranted,
                    onStart = viewModel::start,
                    onStop = viewModel::stop,
                    onActivate = viewModel::activateIdentity,
                    onRegister = { url -> viewModel.register(url) },
                    onTestPush = viewModel::testPush,
                    onCopyShareLink = viewModel::copyShareLink,
                    onImport = { showImport = true },
                    onRequestNotifications = onRequestNotifications,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onRealtimeEnabled = viewModel::setRealtimeEnabled,
                    onConnectRealtime = viewModel::connectRealtime,
                )

                AbarkTab.Messages -> MessagesTab(
                    state = state,
                    notificationsGranted = notificationsGranted,
                    onClear = viewModel::clearMessages,
                    onMarkRead = viewModel::markRead,
                    onCopy = viewModel::copyMessage,
                    onDelete = viewModel::deleteMessage,
                    onRequestNotifications = onRequestNotifications,
                )

                AbarkTab.Settings -> SettingsTab(
                    state = state,
                    notificationsGranted = notificationsGranted,
                    onAutoStart = viewModel::setAutoStart,
                    onTts = viewModel::setTts,
                    onKeepAlive = viewModel::setKeepAlive,
                    onActivate = viewModel::activateIdentity,
                    onImport = { showImport = true },
                    onClearIdentity = viewModel::clearIdentity,
                    onCheckForUpdates = viewModel::checkForUpdates,
                    onRequestNotifications = onRequestNotifications,
                    onBattery = onOpenBatterySettings,
                )
            }
        }
    }

    if (showImport) {
        ImportIdentityDialog(
            onDismiss = { showImport = false },
            onImport = {
                viewModel.importIdentity(it)
                showImport = false
            },
        )
    }
}

@Composable
private fun AbarkNavigationBar(selected: AbarkTab, unread: Int, onSelect: (AbarkTab) -> Unit) {
    NavigationBar {
        AbarkTab.entries.forEach { entry ->
            NavigationBarItem(
                selected = selected == entry,
                onClick = { onSelect(entry) },
                icon = {
                    if (entry == AbarkTab.Messages && unread > 0) {
                        BadgedBox(badge = { Badge { Text(unread.toString()) } }) {
                            Icon(entry.icon, contentDescription = null)
                        }
                    } else {
                        Icon(entry.icon, contentDescription = null)
                    }
                },
                label = { Text(entry.label) },
            )
        }
    }
}
