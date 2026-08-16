package com.hermes.mobile.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.core.connection.ConnState

/**
 * Phase 1 home: proves the spine end-to-end — live /api/status from the PC
 * plus the live socket state. The Cockpit replaces this in Phase 2.
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel = hiltViewModel(),
) {
    val conn by vm.connState.collectAsState()
    val channel by vm.channelState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Hermes Remote", style = MaterialTheme.typography.headlineMedium)

        when (val s = conn) {
            is ConnState.Connected -> {
                StatusCard(
                    label = s.profile.label,
                    address = "${s.profile.host}:${s.profile.port}",
                    version = s.status?.version,
                    gateway = s.status?.gatewayState,
                    sessions = s.status?.activeSessions,
                    overall = s.status?.overall,
                )
                SocketRow(state = channel.toString().substringAfterLast('.'))
            }
            is ConnState.Reconnecting -> Text(
                "Reconnecting to ${s.profile.label} (attempt ${s.attempt})…",
                color = MaterialTheme.colorScheme.tertiary,
            )
            else -> Text("Not connected", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.weight(1f))

        OutlinedButton(
            onClick = { vm.disconnect() },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .semantics { contentDescription = "Disconnect and unpair" },
        ) { Text("Disconnect & forget this PC") }
    }
}

@Composable
private fun StatusCard(
    label: String,
    address: String,
    version: String?,
    gateway: String?,
    sessions: Int?,
    overall: String?,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiary,
                    shape = CircleShape,
                    modifier = Modifier.size(10.dp),
                ) {}
                Spacer(Modifier.size(8.dp))
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
            InfoRow("Address", address)
            version?.let { InfoRow("Hermes", "v$it") }
            gateway?.let { InfoRow("Gateway", it) }
            sessions?.let { InfoRow("Active sessions", it.toString()) }
            overall?.let { InfoRow("Health", it) }
        }
    }
}

@Composable
private fun SocketRow(state: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Socket", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(state, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
