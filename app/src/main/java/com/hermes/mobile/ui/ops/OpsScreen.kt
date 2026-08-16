package com.hermes.mobile.ui.ops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.ui.files.FilesScreen
import com.hermes.mobile.ui.home.HomeViewModel
import com.hermes.mobile.ui.terminal.TerminalScreen

private enum class OpsPane { MENU, TERMINAL, FILES }

/**
 * Ops tab: PC status summary + entry points to the embedded terminal and the
 * PC file browser (spec §D.1 — everything past Cockpit/Sessions lives here).
 */
@Composable
fun OpsScreen(
    vm: HomeViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    var pane by rememberSaveable { mutableStateOf(OpsPane.MENU.name) }

    when (OpsPane.valueOf(pane)) {
        OpsPane.TERMINAL -> PaneScaffold(title = "Terminal", onBack = { pane = OpsPane.MENU.name }) {
            TerminalScreen()
        }
        OpsPane.FILES -> PaneScaffold(title = "PC Files", onBack = { pane = OpsPane.MENU.name }) {
            FilesScreen()
        }
        OpsPane.MENU -> OpsMenu(vm, onOpen = { pane = it.name })
    }
}

@Composable
private fun PaneScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "Back to ops" },
            ) { Text("← Ops") }
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        content()
    }
}

@Composable
private fun OpsMenu(vm: HomeViewModel, onOpen: (OpsPane) -> Unit) {
    val conn by vm.connState.collectAsState()
    val channel by vm.channelState.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ops", style = MaterialTheme.typography.headlineSmall)

        (conn as? ConnState.Connected)?.let { c ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(c.profile.label, style = MaterialTheme.typography.titleSmall)
                    Text("${c.profile.host}:${c.profile.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Hermes v${c.status?.version ?: "?"} · gateway ${c.status?.gatewayState ?: "?"}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("socket: ${channel.toString().substringAfterLast('.')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        OpsButton("Terminal", "Full Hermes TUI on your PC") { onOpen(OpsPane.TERMINAL) }
        OpsButton("PC Files", "Browse, preview, download") { onOpen(OpsPane.FILES) }
    }
}

@Composable
private fun OpsButton(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = title },
        onClick = onClick,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
