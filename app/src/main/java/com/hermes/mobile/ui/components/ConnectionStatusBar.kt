package com.hermes.mobile.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun ConnectionStatusBar(state: com.hermes.mobile.domain.model.ConnectionState) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            StatusDot(connected = state.telegramConnected, label = "Telegram")
            StatusDot(connected = state.driveConnected, label = "Drive")
            StatusDot(connected = state.pcOnline, label = "PC")
            Spacer(Modifier.weight(1f))
            Text("${state.pendingTasks} pending · ${state.activeTransfers} transfers",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusDot(connected: Boolean, label: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            color = if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.size(8.dp)
        ) {}
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
