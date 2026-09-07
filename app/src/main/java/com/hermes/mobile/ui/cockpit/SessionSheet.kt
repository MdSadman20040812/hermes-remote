package com.hermes.mobile.ui.cockpit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.components.Meter
import com.hermes.mobile.ui.components.NavRow
import com.hermes.mobile.ui.components.SectionLabel
import com.hermes.mobile.ui.theme.HermesMono
import com.hermes.mobile.ui.theme.hermes

/**
 * Everything you can do *to* the open session, in one sheet.
 *
 * A bottom sheet rather than an overflow menu because these are consequential
 * actions that deserve their labels and their token accounting on screen —
 * "compress" is not something anyone should trigger from a 40dp menu row
 * without knowing how full the context already is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(vm: CockpitViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val title by vm.activeTitle.collectAsState()
    val usage by vm.usage.collectAsState()
    val contextPercent by vm.contextPercent.collectAsState()

    var renaming by remember { mutableStateOf(false) }
    var draft by remember(title) { mutableStateOf(title) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            if (renaming) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Session name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { renaming = false },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            if (draft.isNotBlank()) vm.rename(draft.trim())
                            renaming = false
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Save") }
                }
            } else {
                Text(title, style = MaterialTheme.typography.titleMedium)
            }

            // ---- context budget ----
            contextPercent?.let { percent ->
                SectionLabel("Context")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Meter(
                        fraction = percent / 100f,
                        modifier = Modifier.weight(1f),
                        tone = when {
                            percent >= 85 -> MaterialTheme.colorScheme.error
                            percent >= 65 -> MaterialTheme.hermes.warning
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("$percent%", style = MaterialTheme.typography.labelMedium)
                }
                usage?.let { u ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append("${u.calls} calls · ")
                            append("${u.input.thousands()} in · ")
                            append("${u.output.thousands()} out")
                            u.contextMax?.takeIf { it > 0 }?.let {
                                append(" · window ${it.thousands()}")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = HermesMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                usage?.creditLines?.takeIf { it.isNotEmpty() }?.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionLabel("Actions")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NavRow(
                    title = "Rename",
                    subtitle = "Also renames it on the desktop",
                    icon = Icons.Outlined.DriveFileRenameOutline,
                    onClick = { renaming = true },
                )
                NavRow(
                    title = "Branch",
                    subtitle = "Fork the history and continue on the copy",
                    icon = Icons.Outlined.CallSplit,
                    onClick = {
                        vm.branch()
                        onDismiss()
                    },
                )
                NavRow(
                    title = "Compress history",
                    subtitle = "Summarise older turns to free the context window",
                    icon = Icons.Outlined.Compress,
                    onClick = {
                        vm.compress()
                        onDismiss()
                    },
                )
                NavRow(
                    title = "Undo last exchange",
                    subtitle = "Interrupt the turn first if one is running",
                    icon = Icons.Outlined.Undo,
                    onClick = {
                        vm.undo()
                        onDismiss()
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 1_234_567 → "1.2M". Raw token counts are unreadable at a glance. */
internal fun Long.thousands(): String = when {
    this >= 1_000_000 -> String.format("%.1fM", this / 1_000_000.0)
    this >= 1_000 -> String.format("%.1fk", this / 1_000.0)
    else -> toString()
}
