package io.github.lichon.abark.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import io.github.lichon.abark.data.AppSettings

/** Tab 3: delivery permissions, courier tuning, the device identity, and app housekeeping. */
@Composable
fun SettingsTab(
    state: UiState,
    notificationsGranted: Boolean,
    onAutoStart: (Boolean) -> Unit,
    onTts: (Boolean) -> Unit,
    onKeepAlive: (Int) -> Unit,
    onActivate: () -> Unit,
    onImport: () -> Unit,
    onClearIdentity: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onRequestNotifications: () -> Unit,
    onBattery: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        item {
            SectionCard(title = "Delivery") {
                InfoRow(
                    label = "Notifications",
                    value = if (notificationsGranted) "granted" else "blocked",
                    actionLabel = if (notificationsGranted) null else "Grant",
                    onAction = onRequestNotifications,
                )
                InfoRow(
                    label = "Battery optimisation",
                    value = "allow to keep the connection alive",
                    actionLabel = "Settings",
                    onAction = onBattery,
                )
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Start on boot", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "keep receiving after a restart",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.autoStart, onCheckedChange = onAutoStart)
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Read notifications aloud", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "speaks each push with the system text-to-speech engine; passive pushes stay silent",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.ttsEnabled, onCheckedChange = onTts)
                }
                HorizontalDivider()
                KeepAliveRow(
                    currentSeconds = state.keepAliveSeconds,
                    onKeepAlive = onKeepAlive,
                )
            }
        }

        item {
            SectionCard(title = "Identity") {
                if (state.hasIdentity) {
                    Text(state.identitySummary, style = MaterialTheme.typography.bodyMedium)
                    if (state.scopedToken.isNotBlank()) {
                        Text(
                            "scoped token ${state.scopedToken.take(16)}…",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "The identity holds the device private key. Activation uses the FairPlay key bundled in this " +
                            "build, so it can happen here; identities from tools/cli activate can still be imported.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "No device identity yet. Activate on this device (talks to Apple's Alberts service), or import " +
                            "the JSON written by tools/cli activate.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onActivate,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                state.busy -> "Activating…"
                                state.hasIdentity -> "Activate a new identity"
                                else -> "Activate on this device"
                            },
                        )
                    }
                    if (!state.hasIdentity) {
                        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                            Text("Import identity")
                        }
                    } else {
                        OutlinedButton(onClick = onClearIdentity, modifier = Modifier.fillMaxWidth()) {
                            Text("Remove identity")
                        }
                    }
                }            }
        }

        item {
            SectionCard(title = "App") {
                InfoRow(
                    label = "Version",
                    value = state.versionName.ifBlank { "unknown" },
                    actionLabel = if (state.busy) null else "Check updates",
                    onAction = onCheckForUpdates,
                )
                Text(
                    "Unofficial client. Not affiliated with Apple, Finb/Bark, or Beeper.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * The courier's idle keepalive. It is a connection knob, not a server setting, so it lives with the other delivery
 * options; the value only takes effect on the next connection, which applying triggers. The field refuses out-of-range
 * values rather than clamping them, so a pasted "900" is visibly rejected instead of silently becoming 300.
 */
@Composable
private fun KeepAliveRow(currentSeconds: Int, onKeepAlive: (Int) -> Unit) {
    var seconds by remember(currentSeconds) { mutableStateOf(currentSeconds.toString()) }
    val range = "${AppSettings.MIN_KEEPALIVE_SECONDS}–${AppSettings.MAX_KEEPALIVE_SECONDS}"
    val typed = seconds.toIntOrNull()
    val inRange = typed != null &&
        typed in AppSettings.MIN_KEEPALIVE_SECONDS..AppSettings.MAX_KEEPALIVE_SECONDS

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Keepalive", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Seconds between pings while idle ($range). " +
                    "${AppSettings.DEFAULT_KEEPALIVE_SECONDS} is the safe default; only go above 180 on Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = if (seconds.isNotBlank() && !inRange) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        OutlinedTextField(
            value = seconds,
            onValueChange = { seconds = it.filter(Char::isDigit).take(3) },
            singleLine = true,
            isError = seconds.isNotBlank() && !inRange,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(84.dp),
        )
        TextButton(
            onClick = { onKeepAlive(typed ?: currentSeconds) },
            enabled = inRange && typed != currentSeconds,
        ) { Text("Apply") }
    }
}

@Composable
fun ImportIdentityDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste the JSON produced by `tools/cli activate --store …`. It contains the device private key: " +
                        "transfer it over a channel you trust and clear the clipboard afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("identity JSON") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
