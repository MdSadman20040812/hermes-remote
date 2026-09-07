package com.hermes.mobile.ui.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.theme.HermesTerminalStyle
import com.hermes.mobile.ui.theme.hermes

/**
 * The tail of the agent log.
 *
 * Lines are never wrapped — a wrapped stack trace on a 6" screen is worse than
 * one you have to scroll sideways — and severity is carried by colour on the
 * line itself rather than by a filter chip nobody will reach for at 2am.
 */
@Composable
fun LogsPanel(vm: OpsViewModel) {
    val lines by vm.logs.collectAsState()
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val semantics = MaterialTheme.hermes

    LaunchedEffect(Unit) { vm.loadLogs() }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        PanelProgress(vm, "logs")
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${lines.size} lines",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { vm.loadLogs() },
                modifier = Modifier.semantics { contentDescription = "Reload the log tail" },
            ) { Icon(Icons.Outlined.Refresh, contentDescription = null, Modifier.size(18.dp)) }
        }

        if (lines.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Subject,
                title = "Log is empty",
                hint = "Nothing has been written to the agent log yet.",
            )
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .background(
                    semantics.code,
                    androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                )
                .horizontalScroll(hScroll),
            contentPadding = PaddingValues(10.dp),
        ) {
            itemsIndexed(lines) { index, line ->
                Text(
                    line,
                    style = HermesTerminalStyle,
                    softWrap = false,
                    color = when {
                        line.contains("ERROR", true) || line.contains("CRITICAL", true) ->
                            MaterialTheme.colorScheme.error
                        line.contains("WARN", true) -> semantics.warning
                        line.contains("DEBUG", true) ->
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        else -> semantics.onCode
                    },
                    modifier = Modifier.semantics { contentDescription = "Log line ${index + 1}" },
                )
            }
        }
    }
}
