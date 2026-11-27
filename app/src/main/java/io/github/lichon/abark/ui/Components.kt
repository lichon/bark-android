package io.github.lichon.abark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** A titled card used for every block inside a tab. */
@Composable
fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            content()
        }
    }
}

/** Label on the left, value (and optional action) on the right. */
@Composable
fun InfoRow(
    label: String,
    value: String = "",
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, enabled = actionEnabled) { Text(actionLabel) }
        }
    }
}

/** Courier state: dot, phase, Activate/Start/Stop and whatever detail the transport reported. */
@Composable
fun StatusCard(state: UiState, onStart: () -> Unit, onStop: () -> Unit, onActivate: () -> Unit) {
    val showRoom = !state.running && state.roomActive
    val title = if (showRoom) state.roomStatusTitle else state.statusTitle
    val detail = if (showRoom) state.roomStatusDetail else state.statusDetail
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(
                        when {
                            state.healthy || state.roomHealthy -> Color(0xFF4CAF50)
                            state.running || state.roomActive -> Color(0xFFFFB300)
                            else -> Color(0xFF9E9E9E)
                        },
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (state.running || state.roomActive) {
                OutlinedButton(onClick = onStop) { Text("Stop") }
            } else if (state.hasIdentity || state.hasRealtime) {
                Button(onClick = onStart) { Text("Start") }
            } else {
                // Without an identity there is nothing to start, so the primary action is the thing that creates one.
                Button(onClick = onActivate, enabled = !state.busy) {
                    Text(if (state.busy) "Activating…" else "Activate")
                }
            }
        }
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.hasIdentity) {
            Text(
                state.identitySummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                if (state.hasRealtime) {
                    "No APNs identity: the room still works, but the Bark server stays off until you activate one."
                } else {
                    "No device identity yet. Tap Activate to create one, or import the JSON from tools/cli activate."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.hasRealtime) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        if (state.scopedToken.isNotBlank()) {
            Text(
                "token ${state.scopedToken.take(16)}…",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun WarningCard(title: String, body: String, actionLabel: String, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Text(body, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
