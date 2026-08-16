package com.hermes.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.ui.cockpit.CockpitScreen
import com.hermes.mobile.ui.cockpit.CockpitViewModel
import com.hermes.mobile.ui.connect.ConnectScreen
import com.hermes.mobile.ui.connect.ConnectViewModel
import com.hermes.mobile.ui.home.HomeScreen
import com.hermes.mobile.ui.ops.OpsScreen
import com.hermes.mobile.ui.sessions.SessionsScreen
import com.hermes.mobile.ui.theme.HermesMobileTheme

private val TAB_LABELS = listOf("Cockpit", "Sessions", "Ops")
private val TAB_ICONS = listOf(Icons.Default.Email, Icons.Default.List, Icons.Default.Home)

/**
 * App shell (Phase 2): Connect flow until paired, then the 3-tab adaptive
 * scaffold — Cockpit · Sessions · Ops (spec §D.1). Tabs hoist their
 * ViewModels to the activity so state survives tab switches.
 */
@Composable
fun HermesApp(
    vm: ShellViewModel = hiltViewModel(),
    connectVm: ConnectViewModel = hiltViewModel(),
    cockpitVm: CockpitViewModel = hiltViewModel(),
    deepLinkBus: DeepLinkBus = vm.deepLinkBus,
) {
    HermesMobileTheme {
        val conn by vm.connState.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }

        // Every ViewModel's userMessage channel lands here — errors are visible.
        LaunchedEffect(Unit) {
            connectVm.userMessage.collect { snackbar.showSnackbar(it) }
        }
        LaunchedEffect(Unit) {
            cockpitVm.userMessage.collect { snackbar.showSnackbar(it) }
        }

        // Telegram doorbell / notification deep links → open that session.
        LaunchedEffect(Unit) {
            deepLinkBus.links.collect { storedId ->
                cockpitVm.resumeAndOpen(storedId, "Linked session")
                selectedTab = 0
            }
        }

        // "Send to Hermes" shares → new prompt in the cockpit.
        LaunchedEffect(Unit) {
            vm.shareBus.shares.collect { text ->
                cockpitVm.send(text)
                selectedTab = 0
            }
        }

        when (conn) {
            is ConnState.Connected -> NavigationSuiteScaffold(
                navigationSuiteItems = {
                    TAB_LABELS.forEachIndexed { index, label ->
                        item(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(TAB_ICONS[index], contentDescription = null) },
                            label = { Text(label) },
                            modifier = Modifier.semantics { contentDescription = "$label tab" },
                        )
                    }
                },
            ) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    containerColor = MaterialTheme.colorScheme.background,
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (selectedTab) {
                            0 -> CockpitScreen(vm = cockpitVm)
                            1 -> SessionsScreen(onOpen = { summary ->
                                cockpitVm.resumeAndOpen(summary.id, summary.title)
                                selectedTab = 0
                            })
                            2 -> OpsScreen()
                        }
                    }
                }
            }
            else -> {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    containerColor = MaterialTheme.colorScheme.background,
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        ConnectScreen(vm = connectVm)
                    }
                }
            }
        }
    }
}
