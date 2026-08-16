package com.hermes.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.ui.auth.rememberGoogleSignInAction
import com.hermes.mobile.ui.components.SectionHeader
import com.hermes.mobile.ui.components.SettingsBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) {
    val isDark by viewModel.isDarkTheme.collectAsState()
    val driveAccount by viewModel.driveAccount.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val syncedCount by viewModel.syncedCount.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showTelegramDialog by remember { mutableStateOf(false) }

    val startGoogleSignIn = rememberGoogleSignInAction(
        onSuccess = viewModel::onGoogleSignInSuccess,
        onError = viewModel::onGoogleSignInFailed
    )

    LaunchedEffect(userMessage) {
        userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUserMessage()
        }
    }

    if (showTelegramDialog) {
        TelegramConfigDialog(
            initialUserIds = viewModel.getAllowedUserIds(),
            onDismiss = { showTelegramDialog = false },
            onSave = { token, userIds ->
                viewModel.saveTelegramBotToken(token, userIds)
                showTelegramDialog = false
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { viewModel.forceSync() }) {
                        Icon(Icons.Default.Sync, contentDescription = "Force sync")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionHeader("Appearance") }
            item {
                SettingsBar(
                    title = "Dark Theme",
                    subtitle = "Use dark colours throughout the app",
                    icon = Icons.Default.DarkMode,
                    trailing = {
                        Switch(checked = isDark, onCheckedChange = { viewModel.setDarkTheme(it) })
                    }
                )
            }
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionHeader("Connections") }
            item {
                SettingsBar(
                    title = "Telegram Bot",
                    subtitle = if (connection.telegramConnected) "Token configured" else "Not configured — tap to set up",
                    icon = Icons.Default.Chat,
                    onClick = { showTelegramDialog = true }
                )
            }
            item {
                SettingsBar(
                    title = "Google Drive",
                    subtitle = driveAccount ?: "Not signed in — tap to sign in",
                    icon = Icons.Default.AccountCircle,
                    onClick = { if (driveAccount == null) startGoogleSignIn() }
                )
            }
            item {
                SettingsBar(
                    title = "Hermes PC",
                    subtitle = if (connection.pcOnline) "Online · seen in the last 20 min" else "Offline · waiting for outbox activity",
                    icon = Icons.Default.Computer
                )
            }
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionHeader("Data & Privacy") }
            item {
                SettingsBar(
                    title = "Synced files",
                    subtitle = "$syncedCount files cached locally",
                    icon = Icons.Default.Storage
                )
            }
            item {
                SettingsBar(
                    title = "Clear local cache",
                    subtitle = "Free up space used by cached files",
                    icon = Icons.Default.DeleteSweep,
                    onClick = { viewModel.clearLocalCache() }
                )
            }
            item {
                SettingsBar(
                    title = "Log out of Drive",
                    subtitle = "Remove Google account from this device",
                    icon = Icons.Default.Logout,
                    onClick = { viewModel.signOutOfDrive() }
                )
            }
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionHeader("About") }
            item {
                SettingsBar(
                    title = "Hermes Mobile",
                    subtitle = "Version 1.0.0 · Built with Jetpack Compose",
                    icon = Icons.Default.Info
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun TelegramConfigDialog(
    initialUserIds: String,
    onDismiss: () -> Unit,
    onSave: (token: String, userIds: String) -> Unit
) {
    var token by remember { mutableStateOf("") }
    var userIds by remember { mutableStateOf(initialUserIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Telegram Bot", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Create a bot via @BotFather, then paste its token here. The token is stored encrypted on-device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bot Token") },
                    placeholder = { Text("123456:ABC-DEF…") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = userIds,
                    onValueChange = { userIds = it },
                    label = { Text("Your Telegram user ID") },
                    placeholder = { Text("6995160255") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(token.trim(), userIds.trim()) },
                enabled = token.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
