package io.github.lichon.abark.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lichon.abark.data.AppSettings

/**
 * Tab 1: the courier state, the bark server this install registers with, and the optional Supabase Realtime room.
 *
 * The identity is bound to a single bark server — so that is a single card with the connection on top and the editable
 * server below, not a list. The room is an independent second transport; its card keeps its own switch and saved URL/key.
 * The button on the Bark card persists the server and asks the courier to register the scoped token with it; tuning
 * knobs such as the keepalive live in the Settings tab.
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
    onRealtimeEnabled: (Boolean) -> Unit,
    onConnectRealtime: (url: String, key: String, room: String) -> Unit,
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

        item {
            RoomCard(
                state = state,
                onEnabled = onRealtimeEnabled,
                onConnect = onConnectRealtime,
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

    SectionCard(title = "Bark server") {
        Text(
            if (state.hasServer) {
                "Pushes sent to this server with the key below reach this phone."
            } else {
                "Press Register to activate this device if needed, connect to Apple's courier and have the server " +
                    "assign a device key — it is never typed by hand."
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

/**
 * Optional second transport: a Supabase Realtime room alongside the Bark server.
 *
 * The fields mirror the desktop client (project URL, anon key, room name). The Connect button saves them, turns the room
 * on and joins immediately; the switch is the quick way to leave or rejoin without retyping anything.
 */
@Composable
private fun RoomCard(
    state: UiState,
    onEnabled: (Boolean) -> Unit,
    onConnect: (url: String, key: String, room: String) -> Unit,
) {
    var url by remember(state.realtimeUrl) { mutableStateOf(state.realtimeUrl) }
    var key by remember(state.realtimeKey) { mutableStateOf(state.realtimeKey) }
    var room by remember(state.realtimeRoom) { mutableStateOf(state.realtimeRoom) }

    SectionCard(title = "Supabase Realtime") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = state.realtimeEnabled, role = Role.Switch, onValueChange = onEnabled),
        ) {
            Text(
                "Join a room",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.realtimeEnabled, onCheckedChange = null)
        }
        Text(
            "An optional second transport. Messages broadcast to the room arrive here even when the Bark server is down.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Project URL") },
            placeholder = { Text("https://project.supabase.co") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Anon key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = room,
            onValueChange = { room = it },
            label = { Text("Room") },
            placeholder = { Text(AppSettings.DEFAULT_REALTIME_ROOM) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = { onConnect(url, key, room) }, enabled = !state.busy) { Text("Connect") }

        Text(
            when {
                !state.realtimeEnabled -> "Off. Turn the switch on to join the room."
                !state.hasRealtime -> "Add the project URL and anon key to connect."
                state.roomStatusDetail.isBlank() -> state.roomStatusTitle
                else -> "${state.roomStatusTitle} · ${state.roomStatusDetail}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when {
                !state.realtimeEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                state.roomHealthy -> MaterialTheme.colorScheme.primary
                state.roomActive -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.error
            },
        )
    }
}
