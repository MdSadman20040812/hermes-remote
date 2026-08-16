package com.hermes.mobile.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.core.terminal.PtyState

/**
 * Terminal tab — the full Hermes TUI over /api/pty (Phase 0: binary frames,
 * works on Windows via ConPTY). Plain-text scrollback + mobile key row.
 */
@Composable
fun TerminalScreen(
    vm: TerminalViewModel = hiltViewModel(),
) {
    val scrollback by vm.scrollback.collectAsState()
    val state by vm.state.collectAsState()
    val scrollState = rememberScrollState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.connect() }
    DisposableEffect(Unit) { onDispose { vm.disconnect() } }
    LaunchedEffect(scrollback) {
        scrollState.animateScrollTo(scrollableMax(scrollState))
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // status line
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (state) {
                    PtyState.OPEN -> "● connected"
                    PtyState.CONNECTING -> "◌ connecting…"
                    PtyState.RECONNECTING -> "◌ reconnecting…"
                    PtyState.ENDED -> "○ session ended"
                    PtyState.CLOSED -> "○ closed"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when (state) {
                    PtyState.OPEN -> MaterialTheme.colorScheme.tertiary
                    PtyState.ENDED, PtyState.CLOSED -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.secondary
                },
            )
        }

        // scrollback
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                scrollback,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(8.dp)
                    .semantics { contentDescription = "Terminal output" },
            )
        }

        // mobile key row (pty-mobile-input.ts key set: Esc Tab Ctrl arrows | ~ /)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyButton("Esc", "\u001B") { vm.sendInput("\u001B") }
            KeyButton("Tab", "\t") { vm.sendInput("\t") }
            KeyButton("←", "\u001B[D") { vm.sendInput("\u001B[D") }
            KeyButton("↓", "\u001B[B") { vm.sendInput("\u001B[B") }
            KeyButton("↑", "\u001B[A") { vm.sendInput("\u001B[A") }
            KeyButton("→", "\u001B[C") { vm.sendInput("\u001B[C") }
            KeyButton("Ctrl-C", "\u0003") { vm.sendInput("\u0003") }
            KeyButton("Ctrl-D", "\u0004") { vm.sendInput("\u0004") }
            KeyButton("|", "|") { vm.sendInput("|") }
            KeyButton("~", "~") { vm.sendInput("~") }
            KeyButton("/", "/") { vm.sendInput("/") }
        }

        // input row
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { new ->
                    // Whole-value replacement from the IME → normalized resend.
                    vm.noteImeReplacement()
                    input = new
                },
                placeholder = { Text("type a command…") },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Terminal input" },
            )
            IconButton(
                onClick = {
                    if (input.isNotEmpty()) {
                        vm.sendInput(input + "\r")
                        input = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Send to terminal" },
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, send: String, onSend: (String) -> Unit) {
    TextButton(
        onClick = { onSend(send) },
        modifier = Modifier.semantics { contentDescription = "Key $label" },
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

private fun scrollableMax(state: androidx.compose.foundation.ScrollState): Int =
    state.maxValue.let { if (it == Int.MAX_VALUE) 0 else it }
