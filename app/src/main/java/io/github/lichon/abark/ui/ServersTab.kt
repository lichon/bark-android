package io.github.lichon.abark.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lichon.abark.data.AppSettings

/**
 * Tab 1: the courier state and the bark server this install registers with.
 *
 * There is exactly one server today — the identity is bound to a single scoped token — so this is a single card with
 * the connection on top and the editable server below, not a list. The button persists the server and asks the courier
 * to register the scoped token with it; tuning knobs such as the keepalive live in the Settings tab.
 */
@Composable
fun ServersTab(
    state: UiState,
    notificationsGranted: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onActivate: () -> Unit,
    onRegister: (String) -> Unit,
    onTestPush: () -> Unit,
    onCopyShareLink: () -> Unit,
    onImport: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
    ) {
        item { StatusCard(state, onStart = onStart, onStop = onStop, onActivate = onActivate) }

        if (!notificationsGranted) {
            item {
                WarningCard(
                    title = "Notifications are blocked",
                    body = "Pushes cannot be shown until the notification permission is granted.",
                    actionLabel = "Grant",
                    onAction = onRequestNotifications,
                )
            }
        }
        if (state.hasIdentity && !state.healthy) {
            item {
                WarningCard(
                    title = "Connection not established",
                    body = "Android may kill the background connection. Allow ABark to ignore battery " +
                        "optimisation for reliable delivery.",
                    actionLabel = "Battery settings",
                    onAction = onOpenBatterySettings,
                )
            }
        }

        item {
            ServerCard(
                state = state,
                onRegister = onRegister,
                onTestPush = onTestPush,
                onCopyShareLink = onCopyShareLink,
                onImport = onImport,
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ServerCard(
    state: UiState,
    onRegister: (String) -> Unit,
    onTestPush: () -> Unit,
    onCopyShareLink: () -> Unit,
    onImport: () -> Unit,
) {
    var serverUrl by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }

    SectionCard(title = "Server") {
        Text(
            if (state.hasServer) {
                "Pushes sent to this server with the key below reach this phone."
            } else {
                "Press Register to have the server assign a device key — it is never typed by hand."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("bark-server URL") },
            placeholder = { Text(AppSettings.DEFAULT_SERVER) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // The device key is read-only: `bark-server` mints it during registration and it arrives with the answer.
        Text("Device key", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            state.deviceKey.ifBlank { "not registered yet" },
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = if (state.hasServer) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onRegister(serverUrl) },
                enabled = !state.busy,
            ) { Text("Register") }
            OutlinedButton(onClick = onTestPush, enabled = state.hasServer && !state.busy) { Text("Test push") }
        }

        if (state.hasServer) {
            Text(
                serverUrl.trim().trimEnd('/') + "/" + state.deviceKey,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopyShareLink) { Text("Copy share link") }
                if (!state.hasIdentity) OutlinedButton(onClick = onImport) { Text("Import identity") }
            }
        } else if (state.hasIdentity) {
            Text(
                "Waiting for a device key from the server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
