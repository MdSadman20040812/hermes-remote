package com.hermes.mobile.ui.cockpit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.isGranted
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.domain.model.TranscriptItem
import com.hermes.mobile.domain.model.TurnPhase

/**
 * The Cockpit (spec §D.2) — the screen the app is really about.
 * Streaming transcript + tool timeline + live-turn controls + composer.
 */
@Composable
fun CockpitScreen(
    vm: CockpitViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsState()
    val phase by vm.turnPhase.collectAsState()
    val title by vm.activeTitle.collectAsState()
    val conn by vm.connState.collectAsState()
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    // Auto-follow the stream when already at the bottom.
    LaunchedEffect(items.size, (items.lastOrNull() as? TranscriptItem.AssistantMessage)?.text?.length) {
        if (items.isNotEmpty() && !listState.canScrollForward) {
            listState.animateScrollToItem(items.size - 1)
        }
    }
    LaunchedEffect(phase) {
        when (phase) {
            TurnPhase.RUNNING -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            TurnPhase.IDLE -> {}
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // ---- status rail ----
        StatusRail(title = title, conn = conn)

        // ---- transcript ----
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.key }) { item ->
                when (item) {
                    is TranscriptItem.UserMessage -> UserBubble(item.text)
                    is TranscriptItem.AssistantMessage -> AssistantBlock(item)
                    is TranscriptItem.ThinkingBlock -> ThinkingRow(item)
                    is TranscriptItem.ToolCallItem -> ToolRow(item)
                    is TranscriptItem.ApprovalCard -> ApprovalRow(
                        item,
                        onRespond = { choice -> vm.respondApproval(item, choice) },
                    )
                    is TranscriptItem.StatusLine -> Text(
                        item.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (phase == TurnPhase.RUNNING && items.none {
                    (it as? TranscriptItem.AssistantMessage)?.streaming == true
                }
            ) {
                item { ThinkingPlaceholder() }
            }
        }

        // ---- live-turn controls (only while running) ----
        AnimatedVisibility(phase == TurnPhase.RUNNING) {
            LiveTurnBar(onInterrupt = { vm.interrupt() }, onSteer = { vm.steer(it) })
        }

        // ---- composer ----
        Composer(onSend = { vm.send(it) })
    }
}

@Composable
private fun StatusRail(title: String, conn: ConnState) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val (dotColor, label) = when (conn) {
                is ConnState.Connected -> MaterialTheme.colorScheme.tertiary to conn.profile.label
                is ConnState.Reconnecting -> MaterialTheme.colorScheme.secondary to "reconnecting…"
                else -> MaterialTheme.colorScheme.error to "offline"
            }
            Surface(color = dotColor, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AssistantBlock(item: TranscriptItem.AssistantMessage) {
    Column {
        Text(
            "Hermes" + (item.usageContextPercent?.let { " · $it% ctx" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(item.text, style = MaterialTheme.typography.bodyMedium)
        if (item.streaming) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        }
    }
}

@Composable
private fun ThinkingRow(item: TranscriptItem.ThinkingBlock) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .semantics { contentDescription = "Thinking block, tap to expand" },
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.live) "thinking…" else "thinking",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "collapse" else "expand",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolRow(item: TranscriptItem.ToolCallItem) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .semantics { contentDescription = "Tool call ${item.name}, tap for details" },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚙", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(6.dp))
                Text(item.name, style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.size(8.dp))
                item.context?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                } ?: Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(8.dp))
                if (item.running) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                } else {
                    Text(
                        item.durationS?.let { "%.0fms ✓".format(it * 1000) } ?: "✓",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            item.approvalNote?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
            if (expanded) {
                item.args?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("args: $it", style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                }
                item.result?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "result: ${it.take(800)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalRow(card: TranscriptItem.ApprovalCard, onRespond: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Approval needed", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
            card.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
            card.command?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
            if (card.resolved != null) {
                Text("Answered: ${card.resolved}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    card.choices.forEach { choice ->
                        TextButton(
                            onClick = { onRespond(choice) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = "Approval choice $choice" },
                        ) { Text(choice.replaceFirstChar { it.uppercase() }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingPlaceholder() {
    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        Spacer(Modifier.size(8.dp))
        Text("Hermes is working…", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LiveTurnBar(onInterrupt: () -> Unit, onSteer: (String) -> Unit) {
    var steerMode by remember { mutableStateOf(false) }
    var steerText by remember { mutableStateOf("") }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (steerMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = steerText,
                        onValueChange = { steerText = it },
                        placeholder = { Text("Steer this turn…") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { if (steerText.isNotBlank()) { onSteer(steerText); steerText = ""; steerMode = false } },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Send steer" },
                    ) { Text("Steer") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(
                        onClick = onInterrupt,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Interrupt this turn" },
                    ) { Text("⏸ Interrupt", color = MaterialTheme.colorScheme.error) }
                    TextButton(
                        onClick = { steerMode = true },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Steer this turn" },
                    ) { Text("↪ Steer") }
                }
            }
        }
    }
}

@OptIn(com.google.accompanist.permissions.ExperimentalPermissionsApi::class)
@Composable
private fun Composer(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val voice = remember { com.hermes.mobile.core.voice.VoiceInputController(context) }
    val listening by voice.listening.collectAsState()
    val partial by voice.partial.collectAsState()

    androidx.compose.runtime.DisposableEffect(Unit) {
        voice.onFinalResult = { spoken -> text = spoken }
        onDispose { voice.destroy() }
    }

    val micPermission = com.google.accompanist.permissions.rememberPermissionState(
        android.Manifest.permission.RECORD_AUDIO,
    )

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            if (listening && partial.isNotBlank()) {
                Text(
                    partial,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Message Hermes…") },
                    maxLines = 4,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Message input" },
                )
                Spacer(Modifier.size(8.dp))
                IconButton(
                    onClick = {
                        if (micPermission.status.isGranted) {
                            if (listening) voice.stop() else voice.start()
                        } else {
                            micPermission.launchPermissionRequest()
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = if (listening) "Stop voice input" else "Hold to talk" },
                ) {
                    Text(if (listening) "⏹" else "🎙")
                }
                IconButton(
                    onClick = { if (text.isNotBlank()) { onSend(text); text = "" } },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Send message" },
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
