package com.hermes.mobile.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubmitRoute(
    onSubmitted: () -> Unit = {},
    viewModel: SubmitTaskViewModel = hiltViewModel()
) {
    var prompt by remember { mutableStateOf("") }
    var priority by remember { mutableIntStateOf(0) }
    var notify by remember { mutableStateOf(true) }
    var attachments by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val isSubmitting by viewModel.isSubmitting.collectAsState()
    val submittedTaskId by viewModel.submittedTaskId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris -> attachments = uris }

    LaunchedEffect(submittedTaskId) {
        submittedTaskId?.let { id ->
            snackbarHostState.showSnackbar("Task #$id queued — your PC will pick it up")
            viewModel.consumeSubmitted()
            prompt = ""
            attachments = emptyList()
            priority = 0
            onSubmitted()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("New Task", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (isSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(
                            onClick = {
                                viewModel.submit(prompt, attachments, priority, notify)
                            },
                            enabled = prompt.isNotBlank()
                        ) {
                            Text("Send", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("What should Hermes do?") },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                isError = prompt.isBlank(),
                supportingText = { if (prompt.isBlank()) Text("Enter a prompt to send to your PC") },
                minLines = 5
            )

            Text("Quick prompts", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                QuickPromptChip("Summarise latest research") { prompt = it }
                QuickPromptChip("Generate report") { prompt = it }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Priority:", style = MaterialTheme.typography.bodyMedium)
                FilterChip(selected = priority == 0, onClick = { priority = 0 }, label = { Text("Normal") })
                FilterChip(selected = priority == 1, onClick = { priority = 1 }, label = { Text("High") })
                FilterChip(selected = priority == 2, onClick = { priority = 2 }, label = { Text("Critical") })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = notify, onCheckedChange = { notify = it })
                Text("Notify me when done", modifier = Modifier.padding(start = 8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { pickLauncher.launch("*/*") }) {
                    Icon(Icons.Default.AttachFile, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Attach files")
                }
                Spacer(Modifier.width(8.dp))
                Text("${attachments.size} files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (attachments.isNotEmpty()) {
                attachments.take(3).forEach { uri ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.InsertDriveFile, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                uri.lastPathSegment ?: uri.toString(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Your PC picks this up from Drive → HermesInbox. No servers, no hosting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickPromptChip(label: String, onClick: (String) -> Unit) {
    Surface(
        onClick = { onClick(label) },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}
