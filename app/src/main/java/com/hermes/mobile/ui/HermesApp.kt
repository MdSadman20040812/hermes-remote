package com.hermes.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.ui.connect.ConnectScreen
import com.hermes.mobile.ui.connect.ConnectViewModel
import com.hermes.mobile.ui.home.HomeScreen
import com.hermes.mobile.ui.theme.HermesMobileTheme

/**
 * App shell (Phase 1): Connect flow until paired, Home (live status) after.
 * NavigationSuiteScaffold with Cockpit/Sessions/Ops lands in Phase 2.
 */
@Composable
fun HermesApp(
    vm: ShellViewModel = hiltViewModel(),
    connectVm: ConnectViewModel = hiltViewModel(),
) {
    HermesMobileTheme {
        val conn by vm.connState.collectAsState()
        val snackbar = remember { SnackbarHostState() }

        // Every ViewModel's userMessage channel lands here — errors are visible.
        LaunchedEffect(Unit) {
            connectVm.userMessage.collect { snackbar.showSnackbar(it) }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (conn) {
                    is ConnState.Connected -> HomeScreen()
                    is ConnState.NoProfile,
                    is ConnState.Failed,
                    -> ConnectScreen()
                    is ConnState.Probing,
                    is ConnState.Connecting,
                    is ConnState.Reconnecting,
                    -> ConnectScreen() // shows its own progress card
                }
            }
        }
    }
}

/** Tiny boot composable so cold start shows something while profiles load. */
@Composable
fun BootSplash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
