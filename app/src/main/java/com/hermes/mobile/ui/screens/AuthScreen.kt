package com.hermes.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.auth.AuthViewModel
import com.hermes.mobile.ui.auth.rememberGoogleSignInAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthRoute(authViewModel: AuthViewModel) {
    val telegramHasToken by authViewModel.telegramHasToken.collectAsState()
    val driveAccount by authViewModel.driveAccount.collectAsState()
    val authMessage by authViewModel.authMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var botToken by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("") }

    val startGoogleSignIn = rememberGoogleSignInAction(
        onSuccess = authViewModel::onGoogleSignInSuccess,
        onError = authViewModel::onGoogleSignInFailed
    )

    LaunchedEffect(authMessage) {
        authMessage?.let {
            snackbarHostState.showSnackbar(it)
            authViewModel.clearAuthMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Setup Hermes Mobile", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.Lock, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Welcome to Hermes Mobile.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "No server. No hosting. Your PC + your Drive = your agentic system.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Step 1 — Telegram
            SetupStepCard(
                stepNumber = 1,
                title = "Telegram Bot",
                done = telegramHasToken,
                description = "Create a bot via @BotFather and paste its token. Used for instant task notifications."
            ) {
                OutlinedTextField(
                    value = botToken,
                    onValueChange = { botToken = it },
                    label = { Text("Bot Token") },
                    placeholder = { Text("123456:ABC-DEF…") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = userId,
                    onValueChange = { userId = it },
                    label = { Text("Your Telegram user ID") },
                    placeholder = { Text("6995160255") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { authViewModel.saveTelegramBotToken(botToken.trim(), userId.trim()) },
                    enabled = botToken.isNotBlank() && !telegramHasToken,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (telegramHasToken) "Saved" else "Save Bot Token")
                }
            }

            // Step 2 — Google Drive
            SetupStepCard(
                stepNumber = 2,
                title = "Google Drive",
                done = driveAccount != null,
                description = "Sign in with Google. Hermes creates HermesInbox / HermesOutbox / HermesShared folders on your Drive."
            ) {
                if (driveAccount != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp))
                        Text(driveAccount ?: "", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    OutlinedButton(onClick = startGoogleSignIn, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AccountCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Sign in with Google")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { authViewModel.setOnboarded() },
                enabled = telegramHasToken || driveAccount != null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.ArrowForward, null)
                Spacer(Modifier.width(8.dp))
                Text("Continue", fontWeight = FontWeight.SemiBold)
            }
            if (!telegramHasToken && driveAccount == null) {
                Text(
                    "Connect at least one channel to continue",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun SetupStepCard(
    stepNumber: Int,
    title: String,
    done: Boolean,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (done) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        if (done) {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                        } else {
                            Text("$stepNumber", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
