package com.hermes.mobile.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.hermes.mobile.ui.auth.AuthViewModel
import com.hermes.mobile.ui.screens.AuthRoute
import com.hermes.mobile.ui.screens.FilesRoute
import com.hermes.mobile.ui.screens.MessagesRoute
import com.hermes.mobile.ui.screens.SettingsRoute
import com.hermes.mobile.ui.screens.SubmitRoute
import com.hermes.mobile.ui.screens.TasksRoute
import com.hermes.mobile.ui.screens.WelcomeScreen
import com.hermes.mobile.ui.theme.HermesMobileTheme

private enum class HermesTab(val title: String, val icon: ImageVector) {
    Tasks("Tasks", Icons.Default.Assignment),
    Files("Files", Icons.Default.Folder),
    Submit("Submit", Icons.Default.Send),
    Messages("Messages", Icons.Default.Chat),
    Settings("Settings", Icons.Default.Settings)
}

/**
 * Root composable: Welcome → Auth setup → main shell.
 * The shell keeps the Hermes 5-tab bottom-nav design.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HermesApp(authViewModel: AuthViewModel = hiltViewModel()) {
    val isDarkTheme by authViewModel.isDarkTheme.collectAsState()
    val isOnboarded by authViewModel.isOnboarded.collectAsState()
    var welcomeSeen by remember { mutableStateOf(false) }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notifPermission = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
        LaunchedEffect(Unit) {
            if (!notifPermission.status.isGranted) notifPermission.launchPermissionRequest()
        }
    }

    HermesMobileTheme(darkTheme = isDarkTheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            when {
                !welcomeSeen -> WelcomeScreen(onFinished = { welcomeSeen = true })
                !isOnboarded -> AuthRoute(authViewModel = authViewModel)
                else -> HermesMainShell()
            }
        }
    }
}

@Composable
private fun HermesMainShell() {
    var selectedTab by remember { mutableStateOf(HermesTab.Tasks) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                HermesTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                HermesTab.Tasks -> TasksRoute(onNavigateToSubmit = { selectedTab = HermesTab.Submit })
                HermesTab.Files -> FilesRoute()
                HermesTab.Submit -> SubmitRoute(onSubmitted = { selectedTab = HermesTab.Tasks })
                HermesTab.Messages -> MessagesRoute()
                HermesTab.Settings -> SettingsRoute()
            }
        }
    }
}
